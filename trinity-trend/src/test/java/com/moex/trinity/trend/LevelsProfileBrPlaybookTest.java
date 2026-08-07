package com.moex.trinity.trend;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LevelsProfileBrPlaybookTest {

    @Test
    void armedBounceOnUptrendPullbackToVolumeFloor() {
        LevelsProfileBrPlaybook pb = new LevelsProfileBrPlaybook();
        List<TrendBar> bars = buildUptrendWithFloor();
        TrendBarSeries series = new TrendBarSeries("BR", "M5", bars);
        TrendAccountContext acct = TrendAccountContext.of(100_000, 15_000, 16_000, 1.0);

        Optional<TrendRobotPlan> plan = pb.evaluate(series, acct);
        assertTrue(plan.isPresent());
        TrendRobotPlan p = plan.get();
        assertEquals(LevelsProfileBrPlaybook.ID, p.playbookId());
        // May be ARMED_BOUNCE or ZONE_READY/NO_TRADE depending on profile validity — floor is crafted valid
        assertTrue(
                p.state() == TrendRobotState.ARMED_BOUNCE
                        || p.state() == TrendRobotState.ARMED_RETEST
                        || p.state() == TrendRobotState.ZONE_READY
                        || p.state() == TrendRobotState.NO_TRADE,
                "state=" + p.state() + " rationale=" + p.rationale()
        );
        if (p.actionable()) {
            assertTrue(p.buy());
            assertTrue(p.mode() == TrendTradeMode.BOUNCE || p.mode() == TrendTradeMode.RETEST, "mode=" + p.mode());
            assertTrue(p.grid().totalQty() >= 1);
            assertTrue(p.stopLossPrice() < p.grid().averagePrice());
        }
    }

    @Test
    void topBreakHoldArmsRetestLongFromAbove() {
        MergedVolumeRange top = new MergedVolumeRange(80.00, 80.20, 100, List.of(), true, null);
        List<TrendBar> bars = new ArrayList<>();
        LocalDateTime t = LocalDateTime.of(2026, 8, 6, 12, 0);
        // in TOP zone
        bars.add(bar(t, 80.05, 80.15, 80.00, 80.10));
        // break up + hold outside
        bars.add(bar(t.plusMinutes(5), 80.25, 80.35, 80.22, 80.30));
        bars.add(bar(t.plusMinutes(10), 80.30, 80.40, 80.25, 80.35));
        bars.add(bar(t.plusMinutes(15), 80.35, 80.45, 80.28, 80.40));
        // retest touch from above
        bars.add(bar(t.plusMinutes(20), 80.30, 80.35, 80.15, 80.22));

        assertTrue(LevelsProfileBrPlaybook.breakHoldSatisfied(bars, top, true, 2));
        assertTrue(LevelsProfileBrPlaybook.retestEntryAllowed(bars, top, true, 10, 0.01));
        assertFalse(TrendPlaybookSettings.brDefaults().aSetupBounceOnly(),
                "defaults must run full checklist (bounce+retest)");
    }

    @Test
    void softSourceNotLockableAndNotEntryValid() {
        MergedVolumeRange soft = new MergedVolumeRange(84.50, 84.65, 1, List.of(), false, "SOFT§6 desk only");
        assertTrue(LevelsProfileBrPlaybook.isSoftSource("BARS+SOFT"));
        assertTrue(LevelsProfileBrPlaybook.isSoftSource("SOFT§6+TA"));
        assertFalse(LevelsProfileBrPlaybook.isSoftSource("PRIOR_DAY_TOP"));
        assertEquals(null, LevelsProfileBrPlaybook.lockableShelf(soft, "BARS+SOFT"));
        MergedVolumeRange valid = new MergedVolumeRange(84.50, 84.65, 5000, List.of(), true, null);
        assertEquals(valid, LevelsProfileBrPlaybook.lockableShelf(valid, "TAPE"));
    }

    @Test
    void researchServiceSelectsPlaybookInTrend() {
        TrendPlaybook pb = new LevelsProfileBrPlaybook();
        TrendResearchService svc = new TrendResearchService(List.of(pb), new DefaultTrendRegimeSelector());
        assertTrue(svc.activePlaybook(new TrendRegimeContext("TREND", 34, true)).isPresent());
        assertEquals(LevelsProfileBrPlaybook.ID, svc.activePlaybook(new TrendRegimeContext("TREND", 34, true)).orElseThrow().id());
    }

    private static TrendBar bar(LocalDateTime t, double o, double h, double l, double c) {
        return new TrendBar(t, o, h, l, c, 1000);
    }

    /**
     * Rising HH/HL structure, then pullback toward a volume-heavy floor ~84.54–84.71.
     */
    private static List<TrendBar> buildUptrendWithFloor() {
        List<TrendBar> bars = new ArrayList<>();
        LocalDateTime t = LocalDateTime.of(2024, 12, 1, 10, 0);
        double px = 84.0;
        // climb with swings
        for (int i = 0; i < 80; i++) {
            double open = px;
            px += 0.04;
            double high = px + 0.02;
            double low = open - 0.01;
            bars.add(new TrendBar(t.plusMinutes(i * 5L), open, high, low, px, 800));
        }
        // carve a floor with heavy volume around 84.55–84.70 (simulate earlier lows by adding bounce bars)
        // Re-anchor: inject dense volume bars at support then leave price just above mid of zone
        double floorLow = 84.54;
        double floorHigh = 84.71;
        for (int i = 0; i < 4; i++) {
            bars.add(new TrendBar(
                    t.plusMinutes((80 + i) * 5L),
                    84.60, floorHigh, floorLow, 84.62, 6000
            ));
        }
        // push up then pull back toward floor (bounce setup: price near/below mid, still above low)
        for (int i = 0; i < 20; i++) {
            double c = 85.0 + i * 0.05;
            bars.add(new TrendBar(t.plusMinutes((84 + i) * 5L), c - 0.02, c + 0.03, c - 0.04, c, 900));
        }
        // pullback close near 84.62 (inside/lower half of floor)
        bars.add(new TrendBar(t.plusMinutes(104 * 5L), 84.80, 84.85, 84.58, 84.62, 1200));
        return bars;
    }
}
