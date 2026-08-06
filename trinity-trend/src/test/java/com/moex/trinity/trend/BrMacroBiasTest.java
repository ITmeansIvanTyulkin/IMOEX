package com.moex.trinity.trend;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrMacroBiasTest {

    @Test
    void dumpDayIsBearishAndBlocksBuy() {
        List<TrendBar> bars = dumpBars();
        LocalDateTime now = LocalDateTime.of(2026, 8, 4, 16, 30);
        BrMacroBias bias = BrMacroBias.resolve(
                bars, now, HtfTrend.DOWN, MarketState.TREND_DOWN,
                TrendPlaybookSettings.brDefaults(), 80);
        assertEquals(BrMacroBias.BEARISH, bias);
        assertTrue(bias.blocksBuy());
    }

    @Test
    void quietRangeIsNeutral() {
        List<TrendBar> bars = flatBars();
        LocalDateTime now = LocalDateTime.of(2026, 8, 5, 14, 0);
        BrMacroBias bias = BrMacroBias.resolve(
                bars, now, HtfTrend.FLAT, MarketState.RANGE,
                TrendPlaybookSettings.brDefaults(), 80);
        assertEquals(BrMacroBias.NEUTRAL, bias);
    }

    private static List<TrendBar> dumpBars() {
        List<TrendBar> bars = new ArrayList<>();
        LocalDateTime t = LocalDateTime.of(2026, 8, 4, 9, 0);
        double px = 86.0;
        for (int i = 0; i < 90; i++) {
            px -= 0.08;
            bars.add(new TrendBar(t.plusMinutes(i * 5L), px + 0.05, px + 0.08, px - 0.02, px, 1000));
        }
        return bars;
    }

    private static List<TrendBar> flatBars() {
        List<TrendBar> bars = new ArrayList<>();
        LocalDateTime t = LocalDateTime.of(2026, 8, 5, 9, 0);
        for (int i = 0; i < 60; i++) {
            double px = 80.0 + ((i % 5) - 2) * 0.02;
            bars.add(new TrendBar(t.plusMinutes(i * 5L), px, px + 0.03, px - 0.03, px, 800));
        }
        return bars;
    }
}
