package com.moex.trinity.trend;

import com.moex.trinity.marketdata.TradePrint;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Checklist footprint: bid/ask (buy/sell aggressor) lots per price inside each M5 bar.
 */
public final class FootprintAggregator {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");

    private final double pointSize;

    public FootprintAggregator(double pointSize) {
        this.pointSize = pointSize > 0 ? pointSize : 0.01;
    }

    public record FootLevel(double price, long buyLots, long sellLots) {
        public long delta() {
            return buyLots - sellLots;
        }

        public long total() {
            return buyLots + sellLots;
        }
    }

    public record FootBar(LocalDateTime time, List<FootLevel> levels) {
    }

    /**
     * Build footprint for the last {@code maxBars} M5 buckets that have prints.
     */
    public List<FootBar> build(List<TradePrint> prints, int maxBars) {
        if (prints == null || prints.isEmpty() || maxBars <= 0) {
            return List.of();
        }
        NavigableMap<LocalDateTime, NavigableMap<Long, long[]>> byBar = new TreeMap<>();
        for (TradePrint p : prints) {
            if (p == null || p.time() == null || !(p.price() > 0) || p.quantityLots() <= 0) {
                continue;
            }
            LocalDateTime t = LocalDateTime.ofInstant(p.time(), MSK);
            int m = t.getMinute();
            LocalDateTime key = t.withMinute(m - (m % 5)).withSecond(0).withNano(0);
            long bucket = Math.round(p.price() / pointSize);
            NavigableMap<Long, long[]> levels = byBar.computeIfAbsent(key, k -> new TreeMap<>());
            long[] bs = levels.computeIfAbsent(bucket, b -> new long[2]);
            if (p.side() == TradePrint.TradeSide.SELL) {
                bs[1] += p.quantityLots();
            } else {
                // BUY + UNKNOWN count as buy-side proxy (at least show volume)
                bs[0] += p.quantityLots();
            }
        }
        List<LocalDateTime> keys = new ArrayList<>(byBar.keySet());
        int from = Math.max(0, keys.size() - maxBars);
        List<FootBar> out = new ArrayList<>();
        for (int i = from; i < keys.size(); i++) {
            LocalDateTime bt = keys.get(i);
            List<FootLevel> levels = new ArrayList<>();
            for (Map.Entry<Long, long[]> e : byBar.get(bt).entrySet()) {
                long[] bs = e.getValue();
                levels.add(new FootLevel(e.getKey() * pointSize, bs[0], bs[1]));
            }
            levels.sort(Comparator.comparingDouble(FootLevel::price).reversed());
            out.add(new FootBar(bt, levels));
        }
        return out;
    }

    public static List<Map<String, Object>> toDto(List<FootBar> bars) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (bars == null) {
            return out;
        }
        for (FootBar b : bars) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("time", b.time() == null ? null : b.time().toString() + "+03:00");
            List<Map<String, Object>> lv = new ArrayList<>();
            for (FootLevel l : b.levels()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("price", l.price());
                row.put("buy", l.buyLots());
                row.put("sell", l.sellLots());
                row.put("delta", l.delta());
                lv.add(row);
            }
            m.put("levels", lv);
            out.add(m);
        }
        return out;
    }
}
