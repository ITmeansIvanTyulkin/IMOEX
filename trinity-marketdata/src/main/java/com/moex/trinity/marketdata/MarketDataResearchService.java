package com.moex.trinity.marketdata;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Facade for marketplace market-data contour.
 */
public class MarketDataResearchService {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");

    private final MarketDataFeed feed;
    private final BrokerTapeArchive archive;
    private final String defaultInstrument;

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
     * Live book if streaming; else last DOM snapshot from today's archive.
     */
    public Optional<DomBook> resolveBook(String instrumentId) {
        String id = instrumentId == null || instrumentId.isBlank() ? defaultInstrument : instrumentId.trim();
        Optional<DomBook> live = feed.latestBook(id);
        if (live.isEmpty() && feed instanceof TInvestMarketDataFeed t) {
            live = t.anyBook();
        }
        if (live.isPresent()) {
            return live;
        }
        try {
            List<DomBook> day = archive.loadDomDay(id, LocalDate.now(MSK));
            if (day.isEmpty()) {
                day = archive.loadDomDay(id, LocalDate.now(MSK).minusDays(1));
            }
            if (!day.isEmpty()) {
                return Optional.of(day.get(day.size() - 1));
            }
        } catch (Exception ignored) {
            // empty
        }
        return Optional.empty();
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
