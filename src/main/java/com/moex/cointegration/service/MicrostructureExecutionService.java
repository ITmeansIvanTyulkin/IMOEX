package com.moex.cointegration.service;

import com.moex.cointegration.client.MoexIssClient;
import com.moex.cointegration.config.MicrostructureProperties;
import com.moex.cointegration.config.SessionProperties;
import com.moex.cointegration.model.BookKind;
import com.moex.cointegration.model.Candle;
import com.moex.cointegration.model.OrderBookSnapshot;
import com.moex.cointegration.model.TradingSignal;
import com.moex.cointegration.quant.microstructure.CandleMicrostructure;
import com.moex.cointegration.quant.microstructure.CandleMicrostructureAnalyzer;
import com.moex.cointegration.quant.microstructure.ClusterDetector;
import com.moex.cointegration.quant.microstructure.DomAnalyzer;
import com.moex.cointegration.quant.microstructure.FootprintAnalyzer;
import com.moex.cointegration.quant.microstructure.IcebergDetector;
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
 * ATAS-inspired execution gates для INTRADAY mean-reversion (ISS OHLCV + DOM proxies).
 */
@Service
public class MicrostructureExecutionService {

    private static final Logger log = LoggerFactory.getLogger(MicrostructureExecutionService.class);
    private static final double DOM_IMBALANCE_MIN = 0.08;

    private final MicrostructureProperties properties;
    private final SessionProperties sessionProperties;
    private final MarketDataStorage storage;
    private final MoexIssClient moexIssClient;

    @Autowired
    public MicrostructureExecutionService(
            MicrostructureProperties properties,
            SessionProperties sessionProperties,
            MarketDataStorage storage,
            MoexIssClient moexIssClient
    ) {
        this.properties = properties;
        this.sessionProperties = sessionProperties;
        this.storage = storage;
        this.moexIssClient = moexIssClient;
    }

    /** Replay / тесты без DOM. */
    MicrostructureExecutionService(
            MicrostructureProperties properties,
            SessionProperties sessionProperties,
            MarketDataStorage storage
    ) {
        this(properties, sessionProperties, storage, null);
    }

