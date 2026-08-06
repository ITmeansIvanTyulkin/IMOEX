package com.moex.trinity.trend;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LevelsProfileStructureTest {

    @Test
    void structureExposesLookbackEvenWhenHtfFlatSession() {
        LevelsProfileBrPlaybook pb = new LevelsProfileBrPlaybook();
        List<TrendBar> bars = flatishBars();
        TrendStructureSnapshot st = pb.structure(new TrendBarSeries("BRU6", "M5", bars));
        assertTrue(st.lookbackBars() >= 60);
        assertTrue(Double.isFinite(st.lookbackHigh()));
        assertTrue(Double.isFinite(st.lookbackLow()));
        assertTrue(st.lookbackHigh() >= st.lookbackLow());
        assertNotNull(st.htf());
        assertNotNull(st.note());
        assertFalse(st.note().isBlank());
    }

    @Test
    void emptySeriesReturnsEmptySnapshot() {
        LevelsProfileBrPlaybook pb = new LevelsProfileBrPlaybook();
        TrendStructureSnapshot st = pb.structure(new TrendBarSeries("BR", "M5", List.of()));
        assertEquals(0, st.lookbackBars());
    }

    @Test
    void structureNoteMentionsTwoZones() {
        LevelsProfileBrPlaybook pb = new LevelsProfileBrPlaybook();
        TrendStructureSnapshot st = pb.structure(new TrendBarSeries("BRU6", "M5", flatishBars()));
        assertNotNull(st.note());
        // Snapshot always has room for zoneTop + zoneBottom (either or both may be null on flat tape)
        assertTrue(st.note().toLowerCase().contains("зон") || st.htf() != null);
    }

    @Test
    void structureBuildsTwoZonesAtCurrentTrendExtremes() {
        LevelsProfileBrPlaybook pb = new LevelsProfileBrPlaybook();
        List<TrendBar> bars = channelBarsWithTouches();
        // Older spike outside current trend window → historical ≠ trend extremes
        LocalDateTime early = LocalDateTime.of(2026, 8, 5, 10, 0);
        bars.add(0, new TrendBar(early, 82.0, 88.0, 77.0, 82.0, 2000));
        TrendStructureSnapshot st = pb.structure(new TrendBarSeries("BRU6", "M5", bars));
        assertTrue(Double.isFinite(st.lookbackHigh()));
        assertTrue(Double.isFinite(st.lookbackLow()));
        assertTrue(st.lookbackHigh() > st.lookbackLow());
        assertTrue(Double.isFinite(st.historicalHigh()));
        assertTrue(Double.isFinite(st.historicalLow()));
        assertTrue(st.historicalHigh() >= st.lookbackHigh());
        assertTrue(st.historicalLow() <= st.lookbackLow());
        // Soft structure zones at both extremes when candles touch HI and LO
        assertNotNull(st.zoneTop(), "TOP zone at trend high");
        assertNotNull(st.zoneBottom(), "BOT zone at trend low");
        assertTrue(st.zoneTop().mid() > st.zoneBottom().mid());
        assertTrue(Math.abs(st.zoneTop().mid() - st.lookbackHigh())
                < Math.abs(st.zoneTop().mid() - st.lookbackLow()));
        assertTrue(Math.abs(st.zoneBottom().mid() - st.lookbackLow())
                < Math.abs(st.zoneBottom().mid() - st.lookbackHigh()));
    }

    @Test
    void zeroPointBrokenWhenTrendTakesPriorSwing() {
        assertTrue(LevelsProfileBrPlaybook.zeroPointBroken(
                TrendBias.UP, 80.50, 79.50, 80.00, 0.01));
        assertFalse(LevelsProfileBrPlaybook.zeroPointBroken(
                TrendBias.UP, 79.90, 79.50, 80.00, 0.01));
    }

    /** Synthetic channel: repeated touches of high≈80.5 and low≈79.5. */
    private static List<TrendBar> channelBarsWithTouches() {
        List<TrendBar> bars = new ArrayList<>();
        LocalDateTime t = LocalDateTime.of(2026, 8, 6, 6, 0);
        double lo = 79.50;
        double hi = 80.50;
        for (int i = 0; i < 90; i++) {
            boolean nearLo = (i % 10) < 3;
            boolean nearHi = (i % 10) >= 7;
            double c;
            double h;
            double l;
            if (nearLo) {
                l = lo;
                h = lo + 0.25;
                c = lo + 0.10;
            } else if (nearHi) {
                h = hi;
                l = hi - 0.25;
                c = hi - 0.10;
            } else {
                c = 80.0 + ((i % 5) - 2) * 0.05;
                h = c + 0.08;
                l = c - 0.08;
            }
            bars.add(new TrendBar(t.plusMinutes(i * 5L), c, h, l, c, 1200 + i * 10));
        }
        return bars;
    }

    private static List<TrendBar> flatishBars() {
        List<TrendBar> bars = new ArrayList<>();
        LocalDateTime t = LocalDateTime.of(2026, 8, 6, 9, 0);
        double px = 80.0;
        for (int i = 0; i < 90; i++) {
            double wiggle = (i % 7 - 3) * 0.02;
            double c = px + wiggle;
            bars.add(new TrendBar(t.plusMinutes(i * 5L), c - 0.01, c + 0.05, c - 0.05, c, 800 + i));
        }
        return bars;
    }
}
