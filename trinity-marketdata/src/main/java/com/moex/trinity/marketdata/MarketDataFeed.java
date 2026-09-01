package com.moex.trinity.marketdata;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Live market-data feed SPI (DOM + trades). Implementations must not place orders.
 */
public interface MarketDataFeed {

    MarketDataProviderId providerId();

    /** Human status for operator UI / smoke checks. */
    String statusMessage();

    boolean streaming();

    /**
     * Best-effort snapshot. Empty until a real provider is wired and subscribed.
     */
    Optional<DomBook> latestBook(String instrumentId);

    /** All cached DOM snapshots (empty for NOOP). */
    default List<DomBook> snapshotBooks() {
        return List.of();
    }

    /**
     * Recent tape prints for VAP / zone building. Empty until stream fills a buffer.
     */
    default List<TradePrint> recentTrades(String instrumentId) {
        return List.of();
    }

    /** Last tape print for the instrument, or empty. */
    default Optional<TradePrint> lastTrade(String instrumentId) {
        List<TradePrint> all = recentTrades(instrumentId);
        if (all == null || all.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(all.get(all.size() - 1));
    }

    /**
     * Tape prints in {@code [from, to)} for one instrument. Default filters {@link #recentTrades};
     * live feeds should override to avoid copying the whole buffer.
     */
    default List<TradePrint> recentTradesWindow(String instrumentId, Instant from, Instant to) {
        List<TradePrint> all = recentTrades(instrumentId);
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        List<TradePrint> out = new ArrayList<>();
        for (TradePrint p : all) {
            if (p == null || p.time() == null) {
                continue;
            }
            if (from != null && p.time().isBefore(from)) {
                continue;
            }
            if (to != null && !p.time().isBefore(to)) {
                continue;
            }
            out.add(p);
        }
        return out;
    }

    /**
     * Subscribe hooks — no-op until T-Invest stream is implemented.
     */
    default void subscribeBook(String instrumentId, int depth) {
        /* skeleton */
    }

    default void subscribeTrades(String instrumentId) {
        /* skeleton */
    }
}
