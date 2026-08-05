package com.moex.trinity.trend;

/**
 * Speculative zone / stop tables from Exclusive checklist (points).
 */
public record TrendInstrumentSpec(
        String code,
        double pointSize,
        double zoneMinPoints,
        double zoneMaxPoints,
        double stopPoints,
        double tp1Points,
        double rubPerPoint
) {
    /** Brent oil futures — primary playbook instrument. */
    public static TrendInstrumentSpec br(double zoneMin, double zoneMax, double stop, double tp1, double rubPerPoint) {
        return new TrendInstrumentSpec("BR", 0.01, zoneMin, zoneMax, stop, tp1, rubPerPoint);
    }

    public static TrendInstrumentSpec brDefaults() {
        return br(15, 20, 15, 25, 7.0);
    }
}