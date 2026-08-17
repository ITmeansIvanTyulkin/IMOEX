package com.moex.trinity.marketdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.tinkoff.piapi.contract.v1.CandleInterval;
import ru.tinkoff.piapi.contract.v1.Future;
import ru.tinkoff.piapi.contract.v1.GetOrderBookResponse;
import ru.tinkoff.piapi.contract.v1.HistoricCandle;
import ru.tinkoff.piapi.contract.v1.InstrumentStatus;
import ru.tinkoff.piapi.contract.v1.GetFuturesMarginResponse;
import ru.tinkoff.piapi.contract.v1.LastPrice;
import ru.tinkoff.piapi.contract.v1.MoneyValue;
import ru.tinkoff.piapi.contract.v1.Order;
import ru.tinkoff.piapi.contract.v1.Quotation;
import ru.tinkoff.piapi.contract.v1.Trade;
import ru.tinkoff.piapi.contract.v1.TradeDirection;
import ru.tinkoff.piapi.core.InvestApi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unary T-Invest market-data: FIGI resolve, GetLastTrades, GetOrderBook (max depth), candles.
 */
public final class TInvestBrokerMarketData implements AutoCloseable {

    public static final int MAX_ORDERBOOK_DEPTH = 50;
    /** T-Invest accepts only these depths for stream/unary. */
    private static final int[] ALLOWED_DEPTHS = {10, 20, 30, 40, 50};

    /** Docs: GetLastTrades guaranteed for last hour — request in ≤1h chunks. */
    public static final int TRADE_CHUNK_MINUTES = 55;

    private static final Logger log = LoggerFactory.getLogger(TInvestBrokerMarketData.class);
    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final String[] FUTURE_CLASS_CODES = {"SPBFUT", "FUT"};
    private static final long MARGIN_CACHE_MS = 30 * 60_000L;
    private static final long FUTURES_LIST_CACHE_MS = 5 * 60_000L;
    private static final int MARGIN_MONTHS = 4;
    private static final ConcurrentHashMap<String, CachedMargin> MARGIN_CACHE = new ConcurrentHashMap<>();
    private static final Object FUTURES_LIST_LOCK = new Object();
    private static volatile long futuresListAtMs;
    private static volatile List<Future> futuresListCache;

    private final InvestApi api;
    private final boolean sandbox;

    public TInvestBrokerMarketData(TInvestCredentials creds) {
        if (creds == null || !creds.present()) {
            throw new IllegalArgumentException("T-Invest credentials required");
        }
        this.sandbox = creds.sandbox();
        this.api = TInvestApiFactory.create(creds.token(), creds.sandbox());
    }

    public boolean sandbox() {
        return sandbox;
    }

    public InvestApi api() {
        return api;
    }

    /** Resolve FORTS future FIGI by MOEX SECID / ticker (e.g. BRU6). */
    public String resolveFigi(String ticker) {
        Optional<String> override = TInvestCredentials.figiOverride(ticker);
        if (override.isPresent()) {
            return override.get();
        }
        String t = ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
        if (t.isEmpty()) {
            throw new IllegalArgumentException("empty ticker");
        }
        for (String cc : FUTURE_CLASS_CODES) {
            try {
                Future f = api.getInstrumentsService().getFutureByTickerSync(t, cc);
                if (f != null && f.getFigi() != null && !f.getFigi().isBlank()) {
                    return f.getFigi();
                }
            } catch (Exception ex) {
                log.debug("getFutureByTicker {}/{}: {}", t, cc, ex.toString());
            }
        }
        List<Future> all = listedFutures();
        for (Future f : all) {
            if (t.equalsIgnoreCase(f.getTicker()) || t.equalsIgnoreCase(f.getClassCode() + "." + f.getTicker())) {
                return f.getFigi();
            }
        }
        // front BR by open interest / last trade activity among BR*
        Future best = null;
        for (Future f : all) {
            if (f.getTicker() != null && f.getTicker().toUpperCase(Locale.ROOT).startsWith("BR")
                    && f.getTicker().length() <= 5) {
                if (best == null || f.getTicker().compareToIgnoreCase(best.getTicker()) > 0) {
                    best = f;
                }
            }
        }
        if (best != null && t.startsWith("BR")) {
            log.warn("Exact FIGI for {} not found; using {}", t, best.getTicker());
            return best.getFigi();
        }
        // Family-only (BR / RI / NG) → nearest open front-month
        Optional<FrontMonth> fm = resolveFrontMonth(t);
        if (fm.isPresent()) {
            log.info("Resolved family {} → front-month {} ({})", t, fm.get().ticker(), fm.get().figi());
            return fm.get().figi();
        }
        throw new IllegalStateException("FIGI not found for ticker " + t);
    }

