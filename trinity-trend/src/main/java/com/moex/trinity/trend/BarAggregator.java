package com.moex.trinity.trend;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * OHLC bar aggregation helpers (M1 → M5, M5 → M15/H1, etc.).
 */
public final class BarAggregator {

    private BarAggregator() {
    }

    /** Floor each bar time to 5-minute Moscow wall buckets and merge OHLCV. */
    public static List<TrendBar> aggregateM5(List<TrendBar> m1) {
        return aggregateMinutes(m1, 5);
    }

    /**
     * Floor each bar to {@code periodMinutes} wall-clock buckets and merge OHLCV.
     * Typical: M5→M15 (15), M5→H1 (60).
     */
    public static List<TrendBar> aggregateMinutes(List<TrendBar> source, int periodMinutes) {
        if (source == null || source.isEmpty() || periodMinutes <= 0) {
            return List.of();
        }
        int period = periodMinutes;
        Map<LocalDateTime, List<TrendBar>> buckets = new TreeMap<>();
        for (TrendBar b : source) {
            if (b == null || b.time() == null) {
                continue;
            }
            int totalMin = b.time().getHour() * 60 + b.time().getMinute();
            int floored = totalMin - (totalMin % period);
            int hour = floored / 60;
            int minute = floored % 60;
            LocalDateTime key = b.time().withHour(hour).withMinute(minute).withSecond(0).withNano(0);
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
