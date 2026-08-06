package com.moex.trinity.trend;

import com.moex.trinity.marketdata.BrokerTapeArchive;
import com.moex.trinity.marketdata.TradePrint;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Build M5 OHLC bars from broker trade prints (tape archive).
 */
public final class TapeToM5Aggregator {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");

    private final BrokerTapeArchive archive;

    public TapeToM5Aggregator(BrokerTapeArchive archive) {
        this.archive = archive == null ? new BrokerTapeArchive(null) : archive;
    }

    public List<TrendBar> fromPrints(List<TradePrint> prints) {
        if (prints == null || prints.isEmpty()) {
            return List.of();
        }
        List<TradePrint> sorted = new ArrayList<>(prints);
        sorted.sort(Comparator.comparing(TradePrint::time, Comparator.nullsLast(Comparator.naturalOrder())));
        Map<LocalDateTime, List<TradePrint>> buckets = new TreeMap<>();
        for (TradePrint p : sorted) {
            if (p == null || p.time() == null || !(p.price() > 0)) {
                continue;
            }
            LocalDateTime t = LocalDateTime.ofInstant(p.time(), MSK);
            int m = t.getMinute();
            int floored = m - (m % 5);
            LocalDateTime key = t.withMinute(floored).withSecond(0).withNano(0);
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }
        List<TrendBar> out = new ArrayList<>(buckets.size());
        for (Map.Entry<LocalDateTime, List<TradePrint>> e : buckets.entrySet()) {
            List<TradePrint> g = e.getValue();
            double open = g.get(0).price();
            double close = g.get(g.size() - 1).price();
            double high = open;
            double low = open;
            double vol = 0;
            for (TradePrint p : g) {
                high = Math.max(high, p.price());
                low = Math.min(low, p.price());
                vol += Math.max(0, p.quantityLots());
            }
            out.add(new TrendBar(e.getKey(), open, high, low, close, vol));
        }
        return out;
    }

    /** Load today + 2 prior sessions from tape and aggregate to M5. */
    public List<TrendBar> loadRecentM5(String instrumentId, LocalDate today) throws Exception {
        LocalDate day = today == null ? LocalDate.now(MSK) : today;
        List<TradePrint> prints = new ArrayList<>();
        prints.addAll(archive.loadDay(instrumentId, day.minusDays(2)));
        prints.addAll(archive.loadDay(instrumentId, day.minusDays(1)));
        prints.addAll(archive.loadDay(instrumentId, day));
        return fromPrints(prints);
    }

    /** Raw prints for footprint / VAP (today + 2 prior days). */
    public List<TradePrint> loadRecentPrints(String instrumentId, LocalDate today) throws Exception {
        LocalDate day = today == null ? LocalDate.now(MSK) : today;
        List<TradePrint> prints = new ArrayList<>();
        prints.addAll(archive.loadDay(instrumentId, day.minusDays(2)));
        prints.addAll(archive.loadDay(instrumentId, day.minusDays(1)));
        prints.addAll(archive.loadDay(instrumentId, day));
        prints.sort(Comparator.comparing(TradePrint::time, Comparator.nullsLast(Comparator.naturalOrder())));
        return prints;
    }
}