    /**
     * Nearest FORTS front-month by last trade date ≥ today (Moscow).
     * Accepts family ({@code BR}/{@code RI}/{@code NG}) or a concrete SECID ({@code BRU6}).
     */
    public Optional<FrontMonth> resolveFrontMonth(String familyOrSecid) {
        String family = normalizeFamily(familyOrSecid);
        if (family == null) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        List<Future> all;
        try {
            all = listedFutures();
        } catch (Exception ex) {
            log.warn("getFutures for front-month {}: {}", family, ex.toString());
            return Optional.empty();
        }
        List<FrontMonth> candidates = new ArrayList<>();
        for (Future f : all) {
            String ticker = f.getTicker();
            if (ticker == null || !matchesFamily(ticker, family)) {
                continue;
            }
            Instant last = f.hasLastTradeDate()
                    ? Instant.ofEpochSecond(f.getLastTradeDate().getSeconds(), f.getLastTradeDate().getNanos())
                    : (f.hasExpirationDate()
                    ? Instant.ofEpochSecond(f.getExpirationDate().getSeconds(), f.getExpirationDate().getNanos())
                    : null);
            if (last == null || last.isBefore(now.minusSeconds(86400))) {
                continue;
            }
            candidates.add(new FrontMonth(
                    ticker,
                    f.getFigi(),
                    LocalDate.ofInstant(last, MSK)
            ));
        }
        candidates.sort(Comparator.comparing(FrontMonth::lastTradeDate));
        return candidates.stream().findFirst();
    }

    /** Convenience: front-month ticker or original if broker unavailable. */
    public String resolveFrontMonthTicker(String familyOrSecid) {
        return resolveFrontMonth(familyOrSecid).map(FrontMonth::ticker).orElse(familyOrSecid);
    }

    /** Shared getFutures snapshot — calendar desk used to call this once per family per poll. */
    private List<Future> listedFutures() {
        long now = System.currentTimeMillis();
        List<Future> hit = futuresListCache;
        if (hit != null && now - futuresListAtMs < FUTURES_LIST_CACHE_MS) {
            return hit;
        }
        synchronized (FUTURES_LIST_LOCK) {
            now = System.currentTimeMillis();
            hit = futuresListCache;
            if (hit != null && now - futuresListAtMs < FUTURES_LIST_CACHE_MS) {
                return hit;
            }
            List<Future> all = api.getInstrumentsService().getFuturesSync(InstrumentStatus.INSTRUMENT_STATUS_BASE);
            List<Future> copy = List.copyOf(all);
            futuresListCache = copy;
            futuresListAtMs = now;
            return copy;
        }
    }

