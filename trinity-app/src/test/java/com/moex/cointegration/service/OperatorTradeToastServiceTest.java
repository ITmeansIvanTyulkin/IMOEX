package com.moex.cointegration.service;

import com.moex.cointegration.model.OperatorTradeToast;
import com.moex.trinity.trend.LimitGridPlan;
import com.moex.trinity.trend.LimitGridStyle;
import com.moex.trinity.trend.LevelsProfileBrPlaybook;
import com.moex.trinity.trend.MergedVolumeRange;
import com.moex.trinity.trend.TrendRobotPlan;
import com.moex.trinity.trend.TrendRobotState;
import com.moex.trinity.trend.TrendTradeMode;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperatorTradeToastServiceTest {

    @Test
    void recordsTrendSignalOncePerDedupeKey() {
        OperatorTradeToastService svc = new OperatorTradeToastService(null, 0.01, 7.0);
        TrendRobotPlan plan = samplePlan();
        svc.recordTrendSignal(plan);
        svc.recordTrendSignal(plan);
        List<OperatorTradeToast> toasts = svc.recentToasts();
        assertEquals(1, toasts.size());
        assertEquals("SIGNAL", toasts.get(0).kind());
        assertEquals("TREND", toasts.get(0).strategy());
        assertTrue(toasts.get(0).potentialPnlRub() != null && toasts.get(0).potentialPnlRub() > 0);
    }

    @Test
    void recordsTrendEntryWithPotential() {
        OperatorTradeToastService svc = new OperatorTradeToastService(null, 0.01, 7.0);
        svc.recordTrendEntry(samplePlan());
        List<OperatorTradeToast> toasts = svc.recentToasts();
        assertEquals(1, toasts.size());
        assertEquals("ENTRY", toasts.get(0).kind());
        assertTrue(toasts.get(0).title().contains("вход"));
    }

    private static TrendRobotPlan samplePlan() {
        LimitGridPlan grid = new LimitGridPlan(LimitGridStyle.MODERATE, 84.71, 2, 84.62, 2, 84.54, 2, true);
        MergedVolumeRange range = new MergedVolumeRange(84.54, 84.71, 100, List.of(), true, null);
        return new TrendRobotPlan(
                LevelsProfileBrPlaybook.ID, "BR", "M5", LocalDateTime.now(),
                TrendRobotState.ARMED_BOUNCE, TrendTradeMode.BOUNCE, true,
                range, grid, 84.40, 84.90, 85.10, 1.0 / 3.0,
                "bounce long", List.of()
        );
    }
}
