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
}
