package com.moex.trinity.marketdata;

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

    /**
     * Recent tape prints for VAP / zone building. Empty until stream fills a buffer.
     */
    default List<TradePrint> recentTrades(String instrumentId) {
        return List.of();
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
