package com.moex.trinity.marketdata;

import java.time.Instant;
import java.util.List;

/**
 * Thin DOM snapshot for marketplace feeds (not pairs ISS model).
 *
 * @param depth requested book depth (e.g. 10–50 for T-Invest)
 */
public record DomBook(
        String instrumentId,
        int depth,
        List<DomLevel> bids,
        List<DomLevel> asks,
        Instant asOf,
        boolean consistent
) {
    public record DomLevel(double price, long quantityLots) {}

    public double bestBid() {
        return bids == null || bids.isEmpty() ? Double.NaN : bids.get(0).price();
    }

    public double bestAsk() {
        return asks == null || asks.isEmpty() ? Double.NaN : asks.get(0).price();
    }

    public long topBidLots() {
        return bids == null || bids.isEmpty() ? 0 : Math.max(0, bids.get(0).quantityLots());
    }

    public long topAskLots() {
        return asks == null || asks.isEmpty() ? 0 : Math.max(0, asks.get(0).quantityLots());
    }
}
