package com.moex.cointegration.service;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.BookKind;
import com.moex.cointegration.model.Candle;
import com.moex.cointegration.model.PriceSeries;
import com.moex.cointegration.storage.MarketDataStorage;
import com.moex.cointegration.universe.SectorCatalog;
import com.moex.cointegration.universe.TierOneCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pre-filter юниверса и пар: ликвидность, цена, preferred, same-sector, баланс ADV ног.
 */
@Service
public class UniverseFilterService {

    private static final Logger log = LoggerFactory.getLogger(UniverseFilterService.class);

    private final MarketDataStorage storage;
    private final ImoexProperties properties;
    private final Map<String, Metrics> metricsCache = new HashMap<>();

    public UniverseFilterService(MarketDataStorage storage, ImoexProperties properties) {
        this.storage = storage;
        this.properties = properties;
    }

    /**
     * Оставляет только тикеры, прошедшие {@code imoex.universe.*} (тикерный уровень).
     */
    public Map<String, PriceSeries> filter(Map<String, PriceSeries> seriesByTicker) throws IOException {
        return filter(seriesByTicker, BookKind.DAILY);
    }

    public Map<String, PriceSeries> filter(Map<String, PriceSeries> seriesByTicker, BookKind book) throws IOException {
        metricsCache.clear();
        ImoexProperties.UniverseProperties u = properties.universe();
        if (!u.enabled() || seriesByTicker == null || seriesByTicker.isEmpty()) {
            return seriesByTicker;
        }

        Map<String, PriceSeries> kept = new LinkedHashMap<>();
        List<String> rejected = new ArrayList<>();
        boolean tierOne = book == BookKind.INTRADAY && u.intradayTierOneOnlyEnabled();

        for (Map.Entry<String, PriceSeries> e : seriesByTicker.entrySet()) {
            String ticker = e.getKey();
            if (tierOne && !TierOneCatalog.isTierOne(ticker)) {
                rejected.add(ticker + " (не 1-й эшелон INTRADAY)");
                continue;
            }
            if (u.researchFocusSectorsOnlyEnabled() && !SectorCatalog.isEquityResearchFocus(ticker)) {
                rejected.add(ticker + " (вне research focus sectors)");
                continue;
            }
            Metrics m = metrics(ticker);
            String reason = rejectReason(ticker, m, u, book);
            if (reason == null) {
                if (u.sameSectorOnlyEnabled() && SectorCatalog.sectorOf(ticker).isEmpty()) {
                    rejected.add(ticker + " (нет сектора в каталоге)");
                    continue;
                }
                kept.put(ticker, e.getValue());
            } else {
                rejected.add(ticker + " (" + reason + ")");
            }
        }

        logFilterResult(book, seriesByTicker.size(), kept.size(), rejected, tierOne);
        return kept;
    }

    /**
     * Фильтр юниверса по as-of срезам свечей (исторический replay без записи в storage).
     */
    public Map<String, PriceSeries> filterFromCandles(
            Map<String, List<Candle>> candlesByTicker,
            BookKind book
    ) {
        metricsCache.clear();
        ImoexProperties.UniverseProperties u = properties.universe();
        if (!u.enabled() || candlesByTicker == null || candlesByTicker.isEmpty()) {
            return toPriceSeriesMap(candlesByTicker);
        }

        Map<String, PriceSeries> kept = new LinkedHashMap<>();
        List<String> rejected = new ArrayList<>();
        boolean tierOne = book == BookKind.INTRADAY && u.intradayTierOneOnlyEnabled();

        for (Map.Entry<String, List<Candle>> e : candlesByTicker.entrySet()) {
            String ticker = e.getKey();
            if (tierOne && !TierOneCatalog.isTierOne(ticker)) {
                rejected.add(ticker + " (не 1-й эшелон INTRADAY)");
                continue;
            }
            if (u.researchFocusSectorsOnlyEnabled() && !SectorCatalog.isEquityResearchFocus(ticker)) {
                rejected.add(ticker + " (вне research focus sectors)");
                continue;
            }
            Metrics m = metricsFromCandles(e.getValue());
            String reason = rejectReason(ticker, m, u, book);
            if (reason == null) {
                if (u.sameSectorOnlyEnabled() && SectorCatalog.sectorOf(ticker).isEmpty()) {
                    rejected.add(ticker + " (нет сектора в каталоге)");
                    continue;
                }
                kept.put(ticker, toPriceSeries(ticker, e.getValue()));
            } else {
                rejected.add(ticker + " (" + reason + ")");
            }
        }

        logFilterResult(book, candlesByTicker.size(), kept.size(), rejected, tierOne);
        return kept;
    }

