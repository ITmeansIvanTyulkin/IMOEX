package com.moex.trinity.trend;

/**
 * Minimal regime hint for playbook selection (expand later: ADX, volume regime, range vs breakout).
 */
public record TrendRegimeContext(
        String regimeLabel,
        double adx,
        boolean volumeExpansion
) {
    public static TrendRegimeContext unknown() {
        return new TrendRegimeContext("UNKNOWN", 0, false);
    }
}
