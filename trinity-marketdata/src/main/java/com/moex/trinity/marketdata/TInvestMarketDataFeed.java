package com.moex.trinity.marketdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.tinkoff.piapi.contract.v1.MarketDataResponse;
import ru.tinkoff.piapi.contract.v1.Order;
import ru.tinkoff.piapi.contract.v1.OrderBook;
import ru.tinkoff.piapi.contract.v1.Trade;
import ru.tinkoff.piapi.core.InvestApi;
import ru.tinkoff.piapi.core.stream.MarketDataSubscriptionService;
import ru.tinkoff.piapi.core.stream.StreamProcessor;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Live T-Invest MarketDataStream → order book (max depth) + trades tape + optional disk archive.
 * Transient gRPC EOS / INTERNAL disconnects auto-reconnect with backoff.
 */
public final class TInvestMarketDataFeed implements MarketDataFeed, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TInvestMarketDataFeed.class);

    private final TradeTapeBuffer tape;
    private final int orderbookDepth;
    private final BrokerTapeArchive archive;
    private volatile TapeTickBus tickBus;
    private final AtomicBoolean streaming = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicReference<String> status = new AtomicReference<>("T-Invest feed idle (no token / not started)");
    private final Map<String, String> figiByInstrument = new ConcurrentHashMap<>();
    private final Map<String, DomBook> books = new ConcurrentHashMap<>();
    private final Map<String, String> instrumentByFigi = new ConcurrentHashMap<>();
    private final Map<String, Long> lastDomArchiveMs = new ConcurrentHashMap<>();
    private final Map<String, Integer> lastDomArchiveFp = new ConcurrentHashMap<>();

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    /** Min gap between persisted DOM snapshots. Live book still updates every stream tick.
     * Replay/{@code loadDomDay} keeps ≤1 book/min — denser disk writes only inflate MEGA. */
    private static final long DOM_ARCHIVE_MIN_MS = 60_000;
    private static final long RECONNECT_MAX_DELAY_MS = 60_000L;
    /** gRPC can hang with streaming=true and no onError — reconnect during FORTS hours. */
    private static final long STALE_STREAM_MS = 60_000L;

    private volatile InvestApi api;
    private volatile MarketDataSubscriptionService stream;
    private volatile String tokenHint;
    private volatile String lastToken;
    private volatile boolean lastSandbox;

    private final AtomicInteger reconnectAttempt = new AtomicInteger(0);
    private final AtomicBoolean reconnectPending = new AtomicBoolean(false);
    private final AtomicLong lastEventMs = new AtomicLong(0);
    private final AtomicInteger streamGen = new AtomicInteger(0);
    private final ScheduledExecutorService reconnectExec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tinvest-md-reconnect");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService archiveExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tinvest-md-archive");
        t.setDaemon(true);
        return t;
    });
    private volatile ScheduledFuture<?> reconnectFuture;

    public TInvestMarketDataFeed(int tapeCapacity, int orderbookDepth) {
        this(tapeCapacity, orderbookDepth, new BrokerTapeArchive(java.nio.file.Path.of("data", "broker-tape")));
    }

    public TInvestMarketDataFeed(int tapeCapacity, int orderbookDepth, BrokerTapeArchive archive) {
        this.tape = new TradeTapeBuffer(tapeCapacity);
        this.orderbookDepth = TInvestBrokerMarketData.clampDepth(orderbookDepth);
        this.archive = archive;
        reconnectExec.scheduleWithFixedDelay(this::watchStaleStream, 20, 15, TimeUnit.SECONDS);
    }

    public static TInvestMarketDataFeed unconfigured() {
        return new TInvestMarketDataFeed(50_000, TInvestBrokerMarketData.MAX_ORDERBOOK_DEPTH);
    }

    public int orderbookDepth() {
        return orderbookDepth;
    }

    /** Append-only DOM/tape archive used for hist replay (may be empty until stream runs). */
    public BrokerTapeArchive archive() {
        return archive;
    }

    public void setTickBus(TapeTickBus tickBus) {
        this.tickBus = tickBus;
    }

    /**
     * Open MarketDataStream for the given FIGIs (keys = operator instrument ids e.g. BRU6).
     */
    public synchronized void start(String token, boolean sandbox, Map<String, String> instrumentToFigi) {
        if (closed.get()) {
            return;
        }
        final int gen = streamGen.incrementAndGet();
        stopStreamOnly();
        if (token == null || token.isBlank()) {
            status.set("T-Invest feed: token missing — preview only");
            streaming.set(false);
            return;
        }
        if (instrumentToFigi == null || instrumentToFigi.isEmpty()) {
            status.set("T-Invest feed: no FIGI map");
            streaming.set(false);
            return;
        }
        if (archive != null) {
            archive.compressClosedDays();
        }
        this.lastToken = token;
        this.lastSandbox = sandbox;
        this.tokenHint = token.substring(0, Math.min(4, token.length())) + "…";
        this.api = TInvestApiFactory.create(token, sandbox);
        figiByInstrument.clear();
        instrumentByFigi.clear();
        figiByInstrument.putAll(instrumentToFigi);
        instrumentToFigi.forEach((inst, figi) -> instrumentByFigi.put(figi, inst));

        StreamProcessor<MarketDataResponse> processor = response -> {
            if (gen != streamGen.get()) {
                return;
            }
            try {
                onResponse(response);
            } catch (Exception ex) {
                log.debug("marketdata process error: {}", ex.toString());
            }
        };
        stream = api.getMarketDataStreamService().newStream(
                "trinity-md",
                processor,
                err -> onStreamError(gen, err)
        );
        List<String> figis = new ArrayList<>(instrumentToFigi.values());
        stream.subscribeTrades(figis);
        stream.subscribeOrderbook(figis, orderbookDepth);
        for (Map.Entry<String, String> e : instrumentToFigi.entrySet()) {
            try {
                var ob = api.getMarketDataService().getOrderBookSync(e.getValue(), orderbookDepth);
                Instant asOf = Instant.now();
                DomBook seeded = new DomBook(
                        e.getKey(),
                        ob.getDepth() > 0 ? ob.getDepth() : orderbookDepth,
                        mapOrders(ob.getBidsList()),
                        mapOrders(ob.getAsksList()),
                        asOf,
                        true
                );
                storeBook(e.getKey(), seeded);
                // Do not archive seed books: every reconnect would append full depth-50 rows.
                // Live continuum uses maybeArchiveDom on stream ticks.
            } catch (Exception ex) {
                log.debug("seed orderbook {}: {}", e.getKey(), ex.toString());
            }
        }
        streaming.set(true);
        reconnectAttempt.set(0);
        lastEventMs.set(System.currentTimeMillis());
        status.set("T-Invest MarketDataStream live (sandbox=" + sandbox + ", depth=" + orderbookDepth
                + ", instruments=" + figis.size() + ", token=" + tokenHint + ")");
        log.info("{}", status.get());
    }

    private void onStreamError(int gen, Throwable err) {
        if (closed.get() || gen != streamGen.get()) {
            log.debug("ignore stale T-Invest stream error gen={} current={}: {}",
                    gen, streamGen.get(), err == null ? "?" : err.toString());
            return;
        }
        streaming.set(false);
        String msg = err == null ? "unknown" : err.getMessage();
        status.set("T-Invest stream error: " + msg);
        if (isTransientStreamDrop(err)) {
            log.info("T-Invest marketdata stream dropped (will reconnect): {}", err);
            scheduleReconnect();
        } else {
            log.warn("T-Invest marketdata stream failed (no auto-reconnect): {}", err == null ? "?" : err.toString());
        }
    }

    static boolean isTransientStreamDrop(Throwable err) {
        if (err == null) {
            return false;
        }
        if (err instanceof io.grpc.StatusRuntimeException sre) {
            io.grpc.Status.Code code = sre.getStatus().getCode();
            if (code == io.grpc.Status.Code.UNAUTHENTICATED
                    || code == io.grpc.Status.Code.PERMISSION_DENIED
                    || code == io.grpc.Status.Code.INVALID_ARGUMENT) {
                return false;
            }
            return true;
        }
        String s = err.toString();
        if (s.contains("UNAUTHENTICATED") || s.contains("PERMISSION_DENIED")) {
            return false;
        }
        return s.contains("EOS")
                || s.contains("UNAVAILABLE")
                || s.contains("INTERNAL")
                || s.contains("Broken pipe")
                || s.contains("Connection reset")
                || s.contains("GOAWAY")
                || s.contains("Http2")
                || s.contains("CANCELLED")
                || s.contains("DEADLINE")
                || s.contains("RST_STREAM")
                || s.contains("closed");
    }

    /** FORTS main + evening (MSK), weekdays. Silent stream death is only worth a reconnect then. */
    static boolean sessionOpenMsk(LocalDateTime now) {
        if (now == null) {
            return false;
        }
        DayOfWeek d = now.getDayOfWeek();
        if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) {
            return false;
        }
        int hm = now.getHour() * 60 + now.getMinute();
        return hm >= 9 * 60 && hm < 23 * 60 + 50;
    }

    private void watchStaleStream() {
        if (closed.get() || !streaming.get()) {
            return;
        }
        if (!sessionOpenMsk(LocalDateTime.now(MSK))) {
            return;
        }
        long last = lastEventMs.get();
        if (last <= 0) {
            return;
        }
        long silent = System.currentTimeMillis() - last;
        if (silent < STALE_STREAM_MS) {
            return;
        }
        log.warn("T-Invest stream silent {}ms during FORTS session — reconnect", silent);
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (closed.get() || lastToken == null || lastToken.isBlank() || figiByInstrument.isEmpty()) {
            return;
        }
        if (!reconnectPending.compareAndSet(false, true)) {
            return;
        }
        int attempt = Math.max(1, reconnectAttempt.incrementAndGet());
        long delay = Math.min(RECONNECT_MAX_DELAY_MS, 1_000L * (1L << Math.min(5, attempt - 1)));
        status.set("T-Invest stream reconnect in " + delay + "ms (attempt " + attempt + ")");
        log.info("{}", status.get());
        reconnectFuture = reconnectExec.schedule(() -> {
            reconnectPending.set(false);
            if (closed.get()) {
                return;
            }
            try {
                Map<String, String> map = new LinkedHashMap<>(figiByInstrument);
                String token = lastToken;
                boolean sandbox = lastSandbox;
                if (token == null || map.isEmpty()) {
                    return;
                }
                start(token, sandbox, map);
            } catch (Exception ex) {
                log.warn("T-Invest reconnect failed: {}", ex.toString());
                scheduleReconnect();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    public synchronized void startAuto(String instrumentId) {
        TInvestCredentials creds = TInvestCredentials.resolve();
        if (!creds.present()) {
            status.set("T-Invest feed: no token in env / data/broker-ui-settings.json");
            return;
        }
        try (TInvestBrokerMarketData md = new TInvestBrokerMarketData(creds)) {
            String figi = md.resolveFigi(instrumentId);
            start(creds.token(), creds.sandbox(), Map.of(instrumentId, figi));
            DomBook book = md.fetchOrderBook(instrumentId, figi, orderbookDepth);
            storeBook(instrumentId, book);
        }
    }

    private void onResponse(MarketDataResponse response) {
        lastEventMs.set(System.currentTimeMillis());
        if (response.hasTrade()) {
            Trade t = response.getTrade();
            String inst = instrumentByFigi.getOrDefault(t.getFigi(), t.getFigi());
            TradePrint print = TInvestBrokerMarketData.toPrint(inst, t);
            tape.add(print);
            TapeTickBus bus = tickBus;
            if (bus != null) {
                bus.publish(print);
            }
            if (archive != null) {
                archiveExec.execute(() -> {
                    try {
                        archive.append(print);
                    } catch (Exception ignored) {
                        // best-effort disk
                    }
                });
            }
        }
        if (response.hasOrderbook()) {
            OrderBook ob = response.getOrderbook();
            String inst = instrumentByFigi.getOrDefault(ob.getFigi(), ob.getFigi());
            Instant asOf = ob.hasTime()
                    ? Instant.ofEpochSecond(ob.getTime().getSeconds(), ob.getTime().getNanos())
                    : Instant.now();
            DomBook book = new DomBook(
                    inst,
                    ob.getDepth() > 0 ? ob.getDepth() : orderbookDepth,
                    mapOrders(ob.getBidsList()),
                    mapOrders(ob.getAsksList()),
                    asOf,
                    ob.getIsConsistent()
            );
            storeBook(inst, book);
            maybeArchiveDom(inst, book);
        }
    }

    private void maybeArchiveDom(String instrumentId, DomBook book) {
        if (archive == null || book == null || instrumentId == null || instrumentId.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        String key = instrumentId.trim().toUpperCase(Locale.ROOT);
        Long prev = lastDomArchiveMs.get(key);
        if (prev != null && now - prev < DOM_ARCHIVE_MIN_MS) {
            return;
        }
        int fp = domArchiveFingerprint(book);
        Integer prevFp = lastDomArchiveFp.get(key);
        if (prevFp != null && prevFp == fp && prev != null) {
            // Unchanged ladder — bump timer so quiet books don't rewrite identical rows.
            lastDomArchiveMs.put(key, now);
            return;
        }
        lastDomArchiveMs.put(key, now);
        lastDomArchiveFp.put(key, fp);
        archiveExec.execute(() -> {
            try {
                archive.appendDom(book);
            } catch (Exception ignored) {
                // best-effort disk
            }
        });
    }

    /** Stable hash of depth + bid/ask ladders — skip identical DOM rows on disk. */
    static int domArchiveFingerprint(DomBook book) {
        if (book == null) {
            return 0;
        }
        int h = 17;
        h = 31 * h + book.depth();
        h = 31 * h + Boolean.hashCode(book.consistent());
        h = 31 * h + levelsFingerprint(book.bids());
        h = 31 * h + levelsFingerprint(book.asks());
        return h;
    }

    private static int levelsFingerprint(List<DomBook.DomLevel> levels) {
        if (levels == null || levels.isEmpty()) {
            return 0;
        }
        int h = 1;
        for (DomBook.DomLevel l : levels) {
            if (l == null) {
                continue;
            }
            h = 31 * h + Double.hashCode(l.price());
            h = 31 * h + Long.hashCode(l.quantityLots());
        }
        return h;
    }

    private static List<DomBook.DomLevel> mapOrders(List<Order> orders) {
        List<DomBook.DomLevel> out = new ArrayList<>(orders.size());
        for (Order o : orders) {
            out.add(new DomBook.DomLevel(TInvestBrokerMarketData.quotationToDouble(o.getPrice()), o.getQuantity()));
        }
        return List.copyOf(out);
    }

    public synchronized void addInstruments(Map<String, String> extra) {
        if (extra == null || extra.isEmpty()) {
            return;
        }
        List<String> newFigis = new ArrayList<>();
        for (Map.Entry<String, String> e : extra.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null || e.getValue().isBlank()) {
                continue;
            }
            String inst = e.getKey().trim().toUpperCase(Locale.ROOT);
            String figi = e.getValue().trim();
            String prev = figiByInstrument.put(inst, figi);
            instrumentByFigi.put(figi, inst);
            if (prev == null || !prev.equals(figi)) {
                newFigis.add(figi);
            }
        }
        if (stream == null || newFigis.isEmpty()) {
            return;
        }
        try {
            stream.subscribeTrades(newFigis);
            stream.subscribeOrderbook(newFigis, orderbookDepth);
            status.set(status.get() + " | +legs=" + newFigis.size());
        } catch (Exception ex) {
            log.warn("addInstruments subscribe failed: {}", ex.toString());
        }
    }

    private synchronized void stopStreamOnly() {
        if (stream != null) {
            try {
                stream.cancel();
            } catch (Exception ignored) {
                // ignore
            }
            stream = null;
        }
        InvestApi dying = api;
        api = null;
        TInvestApiFactory.shutdown(dying);
        streaming.set(false);
    }

    public synchronized void stop() {
        cancelReconnect();
        stopStreamOnly();
    }

    private void cancelReconnect() {
        ScheduledFuture<?> f = reconnectFuture;
        reconnectFuture = null;
        if (f != null) {
            f.cancel(false);
        }
        reconnectPending.set(false);
    }

    @Override
    public void close() {
        closed.set(true);
        stop();
        reconnectExec.shutdownNow();
        archiveExec.shutdownNow();
    }

    @Override
    public MarketDataProviderId providerId() {
        return MarketDataProviderId.T_INVEST;
    }

    public int tapeSize() {
        return tape.size();
    }

    public Optional<DomBook> anyBook() {
        return snapshotBooks().stream().filter(b -> b != null && !b.emptyLevels()).findFirst()
                .or(() -> books.values().stream().findFirst());
    }

    /** Tickers currently mapped on the stream (uppercase). */
    public java.util.Set<String> subscribedTickers() {
        return java.util.Set.copyOf(figiByInstrument.keySet());
    }

    @Override
    public List<DomBook> snapshotBooks() {
        return List.copyOf(books.values());
    }

    @Override
    public String statusMessage() {
        return status.get() + " | tape=" + tape.size() + " | depth=" + orderbookDepth;
    }

    @Override
    public boolean streaming() {
        return streaming.get();
    }

    public void putBook(DomBook book) {
        if (book == null || book.instrumentId() == null || book.instrumentId().isBlank()) {
            return;
        }
        storeBook(book.instrumentId(), book);
    }

    private void storeBook(String instrumentId, DomBook book) {
        if (book == null || instrumentId == null || instrumentId.isBlank()) {
            return;
        }
        String key = instrumentId.trim().toUpperCase(Locale.ROOT);
        DomBook existing = books.get(key);
        if (book.emptyLevels() && existing != null && !existing.emptyLevels()) {
            return;
        }
        books.put(key, book);
        TapeTickBus bus = tickBus;
        if (bus != null) {
            bus.publishBook(book);
        }
    }

    @Override
    public Optional<DomBook> latestBook(String instrumentId) {
        if (instrumentId == null) {
            return Optional.empty();
        }
        String key = instrumentId.trim().toUpperCase(Locale.ROOT);
        DomBook b = books.get(key);
        if (b != null) {
            return Optional.of(b);
        }
        b = books.get(instrumentId);
        if (b != null) {
            return Optional.of(b);
        }
        for (var e : books.entrySet()) {
            if (e.getKey().equalsIgnoreCase(instrumentId)) {
                return Optional.of(e.getValue());
            }
        }
        return Optional.empty();
    }

    @Override
    public List<TradePrint> recentTrades(String instrumentId) {
        return tape.snapshotWindow(instrumentId, null, null);
    }

    @Override
    public Optional<TradePrint> lastTrade(String instrumentId) {
        return Optional.ofNullable(tape.last(instrumentId));
    }

    @Override
    public List<TradePrint> recentTradesWindow(String instrumentId, Instant from, Instant to) {
        return tape.snapshotWindow(instrumentId, from, to);
    }

    @Override
    public void subscribeTrades(String instrumentId) {
        status.set(status.get() + " | subscribeTrades(" + instrumentId + ") noted");
    }

    @Override
    public void subscribeBook(String instrumentId, int depth) {
        status.set(status.get() + " | subscribeBook(" + instrumentId + "," + depth + ") noted");
    }
}
