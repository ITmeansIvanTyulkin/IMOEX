package com.moex.trinity.trend;

import com.moex.trinity.marketdata.TradePrint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TapeToM5AggregatorTest {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");

    @Test
    void bucketsPrintsAcrossFiveMinuteBoundary() {
        TapeToM5Aggregator agg = new TapeToM5Aggregator(null);
        Instant t1 = LocalDateTime.of(2026, 8, 6, 10, 4, 10).atZone(MSK).toInstant();
        Instant t2 = LocalDateTime.of(2026, 8, 6, 10, 4, 50).atZone(MSK).toInstant();
        Instant t3 = LocalDateTime.of(2026, 8, 6, 10, 5, 1).atZone(MSK).toInstant();
        List<TrendBar> bars = agg.fromPrints(List.of(
                print(t1, 80.10, 1),
                print(t2, 80.20, 2),
                print(t3, 80.15, 3)
        ));
        assertEquals(2, bars.size());
        TrendBar first = bars.get(0);
        assertEquals(LocalDateTime.of(2026, 8, 6, 10, 0), first.time());
        assertEquals(80.10, first.open(), 1e-9);
        assertEquals(80.20, first.high(), 1e-9);
        assertEquals(80.10, first.low(), 1e-9);
        assertEquals(80.20, first.close(), 1e-9);
        assertEquals(3.0, first.volume(), 1e-9);
        TrendBar second = bars.get(1);
        assertEquals(LocalDateTime.of(2026, 8, 6, 10, 5), second.time());
        assertEquals(80.15, second.open(), 1e-9);
        assertEquals(3.0, second.volume(), 1e-9);
    }

    @Test
    void barAggregatorM5FromM1() {
        List<TrendBar> m1 = List.of(
                new TrendBar(LocalDateTime.of(2026, 8, 6, 11, 0), 1, 2, 0.5, 1.5, 10),
                new TrendBar(LocalDateTime.of(2026, 8, 6, 11, 1), 1.5, 2.5, 1.4, 2.0, 5),
                new TrendBar(LocalDateTime.of(2026, 8, 6, 11, 5), 2.0, 2.1, 1.9, 2.05, 7)
        );
        List<TrendBar> m5 = BarAggregator.aggregateM5(m1);
        assertEquals(2, m5.size());
        assertEquals(1.0, m5.get(0).open(), 1e-9);
        assertEquals(2.0, m5.get(0).close(), 1e-9);
        assertEquals(2.5, m5.get(0).high(), 1e-9);
        assertEquals(15.0, m5.get(0).volume(), 1e-9);
        assertTrue(m5.get(1).time().getMinute() == 5);
    }

    private static TradePrint print(Instant t, double px, long qty) {
        return new TradePrint("BRU6", px, qty, t, TradePrint.TradeSide.BUY);
    }
}
