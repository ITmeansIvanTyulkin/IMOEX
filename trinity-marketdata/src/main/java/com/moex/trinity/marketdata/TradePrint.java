package com.moex.trinity.marketdata;

import java.time.Instant;

/**
 * Single trade print from a live tape (broker stream later).
 */
public record TradePrint(
        String instrumentId,
        double price,
        long quantityLots,
        Instant time,
        TradeSide side
) {
    public enum TradeSide {
        BUY,
        SELL,
        UNKNOWN
    }
}
