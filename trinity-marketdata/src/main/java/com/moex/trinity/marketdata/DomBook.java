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

    /** Highest bid — do not assume list order (stream snapshots can be unsorted). */
    public double bestBid() {
        if (bids == null || bids.isEmpty()) {
            return Double.NaN;
        }
        double best = Double.NaN;
        for (DomLevel l : bids) {
            if (l == null || !(l.price() > 0)) {
                continue;
            }
            if (Double.isNaN(best) || l.price() > best) {
                best = l.price();
            }
        }
        return best;
    }

    /** Lowest ask — do not assume list order. */
    public double bestAsk() {
        if (asks == null || asks.isEmpty()) {
            return Double.NaN;
        }
        double best = Double.NaN;
        for (DomLevel l : asks) {
            if (l == null || !(l.price() > 0)) {
                continue;
            }
            if (Double.isNaN(best) || l.price() < best) {
                best = l.price();
            }
        }
        return best;
    }

    public long topBidLots() {
        double bb = bestBid();
        if (!(bb > 0) || bids == null) {
            return 0;
        }
        for (DomLevel l : bids) {
            if (l != null && Math.abs(l.price() - bb) < 1e-9) {
                return Math.max(0, l.quantityLots());
            }
        }
        return 0;
    }

    public long topAskLots() {
        double ba = bestAsk();
        if (!(ba > 0) || asks == null) {
            return 0;
        }
        for (DomLevel l : asks) {
            if (l != null && Math.abs(l.price() - ba) < 1e-9) {
                return Math.max(0, l.quantityLots());
            }
        }
        return 0;
    }
}
