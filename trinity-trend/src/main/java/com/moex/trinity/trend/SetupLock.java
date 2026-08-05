package com.moex.trinity.trend;

import java.time.LocalDate;

/**
 * Active setup lock: one volume zone → one armed plan until unlock.
 */
public record SetupLock(
        LocalDate day,
        double zoneLow,
        double zoneHigh,
        boolean buy,
        TrendTradeMode mode,
        TrendRobotPlan plan
) {
    public double mid() {
        return (zoneLow + zoneHigh) / 2.0;
    }

    public boolean sameZone(MergedVolumeRange range, double pointSize) {
        if (range == null || !range.validForEntry()) {
            return false;
        }
        double tol = pointSize * 3;
        return Math.abs(range.low() - zoneLow) <= tol && Math.abs(range.high() - zoneHigh) <= tol;
    }
}