    /**
     * Unexpired FORTS months of a family, nearest last-trade-date first.
     * Source: T-Invest {@code getFutures} — not ISS.
     */
    public List<BrokerListedFuture> listFamilyMonths(String familyOrSecid) {
        String family = normalizeFamily(familyOrSecid);
        if (family == null) {
            return List.of();
        }
        Instant now = Instant.now();
        List<Future> all;
        try {
            all = listedFutures();
        } catch (Exception ex) {
            log.warn("getFutures for calendar {}: {}", family, ex.toString());
            return List.of();
        }
        List<BrokerListedFuture> out = new ArrayList<>();
        for (Future f : all) {
            String ticker = f.getTicker();
            if (ticker == null || !matchesFamily(ticker, family)) {
                continue;
            }
            Instant last = f.hasLastTradeDate()
                    ? Instant.ofEpochSecond(f.getLastTradeDate().getSeconds(), f.getLastTradeDate().getNanos())
                    : (f.hasExpirationDate()
                    ? Instant.ofEpochSecond(f.getExpirationDate().getSeconds(), f.getExpirationDate().getNanos())
                    : null);
            Instant exp = f.hasExpirationDate()
                    ? Instant.ofEpochSecond(f.getExpirationDate().getSeconds(), f.getExpirationDate().getNanos())
                    : last;
            if (last == null || last.isBefore(now.minusSeconds(86400))) {
                continue;
            }
            out.add(new BrokerListedFuture(
                    ticker,
                    f.getFigi(),
                    LocalDate.ofInstant(last, MSK),
                    exp == null ? null : LocalDate.ofInstant(exp, MSK),
                    quotationToDouble(f.getMinPriceIncrement()),
                    quotationToDouble(f.getBasicAssetSize()),
                    0,
                    0
            ));
        }
        out.sort(Comparator.comparing(BrokerListedFuture::lastTradeDate));
        List<BrokerListedFuture> withGo = new ArrayList<>(out.size());
        int n = 0;
        for (BrokerListedFuture m : out) {
            if (n++ < MARGIN_MONTHS) {
                CachedMargin go = futuresMargin(m.figi());
                withGo.add(new BrokerListedFuture(
                        m.ticker(), m.figi(), m.lastTradeDate(), m.expirationDate(),
                        m.minPriceIncrement(), m.basicAssetSize(),
                        go.initialMarginRub(), go.minPriceIncrementAmount()));
            } else {
                withGo.add(m);
            }
        }
        return List.copyOf(withGo);
    }

    /** Near + next month of the family (calendar spread legs). */
    public Optional<CalendarPair> resolveCalendarPair(String familyOrSecid) {
        List<BrokerListedFuture> months = listFamilyMonths(familyOrSecid);
        if (months.size() < 2) {
            return Optional.empty();
        }
        return Optional.of(new CalendarPair(months.get(0), months.get(1)));
    }

