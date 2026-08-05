package com.moex.trinity.trend;

import java.util.List;

/**
 * M5 (or other) series for one instrument, oldest → newest.
 */
public record TrendBarSeries(
        String instrument,
        String timeframe,
        List<TrendBar> bars
) {
    public TrendBarSeries {
        bars = bars == null ? List.of() : List.copyOf(bars);
    }

    public boolean isEmpty() {
        return bars.isEmpty();
    }

    public TrendBar last() {
        return bars.get(bars.size() - 1);
    }

    public int size() {
        return bars.size();
    }
}
