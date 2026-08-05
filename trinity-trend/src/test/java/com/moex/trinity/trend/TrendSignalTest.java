package com.moex.trinity.trend;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrendSignalTest {

    @Test
    void fromActionablePlanExposesTickerAndSide() {
        LimitGridPlan grid = new LimitGridPlan(LimitGridStyle.MODERATE, 84.71, 2, 84.62, 2, 84.54, 2, true);
        MergedVolumeRange range = new MergedVolumeRange(84.54, 84.71, 100, List.of(), true, null);
        TrendRobotPlan plan = new TrendRobotPlan(
                LevelsProfileBrPlaybook.ID, "BR", "M5", LocalDateTime.now(),
                TrendRobotState.ARMED_BOUNCE, TrendTradeMode.BOUNCE, true,
                range, grid, 84.40, 84.90, 85.10, 1.0 / 3.0,
                "bounce long", List.of()
        );
        TrendSignal s = TrendSignal.from(plan);
        assertEquals("BR", s.ticker());
        assertEquals("BUY", s.side());
        assertEquals("BOUNCE", s.mode());
        assertTrue(s.actionable());
        assertTrue(s.summary().contains("BR"));
    }
}
