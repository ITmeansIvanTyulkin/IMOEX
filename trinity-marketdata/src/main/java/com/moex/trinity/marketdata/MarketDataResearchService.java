package com.moex.trinity.marketdata;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Facade for marketplace market-data contour.
 */
public class MarketDataResearchService {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    /** When stream is down or book older than this, pull unary GetOrderBook from broker. */
    private static final long BOOK_STALE_MS = 20_000L;
    /** Min gap between unary book refreshes per instrument (desk + DOM poll). */
    private static final long BOOK_REST_MIN_GAP_MS = 5_000L;

    private final MarketDataFeed feed;
    private final BrokerTapeArchive archive;
    private final String defaultInstrument;
    private final ConcurrentHashMap<String, Long> lastBookRestMs = new ConcurrentHashMap<>();

    public MarketDataResearchService(MarketDataFeed feed) {
        this(feed, new BrokerTapeArchive(Path.of("data", "broker-tape")), "BRU6");
    }

    public MarketDataResearchService(MarketDataFeed feed, BrokerTapeArchive archive, String defaultInstrument) {
        this.feed = feed;
        this.archive = archive == null ? new BrokerTapeArchive(Path.of("data", "broker-tape")) : archive;
        this.defaultInstrument = defaultInstrument == null || defaultInstrument.isBlank() ? "BRU6" : defaultInstrument;
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

    public String statusMessage() {
        return feed.statusMessage();
    }

    public boolean liveReady() {
        return feed.streaming();
    }

    /**
     * Live book if streaming; unary REST refresh when stale; else archive tail.
     */
    public Optional<DomBook> resolveBook(String instrumentId) {
        String id = instrumentId == null || instrumentId.isBlank() ? defaultInstrument : instrumentId.trim();
        DomBook book = feed.latestBook(id).orElse(null);
        if (book == null && feed instanceof TInvestMarketDataFeed t) {
            book = t.anyBook().orElse(null);
        }
        if (needsRestRefresh(book)) {
            Optional<DomBook> refreshed = refreshBookRest(id);
            if (refreshed.isPresent()) {
                book = refreshed.get();
            }
        }
        if (book != null) {
            return Optional.of(book);
        }
        try {
            List<DomBook> day = archive.loadDomDay(id, LocalDate.now(MSK));
            if (day.isEmpty()) {
                day = archive.loadDomDay(id, LocalDate.now(MSK).minusDays(1));
            }
            if (!day.isEmpty()) {
                DomBook archived = day.get(day.size() - 1);
                if (needsRestRefresh(archived)) {
                    Optional<DomBook> refreshed = refreshBookRest(id);
                    if (refreshed.isPresent()) {
                        return refreshed;
                    }
                }
                return Optional.of(archived);
            }
        } catch (Exception ignored) {
            // empty
        }
        return refreshBookRest(id);
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
        return Instant.now().toEpochMilli() - book.asOf().toEpochMilli() > BOOK_STALE_MS;
    }

    private Optional<DomBook> refreshBookRest(String instrumentId) {
        long now = System.currentTimeMillis();
        String key = instrumentId == null ? "" : instrumentId.trim().toUpperCase();
        Long prev = lastBookRestMs.get(key);
        if (prev != null && now - prev < BOOK_REST_MIN_GAP_MS) {
            return Optional.empty();
        }
        TInvestCredentials creds = TInvestCredentials.resolve();
        if (!creds.present()) {
            return Optional.empty();
        }
        try (TInvestBrokerMarketData md = new TInvestBrokerMarketData(creds)) {
            String figi = md.resolveFigi(instrumentId);
            int depth = feed instanceof TInvestMarketDataFeed t ? t.orderbookDepth() : 50;
            DomBook book = md.fetchOrderBook(instrumentId, figi, depth);
            lastBookRestMs.put(key, now);
            if (feed instanceof TInvestMarketDataFeed t) {
                t.putBook(book);
            }
            return Optional.of(book);
        } catch (Exception ignored) {
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
            Optional<DomBook> book = t.anyBook().or(() -> t.latestBook(instrument));
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
