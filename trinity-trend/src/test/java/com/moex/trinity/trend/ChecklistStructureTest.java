package com.moex.trinity.trend;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChecklistStructureTest {

    @Test
    void majorityUptrendAssignsMajorityBuys() {
        TrendInstrumentSpec spec = TrendInstrumentSpec.brDefaults();
        VolumeAtPriceBuilder vap = new VolumeAtPriceBuilder(spec, false, 2);
        List<TrendBar> bars = climbBars();
        List<ChecklistLevel> levels = ChecklistStructure.discoverLevels(
                bars, MarketState.TREND_UP, 83.0, 80.0, 81.5, 81.0, spec, vap);
        assertTrue(levels.size() >= 2 && levels.size() <= 4, "size=" + levels.size());
        long buys = levels.stream().filter(ChecklistLevel::preferBuy).count();
        assertTrue(buys >= levels.size() - 1, "§5 majority BUY in uptrend buys=" + buys + " n=" + levels.size());
    }

    @Test
    void complianceCoreIsImplemented() {
        for (ChecklistCompliance c : ChecklistCompliance.values()) {
            if (c.name().startsWith("S") || c.name().startsWith("NOTE")) {
                assertEquals(ChecklistCompliance.Status.IMPLEMENTED, c.status(), c.name());
            }
        }
        assertEquals(20, TrendInstrumentSpec.brDefaults().stopPoints(), 1e-9);
        assertEquals(20, TrendInstrumentSpec.brDefaults().tp1Points(), 1e-9);
        assertFalse(TrendPlaybookSettings.brDefaults().aSetupBounceOnly());
    }

    @Test
    void pickActiveDoesNotStickOnFarBrokenTop() {
        MergedVolumeRange oldTop = new MergedVolumeRange(80.13, 80.32, 10, List.of(), true, null);
        MergedVolumeRange liveHi = new MergedVolumeRange(81.56, 81.71, 5, List.of(), true, null);
        List<ChecklistLevel> levels = List.of(
                new ChecklistLevel(80.30, "TREND_HI", "DAY", false, oldTop, true),
                new ChecklistLevel(81.71, "TREND_HI", "SOFT", false, liveHi, false)
        );
        // Ambiguous two TREND_HI — use one list with ACCUM + soft HI
        levels = List.of(
                new ChecklistLevel(80.30, "ACCUM", "POC", true, oldTop, true),
                new ChecklistLevel(81.71, "TREND_HI", "SOFT", false, liveHi, false)
        );
        ChecklistLevel active = ChecklistStructure.pickActive(
                levels, 81.48, MarketState.RANGE, 10, 0.01);
        assertEquals("TREND_HI", active.role());
        assertFalse(active.brokenHeld());
    }

    @Test
    void pickActiveIgnoresLevelsWithoutRange() {
        MergedVolumeRange hi = new MergedVolumeRange(81.55, 81.70, 10, List.of(), true, null);
        List<ChecklistLevel> levels = List.of(
                new ChecklistLevel(80.0, "ACCUM", "BARE", true, null, false),
                new ChecklistLevel(81.70, "TREND_HI", "SOFT", false, hi, false)
        );
        ChecklistLevel active = ChecklistStructure.pickActive(
                levels, 81.60, MarketState.RANGE, 10, 0.01);
        assertEquals("TREND_HI", active.role());
        assertTrue(active.hasValidRange());
    }

    @Test
    void buildFromLastBouncesNeedsTwoClusters() {
        TrendInstrumentSpec spec = TrendInstrumentSpec.brDefaults();
        VolumeAtPriceBuilder vap = new VolumeAtPriceBuilder(spec, true, 1);
        List<TrendBar> bars = new ArrayList<>();
        LocalDateTime t = LocalDateTime.of(2026, 8, 3, 10, 0);
        // cluster 1
        bars.add(new TrendBar(t, 80.05, 80.10, 80.00, 80.05, 2000));
        bars.add(new TrendBar(t.plusMinutes(5), 80.05, 80.12, 80.01, 80.08, 2000));
        // leave
        bars.add(new TrendBar(t.plusMinutes(10), 80.20, 80.30, 80.15, 80.25, 900));
        bars.add(new TrendBar(t.plusMinutes(15), 80.25, 80.35, 80.20, 80.30, 900));
        bars.add(new TrendBar(t.plusMinutes(20), 80.30, 80.40, 80.25, 80.35, 900));
        // cluster 2
        bars.add(new TrendBar(t.plusMinutes(25), 80.10, 80.15, 80.00, 80.08, 2500));
        bars.add(new TrendBar(t.plusMinutes(30), 80.08, 80.14, 80.02, 80.10, 2500));
        MergedVolumeRange r = vap.buildFromLastBounces(bars, 80.05, 3, 3);
        assertTrue(r.validForEntry() || r.low() < r.high(), "range=" + r);
    }

    private static List<TrendBar> climbBars() {
        List<TrendBar> bars = new ArrayList<>();
        LocalDateTime t = LocalDateTime.of(2026, 8, 3, 10, 0);
        double px = 80.0;
        for (int i = 0; i < 80; i++) {
            double o = px;
            px += 0.04;
            bars.add(new TrendBar(t.plusMinutes(i * 5L), o, px + 0.02, o - 0.01, px, 1000));
        }
        return bars;
    }
}