    /**
     * Загружает свечи из storage для live-скана (текущий as-of).
     */
    public Map<String, List<Candle>> loadCandlesForTickers(Set<String> tickers, BookKind book) throws IOException {
        Map<String, List<Candle>> out = new LinkedHashMap<>();
        for (String ticker : tickers) {
            List<Candle> candles = book == BookKind.INTRADAY
                    ? storage.loadHourlyCandles(ticker)
                    : storage.loadCandles(ticker);
            if (candles != null && !candles.isEmpty()) {
                out.put(ticker, candles);
            }
        }
        return out;
    }

    private void logFilterResult(
            BookKind book,
            int inputSize,
            int keptSize,
            List<String> rejected,
            boolean tierOne
    ) {
        log.info("Universe filter [{}]: {} → {} tickers (rejected {}), tier1={}, sectorsMapped={}",
                book, inputSize, keptSize, rejected.size(), tierOne, SectorCatalog.size());
        if (!rejected.isEmpty()) {
            int show = Math.min(12, rejected.size());
            log.info("Rejected sample: {}", rejected.subList(0, show));
        }
    }

    private Map<String, PriceSeries> toPriceSeriesMap(Map<String, List<Candle>> candlesByTicker) {
        Map<String, PriceSeries> out = new LinkedHashMap<>();
        if (candlesByTicker == null) {
            return out;
        }
        for (Map.Entry<String, List<Candle>> e : candlesByTicker.entrySet()) {
            out.put(e.getKey(), toPriceSeries(e.getKey(), e.getValue()));
        }
        return out;
    }

    private PriceSeries toPriceSeries(String ticker, List<Candle> candles) {
        List<com.moex.cointegration.model.PricePoint> points = candles.stream()
                .map(c -> new com.moex.cointegration.model.PricePoint(c.begin(), c.close()))
                .toList();
        return new PriceSeries(ticker, points);
    }

    /**
     * Gate на уровне пары: same-sector + обе ноги достаточно ликвидны + ADV не слишком разные.
     *
     * @return null если пара ок, иначе причина отказа
     */
    public String pairRejectReason(String tickerY, String tickerX) throws IOException {
        return pairRejectReason(tickerY, tickerX, BookKind.DAILY);
    }

    public String pairRejectReason(String tickerY, String tickerX, BookKind book) throws IOException {
        ImoexProperties.UniverseProperties u = properties.universe();
        if (!u.enabled()) {
            return null;
        }
        if (book == BookKind.INTRADAY && u.intradayTierOneOnlyEnabled() && !TierOneCatalog.pairTierOne(tickerY, tickerX)) {
            return "не 1-й эшелон (INTRADAY whitelist)";
        }
        if (u.sameSectorOnlyEnabled()) {
            boolean ok = u.allowRelatedSectorsEnabled()
                    ? SectorCatalog.sameOrRelatedSector(tickerY, tickerX)
                    : SectorCatalog.sameSector(tickerY, tickerX);
            if (!ok) {
                return "разные сектора / неизвестный сектор";
            }
        }

        Metrics my = metrics(tickerY);
        Metrics mx = metrics(tickerX);
        return pairLiquidityReject(my, mx, u);
    }

