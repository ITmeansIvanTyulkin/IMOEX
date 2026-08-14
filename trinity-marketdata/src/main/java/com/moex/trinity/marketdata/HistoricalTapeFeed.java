package com.moex.trinity.marketdata;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Clocked broker tape + DOM for day-replay (no look-ahead).
 * DOM comes from our live archive — broker has no hist orderbook API.
 */
public final class HistoricalTapeFeed implements MarketDataFeed {

    public static final ZoneId MSK = ZoneId.of("Europe/Moscow");

    private final String instrumentId;
    private final TradeTapeBuffer buffer;
    private final List<DomBook> booksChrono;
    private final AtomicReference<Instant> asOf = new AtomicReference<>(Instant.EPOCH);
    private final AtomicReference<DomBook> book = new AtomicReference<>();

    public HistoricalTapeFeed(String instrumentId, List<TradePrint> sessionPrints, int capacity) {
        this.instrumentId = instrumentId == null ? "BR" : instrumentId;
        this.buffer = new TradeTapeBuffer(capacity);
        this.booksChrono = new ArrayList<>();
        if (sessionPrints != null) {
            buffer.addAll(sessionPrints);
        }
    }

    /** Single static snapshot (e.g. live unary seed) — not clocked. */
    public HistoricalTapeFeed withBook(DomBook domBook) {
        if (domBook != null) {
            book.set(domBook);
        }
        return this;
    }

    /** Hist DOM series from {@code data/broker-tape/dom-*.jsonl}; clocked via {@link #setAsOf}. */
    public HistoricalTapeFeed withBooks(List<DomBook> books) {
        booksChrono.clear();
        if (books != null && !books.isEmpty()) {
            List<DomBook> copy = new ArrayList<>(books);
            copy.sort(Comparator.comparing(DomBook::asOf, Comparator.nullsLast(Comparator.naturalOrder())));
            booksChrono.addAll(copy);
            book.set(null); // prefer clocked selection
        }
        return this;
    }

    public void setAsOf(LocalDateTime moscowBarTime) {
        if (moscowBarTime == null) {
            return;
        }
        Instant cursor = moscowBarTime.plusMinutes(4).plusSeconds(59).atZone(MSK).toInstant();
        asOf.set(cursor);
        refreshBook(cursor);
    }

    public void setAsOfInstant(Instant instant) {
        if (instant != null) {
            asOf.set(instant);
            refreshBook(instant);
        }
    }

    private void refreshBook(Instant cursor) {
        if (booksChrono.isEmpty()) {
            return;
        }
        DomBook best = null;
        for (DomBook b : booksChrono) {
            if (b.asOf() != null && !b.asOf().isAfter(cursor)) {
                best = b;
            } else if (b.asOf() != null && b.asOf().isAfter(cursor)) {
                break;
            }
        }
        book.set(best);
    }

    public int tapeSize() {
        return buffer.size();
    }

    public int domSnapshots() {
        return booksChrono.size();
    }

    @Override
    public MarketDataProviderId providerId() {
        return MarketDataProviderId.T_INVEST;
    }

    @Override
    public String statusMessage() {
        DomBook b = book.get();
        return "Broker hist for " + instrumentId + " tape=" + buffer.size()
                + " domSnaps=" + booksChrono.size()
                + " asOf=" + asOf.get()
                + (b != null ? " DOM depth=" + b.depth() : "");
    }

    @Override
    public boolean streaming() {
        return buffer.size() > 0;
    }

    @Override
    public Optional<DomBook> latestBook(String ignored) {
        return Optional.ofNullable(book.get());
    }

    @Override
    public List<TradePrint> recentTrades(String ignored) {
        return buffer.snapshotUntil(asOf.get());
    }
}
