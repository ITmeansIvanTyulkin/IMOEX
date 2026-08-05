package com.moex.trinity.marketdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.tinkoff.piapi.contract.v1.CandleInterval;
import ru.tinkoff.piapi.contract.v1.Future;
import ru.tinkoff.piapi.contract.v1.GetOrderBookResponse;
import ru.tinkoff.piapi.contract.v1.HistoricCandle;
import ru.tinkoff.piapi.contract.v1.InstrumentStatus;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

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
        List<Future> all = api.getInstrumentsService().getFuturesSync(InstrumentStatus.INSTRUMENT_STATUS_BASE);
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
        throw new IllegalStateException("FIGI not found for ticker " + t);
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
        Instant from = fromDay.atStartOfDay(MSK).toInstant();
        Instant to = tillDay.plusDays(1).atStartOfDay(MSK).toInstant();
        Instant now = Instant.now();
        if (to.isAfter(now)) {
            to = now;
        }
        List<BrokerCandle> out = new ArrayList<>();
        // API typically caps 1m history window — pull day by day
        LocalDate d = fromDay;
        while (!d.isAfter(tillDay)) {
            Instant a = d.atTime(6, 50).atZone(MSK).toInstant();
            Instant b = d.atTime(23, 55).atZone(MSK).toInstant();
            if (b.isAfter(now)) {
                b = now;
            }
            if (a.isBefore(b)) {
                try {
                    List<HistoricCandle> candles = api.getMarketDataService()
                            .getCandlesSync(figi, a, b, CandleInterval.CANDLE_INTERVAL_1_MIN);
                    for (HistoricCandle c : candles) {
                        if (!c.getIsComplete() && d.equals(LocalDate.now(MSK))) {
                            // keep incomplete current bar
                        }
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
                    log.warn("GetCandles {} {}: {}", figi, d, ex.toString());
                }
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            d = d.plusDays(1);
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
        // InvestApi channel lifecycle — GC; no public close on all versions
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
}