    public String pairRejectReason(
            String tickerY,
            String tickerX,
            Map<String, List<Candle>> candlesByTicker,
            BookKind book
    ) {
        ImoexProperties.UniverseProperties u = properties.universe();
        if (!u.enabled()) {
            return null;
        }
        if (book == BookKind.INTRADAY && u.intradayTierOneOnlyEnabled() && !TierOneCatalog.pairTierOne(tickerY, tickerX)) {
            return "не 1-й эшелон (INTRADAY whitelist)";
        }
        if (u.sameSectorOnlyEnabled()) {
            boolean ok = u.allowRelatedSectorsEnabled()
                    ? SectorCatalog.sameOrRelatedSector(tickerY, tickerX)
                    : SectorCatalog.sameSector(tickerY, tickerX);
            if (!ok) {
                return "разные сектора / неизвестный сектор";
            }
        }

        Metrics my = metricsFromCandles(candlesByTicker.get(tickerY));
        Metrics mx = metricsFromCandles(candlesByTicker.get(tickerX));
        return pairLiquidityReject(my, mx, u);
    }

    private String pairLiquidityReject(Metrics my, Metrics mx, ImoexProperties.UniverseProperties u) {
        if (my == null || mx == null) {
            return "нет метрик ликвидности";
        }

        double pairFloor = Math.max(u.minMedianTurnoverRub(), u.minPairTurnoverRub());
        if (my.medianTurnoverRub() < pairFloor || mx.medianTurnoverRub() < pairFloor) {
            return String.format(Locale.ROOT, "ADV пары < %.0f (Y=%.0f X=%.0f)",
                    pairFloor, my.medianTurnoverRub(), mx.medianTurnoverRub());
        }

        double hi = Math.max(my.medianTurnoverRub(), mx.medianTurnoverRub());
        double lo = Math.min(my.medianTurnoverRub(), mx.medianTurnoverRub());
        if (lo <= 0) {
            return "нулевой ADV";
        }
        double ratio = hi / lo;
        if (ratio > u.maxTurnoverRatio()) {
            return String.format(Locale.ROOT, "ADV ratio %.1f > %.1f", ratio, u.maxTurnoverRatio());
        }
        return null;
    }

    public boolean allowPair(String tickerY, String tickerX) throws IOException {
        return allowPair(tickerY, tickerX, BookKind.DAILY);
    }

    public boolean allowPair(String tickerY, String tickerX, BookKind book) throws IOException {
        return pairRejectReason(tickerY, tickerX, book) == null;
    }

    public boolean allowPair(
            String tickerY,
            String tickerX,
            Map<String, List<Candle>> candlesByTicker,
            BookKind book
    ) {
        return pairRejectReason(tickerY, tickerX, candlesByTicker, book) == null;
    }

    String rejectReason(String ticker, Metrics m, ImoexProperties.UniverseProperties u) {
        return rejectReason(ticker, m, u, BookKind.DAILY);
    }

    String rejectReason(String ticker, Metrics m, ImoexProperties.UniverseProperties u, BookKind book) {
        if (book == BookKind.INTRADAY && u.intradayTierOneOnlyEnabled() && !TierOneCatalog.isTierOne(ticker)) {
            return "не 1-й эшелон";
        }
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
        return t.endsWith("P") && t.length() >= 4;
    }

    Metrics metricsFromCandles(List<Candle> candles) {
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

    Metrics metrics(String ticker) throws IOException {
        String key = ticker.toUpperCase(Locale.ROOT);
        if (metricsCache.containsKey(key)) {
            return metricsCache.get(key);
        }
        List<Candle> candles = storage.loadCandles(ticker);
        if (candles == null || candles.isEmpty()) {
            metricsCache.put(key, null);
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
        Metrics m = new Metrics(median, lastClose, zeroFrac);
        metricsCache.put(key, m);
        return m;
    }

    record Metrics(double medianTurnoverRub, double lastClose, double zeroVolumeFraction) {
    }
}
