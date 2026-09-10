package com.moex.trinity.marketdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Facade for marketplace market-data contour.
 */
public class MarketDataResearchService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataResearchService.class);
    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    /** When stream is down or book older than this, pull unary GetOrderBook from broker. */
    private static final long BOOK_STALE_MS = 20_000L;
    /** Min gap between unary book refreshes per instrument (desk + DOM poll). */
    private static final long BOOK_REST_MIN_GAP_MS = 5_000L;
    /** After REST book failures, skip unary for a while (T-Invest "unknown error" storms). */
    private static final long BOOK_REST_COOLDOWN_MS = 45_000L;
    /** Reuse resolved live front-month (desk polls the book every few seconds). */
    private static final long LIVE_FRONT_MS = 60_000L;
    private static final long ENSURE_MIN_GAP_MS = 30_000L;

    private final MarketDataFeed feed;
    private final BrokerTapeArchive archive;
    private volatile String defaultInstrument;
    private final ConcurrentHashMap<String, Long> lastBookRestMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CachedFront> liveFrontCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastEnsureMs = new ConcurrentHashMap<>();
    private final AtomicLong bookRestCooldownUntilMs = new AtomicLong(0);
    private final ConcurrentHashMap<String, Boolean> bookRefreshQueued = new ConcurrentHashMap<>();
    private final ExecutorService bookRefreshExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "md-book-rest");
        t.setDaemon(true);
        return t;
    });

    private record CachedFront(String ticker, long atMs) {
    }

    public MarketDataResearchService(MarketDataFeed feed) {
        this(feed, new BrokerTapeArchive(Path.of("data", "broker-tape")), "BRU6");
    }

    public MarketDataResearchService(MarketDataFeed feed, BrokerTapeArchive archive, String defaultInstrument) {
        this.feed = feed;
        this.archive = archive == null ? new BrokerTapeArchive(Path.of("data", "broker-tape")) : archive;
        this.defaultInstrument = defaultInstrument == null || defaultInstrument.isBlank()
                ? "BRU6" : defaultInstrument.trim().toUpperCase(Locale.ROOT);
    }

    public MarketDataFeed feed() {
        return feed;
    }

    public BrokerTapeArchive archive() {
        return archive;
    }

    public String defaultInstrument() {
        return defaultInstrument;
    }

    public void setDefaultInstrument(String instrumentId) {
        if (instrumentId == null || instrumentId.isBlank()) {
            return;
        }
        this.defaultInstrument = instrumentId.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Cached live front only — never opens T-Invest. Desk HTTP uses this.
     */
    public String peekLiveFrontTicker(String familyOrSecid) {
        String hint = familyOrSecid == null || familyOrSecid.isBlank()
                ? defaultInstrument : familyOrSecid.trim();
        String fam = TInvestBrokerMarketData.familyOf(hint);
        String cacheKey = (fam == null ? hint : fam).toUpperCase(Locale.ROOT);
        CachedFront hit = liveFrontCache.get(cacheKey);
        return hit == null ? null : hit.ticker();
    }

    /**
     * Live FORTS month for this family (cached). Empty DOM on an expired front
     * rolls to the next month that still has a book.
     */
    public String liveFrontTicker(String familyOrSecid) {
        String hint = familyOrSecid == null || familyOrSecid.isBlank()
                ? defaultInstrument : familyOrSecid.trim();
        String fam = TInvestBrokerMarketData.familyOf(hint);
        String cacheKey = (fam == null ? hint : fam).toUpperCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        CachedFront hit = liveFrontCache.get(cacheKey);
        if (hit != null && now - hit.atMs() < LIVE_FRONT_MS && hit.ticker() != null) {
            return hit.ticker();
        }
        return ensureLiveFront(hint).orElse(hint);
    }

    /**
     * Subscribe the stream to the liquid front-month and remember it as default
     * when the family matches. Safe to call on a schedule after expiry.
     */
    public synchronized Optional<String> ensureLiveFront(String familyOrSecid) {
        String hint = familyOrSecid == null || familyOrSecid.isBlank()
                ? defaultInstrument : familyOrSecid.trim();
        String fam = TInvestBrokerMarketData.familyOf(hint);
        String cacheKey = (fam == null ? hint : fam).toUpperCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        Long prevEnsure = lastEnsureMs.get(cacheKey);
        CachedFront cached = liveFrontCache.get(cacheKey);
        if (prevEnsure != null && now - prevEnsure < ENSURE_MIN_GAP_MS
                && cached != null && cached.ticker() != null) {
            return Optional.of(cached.ticker());
        }
        lastEnsureMs.put(cacheKey, now);

        Optional<TInvestBrokerMarketData.FrontMonth> picked = Optional.empty();
        TInvestCredentials creds = TInvestCredentials.resolve();
        if (creds.present() && feed instanceof TInvestMarketDataFeed) {
            try (TInvestBrokerMarketData md = new TInvestBrokerMarketData(creds)) {
                List<TInvestBrokerMarketData.FrontMonth> months = md.listFrontMonths(hint);
                // Calendar front wins. Do NOT advance on empty DOM / REST — that falsely
                // rolled BRV6→BRX6 and painted November shelves on the October desk.
                picked = TInvestBrokerMarketData.pickLiveMonth(months, this::hasLiveDom);
                if (picked.isPresent() && !hasLiveDom(picked.get().ticker())) {
                    Optional<DomBook> rest = refreshBookRest(picked.get().ticker());
                    if (rest.isEmpty() || isEmpty(rest.get())) {
                        log.warn("front-month {} has empty DOM/REST — keeping calendar front (no skip to next)",
                                picked.get().ticker());
                    }
                }
            } catch (Exception ex) {
                log.debug("ensureLiveFront {}: {}", hint, ex.toString());
            }
        }
        if (picked.isEmpty()) {
            // Only family aliases may borrow another month's live book. Concrete BRV6 must not
            // become BRX6 when getFutures fails but November DOM is streaming (2026-09-10).
            if (!looksLikeConcreteSecid(hint)) {
                picked = sameFamilyLiveBook(hint)
                        .map(b -> new TInvestBrokerMarketData.FrontMonth(b.instrumentId(), "", LocalDate.now(MSK)));
            }
        }
        if (picked.isEmpty()) {
            liveFrontCache.put(cacheKey, new CachedFront(hint.toUpperCase(Locale.ROOT), now));
            return Optional.of(hint.toUpperCase(Locale.ROOT));
        }

        TInvestBrokerMarketData.FrontMonth live = picked.get();
        String ticker = live.ticker().trim().toUpperCase(Locale.ROOT);
        if (feed instanceof TInvestMarketDataFeed t && live.figi() != null && !live.figi().isBlank()) {
            Map<String, String> extra = new LinkedHashMap<>();
            extra.put(ticker, live.figi());
            t.addInstruments(extra);
        }
        String defaultFam = TInvestBrokerMarketData.familyOf(defaultInstrument);
        if (fam != null && fam.equalsIgnoreCase(defaultFam) && !ticker.equalsIgnoreCase(defaultInstrument)) {
            log.info("DOM front-month {} → {} (family {})", defaultInstrument, ticker, fam);
            setDefaultInstrument(ticker);
        } else if (defaultFam == null || defaultFam.equalsIgnoreCase(fam)) {
            setDefaultInstrument(ticker);
        }
        liveFrontCache.put(cacheKey, new CachedFront(ticker, now));
        return Optional.of(ticker);
    }

    private boolean hasLiveDom(String ticker) {
        return feed.latestBook(ticker).filter(b -> !isEmpty(b)).isPresent();
    }

    public String statusMessage() {
        return feed.statusMessage();
    }

    public boolean liveReady() {
        return feed.streaming();
    }

    /**
     * Live book if streaming; unary REST refresh when stale; else archive tail.
     * Empty snapshots (expired month) fall through to the same-family live book.
     */
    public Optional<DomBook> resolveBook(String instrumentId) {
        return resolveBook(instrumentId, true);
    }

    /** Stream + DOM archive. Desk HTTP must not wait on T-Invest REST. */
    public Optional<DomBook> resolveBookLocal(String instrumentId) {
        return resolveBook(instrumentId, false);
    }

    /**
     * Operator DOM poll: return stream/archive immediately; refresh unary REST in background
     * when stale. Never blocks the HTTP thread on GetOrderBook.
     */
    public Optional<DomBook> resolveBookForHttp(String instrumentId) {
        Optional<DomBook> local = resolveBookLocal(instrumentId);
        if (local.isEmpty() || needsRestRefresh(local.orElse(null))) {
            requestBookRefreshAsync(instrumentId);
        }
        return local;
    }

    /** Fire-and-forget unary GetOrderBook (deduped per instrument). */
    public void requestBookRefreshAsync(String instrumentId) {
        String id = instrumentId == null || instrumentId.isBlank() ? defaultInstrument : instrumentId.trim();
        String key = id.toUpperCase(Locale.ROOT);
        if (System.currentTimeMillis() < bookRestCooldownUntilMs.get()) {
            return;
        }
        if (bookRefreshQueued.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        bookRefreshExec.execute(() -> {
            try {
                refreshBookRest(id);
            } finally {
                bookRefreshQueued.remove(key);
            }
        });
    }

    private Optional<DomBook> resolveBook(String instrumentId, boolean allowRest) {
        String id = instrumentId == null || instrumentId.isBlank() ? defaultInstrument : instrumentId.trim();
        // Family alias (BR) may resolve to live front. Concrete SECID (BRV6) must keep its own book —
        // remapping to a peeked next month showed the wrong DOM on the desk (2026-09-10).
        if (!looksLikeConcreteSecid(id)) {
            String front = allowRest ? liveFrontTicker(id) : peekLiveFrontTicker(id);
            if (front != null && !front.isBlank()) {
                id = front;
            }
        }
        DomBook book = feed.latestBook(id).orElse(null);
        boolean concrete = looksLikeConcreteSecid(id);
        if (isEmpty(book) && !concrete) {
            book = sameFamilyLiveBook(id).orElse(book);
        }
        if (allowRest && needsRestRefresh(book)) {
            Optional<DomBook> refreshed = refreshBookRest(id);
            if (refreshed.isPresent() && !isEmpty(refreshed.get())) {
                book = refreshed.get();
            } else if (isEmpty(book) && !concrete) {
                book = sameFamilyLiveBook(id).orElse(book);
            }
        }
        if (!isEmpty(book)) {
            return Optional.of(book);
        }
        if (book != null && !concrete) {
            Optional<DomBook> familyBook = sameFamilyLiveBook(id);
            if (familyBook.isPresent()) {
                return familyBook;
            }
        }
        try {
            List<DomBook> day = archive.loadDomDay(id, LocalDate.now(MSK));
            if (day.isEmpty()) {
                day = archive.loadDomDay(id, LocalDate.now(MSK).minusDays(1));
            }
            if (!day.isEmpty()) {
                DomBook archived = day.get(day.size() - 1);
                if (!isEmpty(archived)) {
                    if (allowRest && needsRestRefresh(archived)) {
                        Optional<DomBook> refreshed = refreshBookRest(id);
                        if (refreshed.isPresent() && !isEmpty(refreshed.get())) {
                            return refreshed;
                        }
                    }
                    return Optional.of(archived);
                }
            }
        } catch (Exception ignored) {
            // empty
        }
        return allowRest ? refreshBookRest(id) : Optional.empty();
    }

    /** Recent DOM snapshots for spoof/pull hunt (minute-sampled archive). */
    public List<DomBook> recentDom(String instrumentId, int max) {
        int cap = Math.max(2, Math.min(max <= 0 ? 12 : max, 40));
        String id = instrumentId == null || instrumentId.isBlank() ? defaultInstrument : instrumentId.trim();
        // Concrete SECID keeps its own DOM history — do not remap to peeked next month.
        if (!looksLikeConcreteSecid(id)) {
            String front = peekLiveFrontTicker(id);
            if (front != null && !front.isBlank()) {
                id = front;
            }
        }
        try {
            List<DomBook> day = archive.loadDomDay(id, LocalDate.now(MSK));
            if (day.isEmpty()) {
                day = archive.loadDomDay(id, LocalDate.now(MSK).minusDays(1));
            }
            if (day.size() > cap) {
                return List.copyOf(day.subList(day.size() - cap, day.size()));
            }
            return day == null ? List.of() : List.copyOf(day);
        } catch (Exception ex) {
            log.debug("recentDom {}: {}", id, ex.toString());
            return List.of();
        }
    }

    /** Last trade price from broker unary (works when MarketDataStream is down). */
    public Optional<Double> liveLastPrice(String instrumentId) {
        String id = instrumentId == null || instrumentId.isBlank() ? defaultInstrument : instrumentId.trim();
        TInvestCredentials creds = TInvestCredentials.resolve();
        if (!creds.present()) {
            return Optional.empty();
        }
        try (TInvestBrokerMarketData md = new TInvestBrokerMarketData(creds)) {
            String figi = md.resolveFigi(id);
            Map<String, Double> px = md.lastPrices(List.of(figi));
            Double v = px.get(figi);
            if (v != null && Double.isFinite(v) && v > 0) {
                return Optional.of(v);
            }
        } catch (Exception ignored) {
            // empty
        }
        return Optional.empty();
    }

    private boolean needsRestRefresh(DomBook book) {
        if (!feed.streaming()) {
            return true;
        }
        if (book == null) {
            return true;
        }
        if (book.asOf() == null) {
            return true;
        }
        if (isEmpty(book)) {
            return true;
        }
        return Instant.now().toEpochMilli() - book.asOf().toEpochMilli() > BOOK_STALE_MS;
    }

    private static boolean isEmpty(DomBook book) {
        return book == null || book.emptyLevels();
    }

    /** Concrete FORTS ticker (BRV6), not family alias (BR). */
    static boolean looksLikeConcreteSecid(String instrumentId) {
        if (instrumentId == null || instrumentId.isBlank()) {
            return false;
        }
        String u = instrumentId.trim().toUpperCase(Locale.ROOT);
        return u.length() >= 4 && u.length() <= 6 && Character.isDigit(u.charAt(u.length() - 1));
    }

    private Optional<DomBook> sameFamilyLiveBook(String instrumentId) {
        String family = TInvestBrokerMarketData.familyOf(instrumentId);
        if (family == null || family.isBlank()) {
            return Optional.empty();
        }
        DomBook exact = null;
        DomBook newest = null;
        for (DomBook b : feed.snapshotBooks()) {
            if (isEmpty(b) || b.instrumentId() == null) {
                continue;
            }
            String fam = TInvestBrokerMarketData.familyOf(b.instrumentId());
            if (!family.equalsIgnoreCase(fam)) {
                continue;
            }
            if (b.instrumentId().equalsIgnoreCase(instrumentId)) {
                exact = b;
            }
            if (newest == null
                    || (b.asOf() != null && (newest.asOf() == null || b.asOf().isAfter(newest.asOf())))) {
                newest = b;
            }
        }
        return Optional.ofNullable(exact != null ? exact : newest);
    }

    private Optional<DomBook> refreshBookRest(String instrumentId) {
        long now = System.currentTimeMillis();
        if (now < bookRestCooldownUntilMs.get()) {
            return Optional.empty();
        }
        String key = instrumentId == null ? "" : instrumentId.trim().toUpperCase();
        Long prev = lastBookRestMs.get(key);
        if (prev != null && now - prev < BOOK_REST_MIN_GAP_MS) {
            return Optional.empty();
        }
        // Claim the gap even before the call so concurrent HTTP polls don't stampede.
        lastBookRestMs.put(key, now);
        TInvestCredentials creds = TInvestCredentials.resolve();
        if (!creds.present()) {
            return Optional.empty();
        }
        try (TInvestBrokerMarketData md = new TInvestBrokerMarketData(creds)) {
            String figi = md.resolveFigi(instrumentId);
            int depth = feed instanceof TInvestMarketDataFeed t ? t.orderbookDepth() : 50;
            DomBook book = md.fetchOrderBook(instrumentId, figi, depth);
            bookRestCooldownUntilMs.set(0);
            if (feed instanceof TInvestMarketDataFeed t) {
                t.putBook(book);
            }
            return Optional.of(book);
        } catch (Exception ex) {
            bookRestCooldownUntilMs.set(System.currentTimeMillis() + BOOK_REST_COOLDOWN_MS);
            log.debug("refreshBookRest {}: {}", key, ex.toString());
            return Optional.empty();
        }
    }

    public Status status() {
        LocalDate today = LocalDate.now(MSK);
        String instrument = defaultInstrument;
        int liveTape = 0;
        int depth = 0;
        int bidLevels = 0;
        int askLevels = 0;
        if (feed instanceof TInvestMarketDataFeed t) {
            liveTape = t.tapeSize();
            depth = t.orderbookDepth();
            Optional<DomBook> book = t.latestBook(instrument).filter(b -> !b.emptyLevels())
                    .or(() -> t.anyBook());
            if (book.isPresent()) {
                bidLevels = book.get().bids().size();
                askLevels = book.get().asks().size();
                if (book.get().depth() > 0) {
                    depth = book.get().depth();
                }
            }
        }
        long archivedTape = archive.tapeLines(instrument, today);
        long archivedDom = archive.domLines(instrument, today);
        boolean streaming = feed.streaming();
        String summary;
        if (streaming && (liveTape > 0 || archivedTape > 0)) {
            summary = "Лента live · " + instrument + " · tape≈" + Math.max(liveTape, (int) archivedTape)
                    + " · DOM depth " + depth;
        } else if (streaming) {
            summary = "Стрим подключён, ждём prints…";
        } else {
            summary = feed.statusMessage();
        }
        return new Status(
                feed.providerId().name(),
                streaming,
                instrument,
                depth,
                liveTape,
                bidLevels,
                askLevels,
                today.toString(),
                archivedTape,
                archivedDom,
                summary,
                feed.statusMessage()
        );
    }

    public record Status(
            String provider,
            boolean streaming,
            String instrument,
            int orderbookDepth,
            int liveTapeSize,
            int bidLevels,
            int askLevels,
            String archiveDay,
            long archivedTapeLines,
            long archivedDomSnapshots,
            String summary,
            String detail
    ) {
    }
}
