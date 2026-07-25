package com.moex.cointegration.service;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.Candle;
import com.moex.cointegration.model.PriceSeries;
import com.moex.cointegration.storage.MarketDataStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pre-filter юниверса перед коинтеграцией: ликвидность (оборот), мин. цена,
 * proxy «шортабельности» (отсев привилегированных *P — обычно тоньше и хуже для шорта).
 * <p>
 * Истинный список shortable даёт только брокер; здесь — исполнимость пары на дневном горизонте.
 */
@Service
public class UniverseFilterService {

    private static final Logger log = LoggerFactory.getLogger(UniverseFilterService.class);

    private final MarketDataStorage storage;
    private final ImoexProperties properties;

    public UniverseFilterService(MarketDataStorage storage, ImoexProperties properties) {
        this.storage = storage;
        this.properties = properties;
    }

    /**
     * Оставляет только тикеры, прошедшие {@code imoex.universe.*}.
     */
    public Map<String, PriceSeries> filter(Map<String, PriceSeries> seriesByTicker) throws IOException {
        ImoexProperties.UniverseProperties u = properties.universe();
        if (!u.enabled() || seriesByTicker == null || seriesByTicker.isEmpty()) {
            return seriesByTicker;
        }

        Map<String, PriceSeries> kept = new LinkedHashMap<>();
        List<String> rejected = new ArrayList<>();

        for (Map.Entry<String, PriceSeries> e : seriesByTicker.entrySet()) {
            String ticker = e.getKey();
            Metrics m = metrics(ticker);
            String reason = rejectReason(ticker, m, u);
            if (reason == null) {
                kept.put(ticker, e.getValue());
            } else {
                rejected.add(ticker + " (" + reason + ")");
            }
        }

        log.info("Universe filter: {} → {} tickers (rejected {})",
                seriesByTicker.size(), kept.size(), rejected.size());
        if (!rejected.isEmpty() && log.isDebugEnabled()) {
            log.debug("Rejected: {}", rejected);
        } else if (!rejected.isEmpty()) {
            int show = Math.min(12, rejected.size());
            log.info("Rejected sample: {}", rejected.subList(0, show));
        }
        return kept;
    }

    String rejectReason(String ticker, Metrics m, ImoexProperties.UniverseProperties u) {
        if (m == null) {
            return "нет свечей для метрик";
        }
        if (u.excludePreferred() && isPreferredShare(ticker)) {
            return "preferred *P";
        }
        if (m.lastClose() < u.minPrice()) {
            return String.format(Locale.ROOT, "price=%.2f < %.2f", m.lastClose(), u.minPrice());
        }
        if (m.medianTurnoverRub() < u.minMedianTurnoverRub()) {
            return String.format(Locale.ROOT, "ADV≈%.0f < %.0f",
                    m.medianTurnoverRub(), u.minMedianTurnoverRub());
        }
        if (m.zeroVolumeFraction() > u.maxZeroVolumeFraction()) {
            return String.format(Locale.ROOT, "zeroVol=%.2f > %.2f",
                    m.zeroVolumeFraction(), u.maxZeroVolumeFraction());
        }
        return null;
    }

    static boolean isPreferredShare(String ticker) {
        if (ticker == null || ticker.length() < 2) {
            return false;
        }
        String t = ticker.toUpperCase(Locale.ROOT);
        // SBERP, SNGSP, TATNP, BANEP, MTLRP, RTKMP — не путать с однобуквенным T
        return t.endsWith("P") && t.length() >= 4;
    }

    Metrics metrics(String ticker) throws IOException {
        List<Candle> candles = storage.loadCandles(ticker);
        if (candles == null || candles.isEmpty()) {
            return null;
        }
        int lookback = Math.max(5, properties.universe().lookbackDays());
        int from = Math.max(0, candles.size() - lookback);
        List<Candle> window = candles.subList(from, candles.size());

        double[] turnovers = new double[window.size()];
        int zeros = 0;
        for (int i = 0; i < window.size(); i++) {
            Candle c = window.get(i);
            double vol = c.volume();
            if (vol <= 0 || Double.isNaN(vol)) {
                zeros++;
                turnovers[i] = 0;
            } else {
                turnovers[i] = c.close() * vol;
            }
        }
        Arrays.sort(turnovers);
        double median = turnovers[turnovers.length / 2];
        double lastClose = window.get(window.size() - 1).close();
        double zeroFrac = (double) zeros / window.size();
        return new Metrics(median, lastClose, zeroFrac);
    }

    record Metrics(double medianTurnoverRub, double lastClose, double zeroVolumeFraction) {
    }
}
