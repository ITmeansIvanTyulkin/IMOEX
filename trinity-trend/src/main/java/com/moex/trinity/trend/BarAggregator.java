package com.moex.trinity.trend;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * OHLC bar aggregation helpers (M1 → M5, etc.).
 */
public final class BarAggregator {

    private BarAggregator() {
    }

    /** Floor each bar time to 5-minute Moscow wall buckets and merge OHLCV. */
    public static List<TrendBar> aggregateM5(List<TrendBar> m1) {
        if (m1 == null || m1.isEmpty()) {
            return List.of();
        }
        Map<LocalDateTime, List<TrendBar>> buckets = new TreeMap<>();
        for (TrendBar b : m1) {
            if (b == null || b.time() == null) {
                continue;
            }
            int m = b.time().getMinute();
            int floored = m - (m % 5);
            LocalDateTime key = b.time().withMinute(floored).withSecond(0).withNano(0);
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(b);
        }
        List<TrendBar> out = new ArrayList<>(buckets.size());
        for (Map.Entry<LocalDateTime, List<TrendBar>> e : buckets.entrySet()) {
            List<TrendBar> g = e.getValue();
            double open = g.get(0).open();
            double close = g.get(g.size() - 1).close();
            double high = g.stream().mapToDouble(TrendBar::high).max().orElse(open);
            double low = g.stream().mapToDouble(TrendBar::low).min().orElse(open);
            double vol = g.stream().mapToDouble(TrendBar::volume).sum();
            out.add(new TrendBar(e.getKey(), open, high, low, close, vol));
        }
        return out;
    }
}
