package com.moex.trinity.trend;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FairPaperSimulatorTest {

    @Test
    void tryOpenFillsWhenBarTradesThroughNearLimit() {
        MergedVolumeRange range = new MergedVolumeRange(79.80, 80.00, 1000, List.of(), true, null);
        LimitGridPlan grid = LimitGridBuilder.build(range, true, 2, LimitGridStyle.MODERATE);
        TrendRobotPlan plan = new TrendRobotPlan(
                LevelsProfileBrPlaybook.ID,
                "BRU6",
                "M5",
                LocalDateTime.of(2026, 8, 7, 12, 0),
                TrendRobotState.ARMED_RETEST,
                TrendTradeMode.RETEST,
                true,
                range,
                grid,
                79.78,
                80.22,
                80.44,
                1.0 / 3.0,
                "test",
                List.of()
        );
        // near=high=80.00 for buy — bar must trade through 80.00
        TrendBar bar = new TrendBar(LocalDateTime.of(2026, 8, 7, 12, 0), 80.05, 80.10, 79.90, 80.00, 100);
        FairPaperSimulator.OpenPaper open = FairPaperSimulator.tryOpen(plan, bar);
        assertNotNull(open);
        assertTrue(open.qty >= 1);
        assertTrue(open.buy);
    }

    @Test
    void manageHitsStopOnNextBar() {
        FairPaperSimulator.OpenPaper open = new FairPaperSimulator.OpenPaper(
                LocalDateTime.of(2026, 8, 7, 12, 0),
                true,
                "RETEST",
                "BRU6",
                80.00,
                1,
                79.78,
                80.22,
                80.44,
                1.0 / 3.0,
                false,
                true,
                0.0
        );
        TrendBar fillBar = new TrendBar(LocalDateTime.of(2026, 8, 7, 12, 0), 80.00, 80.05, 79.95, 80.02, 100);
        assertNull(FairPaperSimulator.manage(open, fillBar, 7.0, 0.01));
        TrendBar slBar = new TrendBar(LocalDateTime.of(2026, 8, 7, 12, 5), 79.90, 79.95, 79.70, 79.75, 100);
        FairPaperSimulator.ExitResult er = FairPaperSimulator.manage(open, slBar, 7.0, 0.01);
        assertNotNull(er);
        assertEquals("SL", er.reason());
        assertTrue(er.pnl() < 0);
    }

    @Test
    void manageHitsTp2AfterTp1SamePath() {
        FairPaperSimulator.OpenPaper open = new FairPaperSimulator.OpenPaper(
                LocalDateTime.of(2026, 8, 7, 12, 0),
                true,
                "RETEST",
                "BRU6",
                80.00,
                3,
                79.78,
                80.22,
                80.44,
                1.0 / 3.0,
                false,
                false,
                0.0
        );
        TrendBar tpBar = new TrendBar(LocalDateTime.of(2026, 8, 7, 12, 5), 80.10, 80.50, 80.05, 80.45, 100);
        FairPaperSimulator.ExitResult er = FairPaperSimulator.manage(open, tpBar, 7.0, 0.01);
        assertNotNull(er);
        assertEquals("TP2", er.reason());
        assertTrue(er.pnl() > 0);
    }
}
