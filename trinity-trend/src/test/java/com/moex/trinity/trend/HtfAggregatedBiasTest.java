package com.moex.trinity.trend;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtfAggregatedBiasTest {

    @Test
    void aggregateM5ToH1Buckets() {
        List<TrendBar> m5 = new ArrayList<>();
        LocalDateTime t = LocalDateTime.of(2026, 8, 7, 10, 0);
        for (int i = 0; i < 12; i++) {
            double px = 80 + i * 0.05;
            m5.add(new TrendBar(t.plusMinutes(i * 5L), px, px + 0.02, px - 0.02, px, 100));
        }
        List<TrendBar> h1 = BarAggregator.aggregateMinutes(m5, 60);
        assertEquals(1, h1.size());
        assertEquals(LocalDateTime.of(2026, 8, 7, 10, 0), h1.get(0).time());
        assertEquals(80.0, h1.get(0).open(), 1e-9);
        assertEquals(80.0 + 11 * 0.05, h1.get(0).close(), 1e-9);
    }

    @Test
    void completedH1SlopeIsDownAndPreferredOverM5Proxy() {
        // 4 completed hours dumping >50 pts; current hour still open
        List<TrendBar> m5 = new ArrayList<>();
        LocalDateTime start = LocalDateTime.of(2026, 8, 7, 9, 0);
        double px = 86.0;
        for (int i = 0; i < 4 * 12 + 6; i++) { // 4h + half open hour
            px -= 0.05; // 60 pts over 4h
            m5.add(new TrendBar(start.plusMinutes(i * 5L), px + 0.02, px + 0.03, px - 0.01, px, 200));
        }
        LocalDateTime now = start.plusMinutes((4 * 12 + 5) * 5L); // inside 5th hour
        TrendPlaybookSettings s = TrendPlaybookSettings.brDefaults();
        HtfTrend.Resolved r = HtfTrend.resolveDetailed(m5, now, s);
        assertEquals(HtfTrend.DOWN, r.trend());
        assertEquals("H1", r.source());
        assertTrue(r.trend().againstTrend(true));
        assertTrue(r.trend().withTrend(false));
    }

    @Test
    void fallsBackToM5ProxyWhenTooFewH1Bars() {
        List<TrendBar> m5 = climb(LocalDateTime.of(2026, 8, 7, 9, 0), 78.0, 20, 0.03);
        LocalDateTime now = m5.get(m5.size() - 1).time();
        // Not enough completed H1 for lookback=3 → M5 proxy
        HtfTrend.Resolved r = HtfTrend.resolveDetailed(m5, now, TrendPlaybookSettings.brDefaults());
        assertEquals("M5_PROXY", r.source());
    }

    private static List<TrendBar> climb(LocalDateTime start, double px, int n, double step) {
        List<TrendBar> bars = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double c = px + i * step;
            bars.add(new TrendBar(start.plusMinutes(i * 5L), c - 0.01, c + 0.01, c - 0.02, c, 1000));
        }
        return bars;
    }
}
