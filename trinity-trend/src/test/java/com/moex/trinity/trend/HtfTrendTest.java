package com.moex.trinity.trend;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtfTrendTest {

    @Test
    void flatWhenMoveTooSmall() {
        List<TrendBar> bars = climb(LocalDateTime.of(2026, 8, 5, 9, 0), 78.0, 20, 0.01);
        assertEquals(HtfTrend.FLAT, HtfTrend.fromSessionOpen(
                bars, bars.get(bars.size() - 1).time(), "09:00", 50, 0.01));
    }

    @Test
    void upWhenSessionRalliesEnough() {
        List<TrendBar> bars = climb(LocalDateTime.of(2026, 8, 5, 9, 0), 78.0, 60, 0.02);
        HtfTrend t = HtfTrend.fromSessionOpen(
                bars, bars.get(bars.size() - 1).time(), "09:00", 50, 0.01);
        assertEquals(HtfTrend.UP, t);
        assertTrue(t.withTrend(true));
        assertTrue(t.againstTrend(false));
    }

    @Test
    void resolveRequiresAgreement() {
        TrendPlaybookSettings s = TrendPlaybookSettings.brDefaults();
        // Strong open→now UP but short slope flat/down conflict → FLAT when agreement on
        List<TrendBar> bars = new ArrayList<>();
        LocalDateTime t = LocalDateTime.of(2026, 8, 5, 9, 0);
        double px = 78.0;
        for (int i = 0; i < 40; i++) {
            px += 0.03; // rally early
            bars.add(new TrendBar(t.plusMinutes(i * 5L), px - 0.01, px + 0.01, px - 0.02, px, 1000));
        }
        for (int i = 40; i < 64; i++) {
            px -= 0.02; // recent slope down
            bars.add(new TrendBar(t.plusMinutes(i * 5L), px + 0.01, px + 0.02, px - 0.01, px, 1000));
        }
        assertEquals(HtfTrend.FLAT, HtfTrend.resolve(bars, bars.get(bars.size() - 1).time(), s));
    }

    @Test
    void againstHelpers() {
        assertFalse(HtfTrend.FLAT.withTrend(true));
        assertFalse(HtfTrend.FLAT.againstTrend(true));
        assertTrue(HtfTrend.DOWN.withTrend(false));
        assertTrue(HtfTrend.DOWN.againstTrend(true));
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
