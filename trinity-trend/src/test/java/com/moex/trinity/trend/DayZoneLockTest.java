package com.moex.trinity.trend;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DayZoneLockTest {

    @Test
    void firstZonesFreezeWhileExtremesStayInside() {
        DayZoneLock lock = new DayZoneLock();
        LocalDate day = LocalDate.of(2026, 8, 6);
        MergedVolumeRange top1 = new MergedVolumeRange(81.60, 81.79, 100, List.of(), true, null);
        MergedVolumeRange bot1 = new MergedVolumeRange(79.47, 79.65, 100, List.of(), true, null);
        var s1 = lock.absorb(day, 81.71, 79.54, top1, "BARS", bot1, "BARS");
        assertEquals(81.60, s1.top().low(), 1e-9);

        // Small wiggle — must NOT move locked day zones
        MergedVolumeRange top2 = new MergedVolumeRange(81.70, 81.90, 100, List.of(), true, null);
        var s2 = lock.absorb(day, 81.85, 79.50, top2, "BARS", bot1, "BARS");
        assertEquals(81.60, s2.top().low(), 1e-9);
        assertEquals(79.47, s2.bottom().low(), 1e-9);
    }

    @Test
    void brokenShelfClearsEvenIfNotPriorSoNewExtremeCanLock() {
        DayZoneLock lock = new DayZoneLock();
        LocalDate day = LocalDate.of(2026, 8, 6);
        MergedVolumeRange top = new MergedVolumeRange(80.13, 80.32, 100, List.of(), true, null);
        MergedVolumeRange bot = new MergedVolumeRange(79.47, 79.65, 100, List.of(), true, null);
        lock.absorb(day, 80.30, 79.54, top, "BARS+DAY", bot, "BARS+DAY");

        // Price runs to 81.71 — old TOP broken by >20 pts → clear for re-lock
        lock.clearBrokenShelves(81.71, 79.54, 20, 0.01);
        assertNull(lock.get().top());
        assertNotNull(lock.get().bottom());

        MergedVolumeRange newTop = new MergedVolumeRange(81.55, 81.74, 80, List.of(), true, null);
        var s = lock.absorb(day, 81.71, 79.54, newTop, "BARS", null, null);
        assertEquals(81.55, s.top().low(), 1e-9);
    }

    @Test
    void priorAndSameDayBottomClearsOnBreakdown() {
        DayZoneLock lock = new DayZoneLock();
        LocalDate day = LocalDate.of(2026, 8, 4);
        MergedVolumeRange priorBot = new MergedVolumeRange(83.25, 83.43, 100, List.of(), true, null);
        MergedVolumeRange priorTop = new MergedVolumeRange(85.56, 85.74, 100, List.of(), true, null);
        lock.absorb(day, 85.71, 84.97, priorTop, "PRIOR_DAY_TOP", priorBot, "PRIOR_DAY_BOT");

        lock.clearBrokenShelves(85.71, 79.50, 20, 0.01);
        assertNull(lock.get().bottom());
        assertNotNull(lock.get().top());

        MergedVolumeRange newBot = new MergedVolumeRange(79.40, 79.60, 50, List.of(), true, null);
        var s = lock.absorb(day, 85.71, 79.50, null, null, newBot, "BARS");
        assertEquals(79.40, s.bottom().low(), 1e-9);

        // Further crash clears the new day shelf too — allows next lock at fresher LO
        lock.clearBrokenShelves(85.71, 78.00, 20, 0.01);
        assertNull(lock.get().bottom());
    }

    @Test
    void kickHardClearsPlaybookLocks() {
        LevelsProfileBrPlaybook pb = new LevelsProfileBrPlaybook();
        List<TrendBar> bars = channel();
        pb.structure(new TrendBarSeries("BRU6", "M5", bars));
        var kr = pb.kickHard("test");
        assertTrue(kr.cleared());
        assertNull(pb.dayZoneSnapshot());
    }

    @Test
    void kickAwakeClearsEngineLocks() {
        LevelsProfileBrPlaybook pb = new LevelsProfileBrPlaybook();
        TrendRobotEngine engine = new TrendRobotEngine(pb, TrendPlaybookSettings.brDefaults());
        var out = engine.kickAwake("unit");
        assertEquals("unit", out.get("reason"));
        assertTrue(Boolean.TRUE.equals(out.get("dayLockCleared")));
        assertEquals(1, engine.kickCountToday());
    }

    private static List<TrendBar> channel() {
        List<TrendBar> bars = new ArrayList<>();
        LocalDateTime t = LocalDateTime.of(2026, 8, 6, 10, 0);
        double lo = 79.50;
        double hi = 80.50;
        for (int i = 0; i < 90; i++) {
            boolean nearLo = (i % 10) < 3;
            boolean nearHi = (i % 10) >= 7;
            double c, h, l;
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
}