    /** Тесты без Spring. */
    MicrostructureExecutionService(MicrostructureProperties properties, MarketDataStorage storage) {
        this(properties, SessionProperties.defaults(), storage, null);
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

            String footprintBlock = footprintBlock(candlesY, candlesX, signal, lookback);
            if (footprintBlock != null) {
                return MicrostructureVerdict.block(footprintBlock);
            }

            String clusterBlock = clusterBlock(candlesY, candlesX, lookback);
            if (clusterBlock != null) {
                return MicrostructureVerdict.block(clusterBlock);
            }

            if (properties.domEnabledFlag() && moexIssClient != null) {
                String domBlock = domBlock(tickerY, tickerX, signal);
                if (domBlock != null) {
                    return MicrostructureVerdict.block(domBlock);
                }
            }

            if (properties.blockIcebergSuspectEnabled()) {
                String icebergBlock = icebergBlock(candlesY, candlesX, lookback);
                if (icebergBlock != null) {
                    return MicrostructureVerdict.block(icebergBlock);
                }
            }

            return MicrostructureVerdict.pass();
        } catch (IOException ex) {
            log.warn("Microstructure gate IO error {}/{}: {}", tickerY, tickerX, ex.getMessage());
            return MicrostructureVerdict.block("microstructure: " + ex.getMessage());
        }
    }

    /**
     * INTRADAY partial TP: цена ноги в пределах pocProximityBps от POC сессионного профиля.
     */
    public boolean priceNearLegPoc(String ticker, double price) throws IOException {
        if (!properties.pocPartialTpEnabledFlag() || price <= 0 || Double.isNaN(price)) {
            return false;
        }
        List<Candle> candles = loadHourly(ticker);
        if (candles.isEmpty()) {
            return false;
        }
        VolumeProfile vp = VolumeProfileCalculator.fromCandles(candles, properties.volumeProfileLookback());
        if (Double.isNaN(vp.pocPrice()) || vp.pocPrice() <= 0) {
            return false;
        }
        double bps = Math.abs(price - vp.pocPrice()) / vp.pocPrice() * 10_000.0;
        return bps <= properties.pocProximityBps();
    }

    private String footprintBlock(
            List<Candle> candlesY,
            List<Candle> candlesX,
            TradingSignal signal,
            int lookback
    ) {
        double imbY = FootprintAnalyzer.volumeWeightedImbalance(candlesY, lookback);
        double imbX = FootprintAnalyzer.volumeWeightedImbalance(candlesX, lookback);
        double edge = signal == TradingSignal.LONG_SPREAD ? imbY - imbX : imbX - imbY;
        if (edge < properties.minFootprintAlignment()) {
            return String.format(Locale.ROOT,
                    "footprint не подтверждает вход (edge=%.2f < %.2f)", edge, properties.minFootprintAlignment());
        }
        return null;
    }

    private String clusterBlock(List<Candle> candlesY, List<Candle> candlesX, int lookback) {
        ClusterDetector.ClusterHit hitY = ClusterDetector.strongestCluster(
                candlesY, lookback, properties.clusterVolumeMult());
        ClusterDetector.ClusterHit hitX = ClusterDetector.strongestCluster(
                candlesX, lookback, properties.clusterVolumeMult());
        if (hitY != null && hitY.atEdge()) {
            return String.format(Locale.ROOT,
                    "cluster Y на краю VA (share=%.0f%%) — риск ложного mean-reversion", hitY.volumeShare() * 100);
        }
        if (hitX != null && hitX.atEdge()) {
            return String.format(Locale.ROOT,
                    "cluster X на краю VA (share=%.0f%%) — риск ложного mean-reversion", hitX.volumeShare() * 100);
        }
        return null;
    }

    private String domBlock(String tickerY, String tickerX, TradingSignal signal) {
        OrderBookSnapshot obY = moexIssClient.fetchOrderBook(tickerY);
        OrderBookSnapshot obX = moexIssClient.fetchOrderBook(tickerX);
        if (!DomAnalyzer.passesDepth(obY, properties.minDomDepthRub(), properties.maxDomSpreadBps())) {
            return "DOM Y: недостаточная глубина или широкий спред";
        }
        if (!DomAnalyzer.passesDepth(obX, properties.minDomDepthRub(), properties.maxDomSpreadBps())) {
            return "DOM X: недостаточная глубина или широкий спред";
        }
        boolean longSpread = signal == TradingSignal.LONG_SPREAD;
        if (longSpread) {
            if (!DomAnalyzer.supportsLongLeg(obY, DOM_IMBALANCE_MIN)) {
                return "DOM Y не поддерживает покупку (bid-side thin)";
            }
            if (!DomAnalyzer.supportsShortLeg(obX, DOM_IMBALANCE_MIN)) {
                return "DOM X не поддерживает шорт (ask-side thin)";
            }
        } else {
            if (!DomAnalyzer.supportsShortLeg(obY, DOM_IMBALANCE_MIN)) {
                return "DOM Y не поддерживает шорт";
            }
            if (!DomAnalyzer.supportsLongLeg(obX, DOM_IMBALANCE_MIN)) {
                return "DOM X не поддерживает покупку";
            }
        }
        return null;
    }

    private String icebergBlock(List<Candle> candlesY, List<Candle> candlesX, int lookback) {
        double mult = properties.clusterVolumeMult();
        double maxRange = properties.maxSpreadProxyBps();
        if (IcebergDetector.suspectedIceberg(candlesY, lookback, mult, maxRange)) {
            return "iceberg proxy на ноге Y — скрытая ликвидность, вход отложен";
        }
        if (IcebergDetector.suspectedIceberg(candlesX, lookback, mult, maxRange)) {
            return "iceberg proxy на ноге X — скрытая ликвидность, вход отложен";
        }
        return null;
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

    private List<Candle> loadHourly(String ticker) throws IOException {
        List<Candle> hourly = storage.loadHourlyCandles(ticker);
        if (!hourly.isEmpty()) {
            return hourly;
        }
        return storage.loadCandles(ticker);
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
