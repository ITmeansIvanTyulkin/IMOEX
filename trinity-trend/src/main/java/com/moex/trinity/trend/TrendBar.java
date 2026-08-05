package com.moex.trinity.trend;

import java.time.LocalDateTime;

/**
 * Minimal OHLCV bar for trend robot (no dependency on pairs Candle).
 */
public record TrendBar(
        LocalDateTime time,
        double open,
        double high,
        double low,
        double close,
        double volume
) {
    public boolean valid() {
        return high >= low && close > 0 && high > 0 && low > 0 && volume >= 0;
    }

    public double typicalPrice() {
        return (high + low + close) / 3.0;
    }

    public boolean touches(double price, double tol) {
        return low - tol <= price && price <= high + tol;
    }
}