    /** Last trade prices from T-Invest unary (not ISS LAST). */
    public Map<String, Double> lastPrices(Collection<String> figis) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (figis == null || figis.isEmpty()) {
            return out;
        }
        List<String> ids = figis.stream().filter(f -> f != null && !f.isBlank()).distinct().toList();
        if (ids.isEmpty()) {
            return out;
        }
        try {
            List<LastPrice> prices = api.getMarketDataService().getLastPricesSync(ids);
            for (LastPrice p : prices) {
                if (p == null || p.getFigi() == null || p.getFigi().isBlank()) {
                    continue;
                }
                double px = quotationToDouble(p.getPrice());
                if (Double.isFinite(px) && px > 0) {
                    out.put(p.getFigi(), px);
                }
            }
        } catch (Exception ex) {
            log.warn("GetLastPrices failed: {}", ex.toString());
        }
        return out;
    }

    private static String normalizeFamily(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        if (u.startsWith("RI") || u.startsWith("RT")) {
            return "RI";
        }
        if (u.startsWith("BR")) {
            return "BR";
        }
        if (u.startsWith("NG")) {
            return "NG";
        }
        return u.length() >= 2 ? u.substring(0, 2) : u;
    }

    private static boolean matchesFamily(String ticker, String family) {
        String t = ticker.toUpperCase(Locale.ROOT);
        if ("RI".equals(family)) {
            return t.startsWith("RI") || t.startsWith("RTS");
        }
        return t.startsWith(family) && t.length() <= 5;
    }

    public DomBook fetchOrderBook(String instrumentId, String figi, int depth) {
        int d = clampDepth(depth);
        GetOrderBookResponse ob = api.getMarketDataService().getOrderBookSync(figi, d);
        List<DomBook.DomLevel> bids = mapOrders(ob.getBidsList());
        List<DomBook.DomLevel> asks = mapOrders(ob.getAsksList());
        Instant asOf = Instant.now();
        return new DomBook(
                instrumentId == null ? figi : instrumentId,
                ob.getDepth() > 0 ? ob.getDepth() : d,
                bids,
                asks,
                asOf,
                true
        );
    }

    /** Snap to broker-allowed depth; default / too high → 50 (API max). */
    public static int clampDepth(int depth) {
        if (depth <= 0 || depth >= MAX_ORDERBOOK_DEPTH) {
            return MAX_ORDERBOOK_DEPTH;
        }
        int best = ALLOWED_DEPTHS[0];
        for (int allowed : ALLOWED_DEPTHS) {
            if (allowed >= depth) {
                return allowed;
            }
            best = allowed;
        }
        return best;
    }

    /**
     * Fetch trades in chunks. Broker docs guarantee ~last hour; older windows may be empty.
     */
    public List<TradePrint> fetchTrades(String instrumentId, String figi, Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            return List.of();
        }
        List<TradePrint> out = new ArrayList<>();
        Instant cursor = from;
        while (cursor.isBefore(to)) {
            Instant end = cursor.plusSeconds(TRADE_CHUNK_MINUTES * 60L);
            if (end.isAfter(to)) {
                end = to;
            }
            try {
                List<Trade> chunk = api.getMarketDataService().getLastTradesSync(figi, cursor, end);
                for (Trade t : chunk) {
                    out.add(toPrint(instrumentId, t));
                }
            } catch (Exception ex) {
                log.warn("GetLastTrades {} {}..{} failed: {}", figi, cursor, end, ex.toString());
            }
            cursor = end;
            try {
                Thread.sleep(120);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        out.sort(Comparator.comparing(TradePrint::time, Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    /** Moscow calendar day session window ≈ 06:50–23:50. */
    public List<TradePrint> fetchTradesForMoscowDay(String instrumentId, String figi, LocalDate day) {
        Instant from = day.atTime(6, 50).atZone(MSK).toInstant();
        Instant to = day.atTime(23, 55).atZone(MSK).toInstant();
        Instant now = Instant.now();
        if (to.isAfter(now)) {
            to = now;
        }
        return fetchTrades(instrumentId, figi, from, to);
    }

    public List<BrokerCandle> fetchM1Candles(String figi, LocalDate fromDay, LocalDate tillDay) {
        return fetchCandles(figi, fromDay, tillDay, CandleInterval.CANDLE_INTERVAL_1_MIN, 1);
    }

    /** Native M5 from broker (1-day chunks — T-Invest 5m window is short). */
    public List<BrokerCandle> fetchM5Candles(String figi, LocalDate fromDay, LocalDate tillDay) {
        return fetchCandles(figi, fromDay, tillDay, CandleInterval.CANDLE_INTERVAL_5_MIN, 1);
    }

    /**
     * Native H1 candles from broker (not aggregated M1/M5).
     * Pulled in ~7-day chunks — enough for positional ~50–90 calendar days.
     */
    public List<BrokerCandle> fetchHourCandles(String figi, LocalDate fromDay, LocalDate tillDay) {
        // HOUR window is up to ~3 months; one chunk covers calendar-arb warmup (~40d).
        return fetchCandles(figi, fromDay, tillDay, CandleInterval.CANDLE_INTERVAL_HOUR, 40);
    }

    private List<BrokerCandle> fetchCandles(
            String figi,
            LocalDate fromDay,
            LocalDate tillDay,
            CandleInterval interval,
            int chunkDays
    ) {
        Instant now = Instant.now();
        List<BrokerCandle> out = new ArrayList<>();
        LocalDate d = fromDay;
        int step = Math.max(1, chunkDays);
        while (!d.isAfter(tillDay)) {
            LocalDate chunkEnd = d.plusDays(step - 1);
            if (chunkEnd.isAfter(tillDay)) {
                chunkEnd = tillDay;
            }
            Instant a = d.atTime(6, 50).atZone(MSK).toInstant();
            Instant b = chunkEnd.atTime(23, 55).atZone(MSK).toInstant();
            if (b.isAfter(now)) {
                b = now;
            }
            if (a.isBefore(b)) {
                try {
                    List<HistoricCandle> candles = api.getMarketDataService()
                            .getCandlesSync(figi, a, b, interval);
                    for (HistoricCandle c : candles) {
                        Instant ts = Instant.ofEpochSecond(c.getTime().getSeconds(), c.getTime().getNanos());
                        LocalDateTime ldt = LocalDateTime.ofInstant(ts, MSK);
                        out.add(new BrokerCandle(
                                ldt,
                                quotationToDouble(c.getOpen()),
                                quotationToDouble(c.getHigh()),
                                quotationToDouble(c.getLow()),
                                quotationToDouble(c.getClose()),
                                c.getVolume()
                        ));
                    }
                } catch (Exception ex) {
                    log.warn("GetCandles {} {}..{} {}: {}", figi, d, chunkEnd, interval, ex.toString());
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            d = chunkEnd.plusDays(1);
        }
        out.sort(Comparator.comparing(BrokerCandle::time));
        return out;
    }

    static TradePrint toPrint(String instrumentId, Trade t) {
        Instant time = t.hasTime()
                ? Instant.ofEpochSecond(t.getTime().getSeconds(), t.getTime().getNanos())
                : Instant.now();
        TradePrint.TradeSide side = switch (t.getDirection()) {
            case TRADE_DIRECTION_BUY -> TradePrint.TradeSide.BUY;
            case TRADE_DIRECTION_SELL -> TradePrint.TradeSide.SELL;
            default -> TradePrint.TradeSide.UNKNOWN;
        };
        return new TradePrint(
                instrumentId == null ? t.getFigi() : instrumentId,
                quotationToDouble(t.getPrice()),
                t.getQuantity(),
                time,
                side
        );
    }

    private static List<DomBook.DomLevel> mapOrders(List<Order> orders) {
        List<DomBook.DomLevel> out = new ArrayList<>(orders.size());
        for (Order o : orders) {
            out.add(new DomBook.DomLevel(quotationToDouble(o.getPrice()), o.getQuantity()));
        }
        return List.copyOf(out);
    }

    /** Initial GO and ₽/tick from T-Invest (cached). Missing → zeros, never ISS. */
    public CachedMargin futuresMargin(String figi) {
        if (figi == null || figi.isBlank()) {
            return CachedMargin.unknown();
        }
        long now = System.currentTimeMillis();
        CachedMargin hit = MARGIN_CACHE.get(figi);
        if (hit != null && now - hit.atMs() < MARGIN_CACHE_MS) {
            return hit;
        }
        try {
            GetFuturesMarginResponse r = api.getInstrumentsService().getFuturesMarginSync(figi);
            double buy = moneyToDouble(r.getInitialMarginOnBuy());
            double sell = moneyToDouble(r.getInitialMarginOnSell());
            double go = Math.max(
                    Double.isFinite(buy) ? buy : 0,
                    Double.isFinite(sell) ? sell : 0);
            double tickRub = quotationToDouble(r.getMinPriceIncrementAmount());
            CachedMargin q = new CachedMargin(now, go, Double.isFinite(tickRub) ? tickRub : 0);
            MARGIN_CACHE.put(figi, q);
            return q;
        } catch (Exception ex) {
            log.debug("getFuturesMargin {} failed: {}", figi, ex.toString());
            CachedMargin miss = new CachedMargin(now - MARGIN_CACHE_MS + 60_000L, 0, 0);
            MARGIN_CACHE.put(figi, miss);
            return CachedMargin.unknown();
        }
    }

    static double moneyToDouble(MoneyValue m) {
        if (m == null) {
            return Double.NaN;
        }
        return BigDecimal.valueOf(m.getUnits())
                .add(BigDecimal.valueOf(m.getNano(), 9))
                .setScale(8, RoundingMode.HALF_UP)
                .doubleValue();
    }

    static double quotationToDouble(Quotation q) {
        if (q == null) {
            return Double.NaN;
        }
        return BigDecimal.valueOf(q.getUnits())
                .add(BigDecimal.valueOf(q.getNano(), 9))
                .setScale(8, RoundingMode.HALF_UP)
                .doubleValue();
    }

    @Override
    public void close() {
        TInvestApiFactory.shutdown(api);
    }

    public record BrokerCandle(
            LocalDateTime time,
            double open,
            double high,
            double low,
            double close,
            long volume
    ) {
    }

    public record FrontMonth(
            String ticker,
            String figi,
            LocalDate lastTradeDate
    ) {
    }

    /** Listed FORTS month from T-Invest instruments (calendar legs). */
    public record CachedMargin(long atMs, double initialMarginRub, double minPriceIncrementAmount) {
        static CachedMargin unknown() {
            return new CachedMargin(0, 0, 0);
        }
    }

    public record BrokerListedFuture(
            String ticker,
            String figi,
            LocalDate lastTradeDate,
            LocalDate expirationDate,
            double minPriceIncrement,
            double basicAssetSize,
            double initialMarginRub,
            double minPriceIncrementAmount
    ) {
    }

    public record CalendarPair(
            BrokerListedFuture near,
            BrokerListedFuture next
    ) {
    }
}
