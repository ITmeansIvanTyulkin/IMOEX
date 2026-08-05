package com.moex.trinity.trend;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Executable robot order plan produced by a trend playbook.
 */
public record TrendRobotPlan(
        String playbookId,
        String instrument,
        String timeframe,
        LocalDateTime createdAt,
        TrendRobotState state,
        TrendTradeMode mode,
        boolean buy,
        MergedVolumeRange range,
        LimitGridPlan grid,
        double stopLossPrice,
        double tp1Price,
        double tp2Price,
        double tp1Fraction,
        String rationale,
        List<String> notes
) {
    public TrendRobotPlan {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public boolean actionable() {
        return grid != null && grid.totalQty() > 0
                && state != TrendRobotState.NO_TRADE
                && state != TrendRobotState.ABORT
                && state != TrendRobotState.SCAN;
    }
}
