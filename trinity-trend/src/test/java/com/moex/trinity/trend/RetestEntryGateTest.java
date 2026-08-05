package com.moex.trinity.trend;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetestEntryGateTest {

    @Test
    void doesNotArmWhileFarBelowAfterDownHold() {
        MergedVolumeRange zone = new MergedVolumeRange(79.20, 79.40, 100, List.of(), true, null);
        List<TrendBar> bars = new ArrayList<>();
        LocalDateTime t = LocalDateTime.of(2026, 8, 5, 7, 0);
        // touch zone then break down and hold outside
        bars.add(bar(t, 79.25, 79.35, 79.20, 79.30));
        bars.add(bar(t.plusMinutes(5), 79.10, 79.15, 79.05, 79.08));
        bars.add(bar(t.plusMinutes(10), 79.05, 79.10, 78.95, 78.98));
        bars.add(bar(t.plusMinutes(15), 78.95, 79.00, 78.85, 78.90));
        // still far below — must NOT arm
        bars.add(bar(t.plusMinutes(20), 78.85, 78.95, 78.75, 78.80));
        assertTrue(LevelsProfileBrPlaybook.breakHoldSatisfied(bars, zone, false, 2));
        assertFalse(LevelsProfileBrPlaybook.retestEntryAllowed(bars, zone, false, 10, 0.01));
    }

    @Test
    void armsOnTouchAfterDownHold() {
        MergedVolumeRange zone = new MergedVolumeRange(79.20, 79.40, 100, List.of(), true, null);
        List<TrendBar> bars = new ArrayList<>();
        LocalDateTime t = LocalDateTime.of(2026, 8, 5, 7, 0);
        bars.add(bar(t, 79.25, 79.35, 79.20, 79.30));
        bars.add(bar(t.plusMinutes(5), 79.10, 79.15, 79.00, 79.05));
        bars.add(bar(t.plusMinutes(10), 79.00, 79.05, 78.90, 78.95));
        bars.add(bar(t.plusMinutes(15), 78.95, 79.00, 78.85, 78.90));
        // retest wick into zone
        bars.add(bar(t.plusMinutes(20), 79.00, 79.25, 78.95, 79.10));
        assertTrue(LevelsProfileBrPlaybook.breakHoldSatisfied(bars, zone, false, 2));
        assertTrue(LevelsProfileBrPlaybook.retestEntryAllowed(bars, zone, false, 10, 0.01));
    }

    @Test
    void armsWhenWithinMaxDistance() {
        MergedVolumeRange zone = new MergedVolumeRange(79.20, 79.40, 100, List.of(), true, null);
        LocalDateTime t = LocalDateTime.of(2026, 8, 5, 8, 0);
        List<TrendBar> bars = new ArrayList<>();
        bars.add(bar(t, 79.25, 79.35, 79.20, 79.28)); // in zone
        bars.add(bar(t.plusMinutes(5), 79.10, 79.15, 79.00, 79.05)); // outside
        bars.add(bar(t.plusMinutes(10), 79.00, 79.05, 78.90, 78.95)); // outside
        bars.add(bar(t.plusMinutes(15), 78.95, 79.00, 78.85, 78.90)); // outside
        // 8 pts below zone low — within maxDist=10, not within 5
        bars.add(bar(t.plusMinutes(20), 79.08, 79.14, 79.05, 79.12));
        assertTrue(LevelsProfileBrPlaybook.breakHoldSatisfied(bars, zone, false, 2));
        assertTrue(LevelsProfileBrPlaybook.retestEntryAllowed(bars, zone, false, 10, 0.01),
                "dist=" + (79.20 - 79.12) / 0.01);
        assertFalse(LevelsProfileBrPlaybook.retestEntryAllowed(bars, zone, false, 5, 0.01));
    }

    @Test
    void fillQuotaNotBurnedByArm() {
        TrendPlaybook sticky = new OneSetupPerZoneTest.StickyArmedPlaybook();
        TrendRobotEngine engine = new TrendRobotEngine(sticky, TrendPlaybookSettings.brDefaults());
        TrendAccountContext acct = TrendAccountContext.of(100_000, 15_000, 16_000, 1.0);
        List<TrendBar> bars = new ArrayList<>();
        LocalDateTime t = LocalDateTime.of(2024, 12, 1, 10, 0);
        for (int i = 0; i < 40; i++) {
            double c = 84.5 + i * 0.02;
            bars.add(new TrendBar(t.plusMinutes(i * 5L), c, c + 0.05, c - 0.05, c, 800));
        }
        engine.evaluate(new TrendBarSeries("BR", "M5", bars), acct);
        assertTrue(engine.setupsTodayCount() == 0, "arm must not count");
        engine.clearSetupLock();
        assertTrue(engine.setupsTodayCount() == 0, "cancel must not count");
        engine.evaluate(new TrendBarSeries("BR", "M5", bars), acct);
        engine.registerFill(bars.get(bars.size() - 1).time());
        assertTrue(engine.setupsTodayCount() == 1, "fill counts");
    }

    private static TrendBar bar(LocalDateTime t, double o, double h, double l, double c) {
        return new TrendBar(t, o, h, l, c, 1000);
    }
}
