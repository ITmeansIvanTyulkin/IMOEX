package com.moex.cointegration.service;

import com.moex.cointegration.config.MicrostructureProperties;
import com.moex.cointegration.config.SessionProperties;
import com.moex.cointegration.model.BookKind;
import com.moex.cointegration.model.Candle;
import com.moex.cointegration.model.TradingSignal;
import com.moex.cointegration.quant.microstructure.CandleMicrostructure;
import com.moex.cointegration.quant.microstructure.CandleMicrostructureAnalyzer;
import com.moex.cointegration.quant.microstructure.PairLegSyncChecker;
import com.moex.cointegration.quant.microstructure.VolumeProfile;
import com.moex.cointegration.quant.microstructure.VolumeProfileCalculator;
import com.moex.cointegration.storage.MarketDataStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;

/**
 * ATAS-inspired execution gates для INTRADAY mean-reversion (ISS OHLCV proxies).
 */
@Service
public class MicrostructureExecutionService {

    private static final Logger log = LoggerFactory.getLogger(MicrostructureExecutionService.class);

    private final MicrostructureProperties properties;
    private final SessionProperties sessionProperties;
    private final MarketDataStorage storage;

    @Autowired
    public MicrostructureExecutionService(
            MicrostructureProperties properties,
            SessionProperties sessionProperties,
            MarketDataStorage storage
    ) {
        this.properties = properties;
        this.sessionProperties = sessionProperties;
        this.storage = storage;
    }

    /** Тесты без Spring. */
    MicrostructureExecutionService(MicrostructureProperties properties, MarketDataStorage storage) {
        this(properties, SessionProperties.defaults(), storage);
    }

    public record MicrostructureVerdict(boolean allowed, String reason) {
        public static MicrostructureVerdict pass() {
            return new MicrostructureVerdict(true, null);
        }

        public static MicrostructureVerdict block(String reason) {
            return new MicrostructureVerdict(false, reason);
        }
    }

    public MicrostructureVerdict evaluateEntry(
            String tickerY,
            String tickerX,
            TradingSignal signal,
            BookKind book,
            LocalDateTime at
    ) {
        if (!properties.enabledFlag() || book != BookKind.INTRADAY || !properties.intradayEnabledFlag()) {
            return MicrostructureVerdict.pass();
        }
        if (signal != TradingSignal.LONG_SPREAD && signal != TradingSignal.SHORT_SPREAD) {
            return MicrostructureVerdict.pass();
        }

        String sessionBlock = sessionEdgeBlock(at);
        if (sessionBlock != null) {
            return MicrostructureVerdict.block(sessionBlock);
        }

        try {
            List<Candle> candlesY = loadHourly(tickerY);
            List<Candle> candlesX = loadHourly(tickerX);
            if (candlesY.isEmpty() || candlesX.isEmpty()) {
                return MicrostructureVerdict.block("нет часовых свечей для microstructure gate");
            }

            int lookback = properties.volumeProfileLookback();
            CandleMicrostructure msY = CandleMicrostructureAnalyzer.analyze(candlesY, lookback);
            CandleMicrostructure msX = CandleMicrostructureAnalyzer.analyze(candlesX, lookback);

            if (msY.relativeVolume() < properties.minRelativeVolume()
                    || msX.relativeVolume() < properties.minRelativeVolume()) {
                return MicrostructureVerdict.block(String.format(Locale.ROOT,
                        "низкий relative volume (Y=%.2f X=%.2f, min=%.2f)",
                        msY.relativeVolume(), msX.relativeVolume(), properties.minRelativeVolume()));
            }

            if (msY.spreadProxyBps() > properties.maxSpreadProxyBps()
                    || msX.spreadProxyBps() > properties.maxSpreadProxyBps()) {
                return MicrostructureVerdict.block(String.format(Locale.ROOT,
                        "широкий spread proxy bps (Y=%.0f X=%.0f, max=%.0f)",
                        msY.spreadProxyBps(), msX.spreadProxyBps(), properties.maxSpreadProxyBps()));
            }

            double volRatio = PairLegSyncChecker.volumeRatio(msY, msX);
            if (volRatio > properties.maxLegVolumeRatio()) {
                return MicrostructureVerdict.block(String.format(Locale.ROOT,
                        "дисбаланс объёма ног ratio=%.1f > %.1f", volRatio, properties.maxLegVolumeRatio()));
            }

            double align = PairLegSyncChecker.alignmentScore(signal, msY, msX);
            if (align < properties.minLegDeltaAlignment()) {
                return MicrostructureVerdict.block(String.format(Locale.ROOT,
                        "order-flow ног не согласован с сигналом (align=%.2f < %.2f)",
                        align, properties.minLegDeltaAlignment()));
            }

            if (properties.blockOutsideValueAreaEnabled()) {
                String vaBlock = valueAreaBlock(candlesY, candlesX, lookback);
                if (vaBlock != null) {
                    return MicrostructureVerdict.block(vaBlock);
                }
            }

            return MicrostructureVerdict.pass();
        } catch (IOException ex) {
            log.warn("Microstructure gate IO error {}/{}: {}", tickerY, tickerX, ex.getMessage());
            return MicrostructureVerdict.block("microstructure: " + ex.getMessage());
        }
    }

    private String sessionEdgeBlock(LocalDateTime at) {
        LocalTime t = at.toLocalTime();
        LocalTime open = parseTime(sessionProperties.sessionOpen(), LocalTime.of(10, 0));
        LocalTime close = parseTime(sessionProperties.preCloseStart(), LocalTime.of(18, 30));
        int openSkip = properties.sessionOpenSkipMinutes();
        int closeSkip = properties.sessionCloseSkipMinutes();

        if (openSkip > 0 && !t.isBefore(open) && t.isBefore(open.plusMinutes(openSkip))) {
            return "microstructure: первые " + openSkip + " мин сессии (тонкий рынок)";
        }
        if (closeSkip > 0 && !t.isBefore(close.minusMinutes(closeSkip))) {
            return "microstructure: pre-close окно " + closeSkip + " мин";
        }
        return null;
    }

    private String valueAreaBlock(List<Candle> candlesY, List<Candle> candlesX, int lookback) {
        VolumeProfile vpY = VolumeProfileCalculator.fromCandles(candlesY, lookback);
        VolumeProfile vpX = VolumeProfileCalculator.fromCandles(candlesX, lookback);
        double priceY = candlesY.get(candlesY.size() - 1).close();
        double priceX = candlesX.get(candlesX.size() - 1).close();

        if (!vpY.containsPrice(priceY)) {
            return "нога Y вне value area (POC/VA gate)";
        }
        if (!vpX.containsPrice(priceX)) {
            return "нога X вне value area (POC/VA gate)";
        }
        return null;
    }

    private List<Candle> loadCandlesForGate(String ticker) throws IOException {
        List<Candle> hourly = storage.loadHourlyCandles(ticker);
        if (!hourly.isEmpty()) {
            return hourly;
        }
        return storage.loadCandles(ticker);
    }

    private List<Candle> loadHourly(String ticker) throws IOException {
        return loadCandlesForGate(ticker);
    }

    private static LocalTime parseTime(String raw, LocalTime fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalTime.parse(raw.trim());
        } catch (Exception ex) {
            return fallback;
        }
    }
}
