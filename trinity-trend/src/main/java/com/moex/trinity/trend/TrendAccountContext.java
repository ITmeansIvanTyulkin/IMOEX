package com.moex.trinity.trend;

/**
 * Account inputs for robot sizing (equity + ГО).
 */
public record TrendAccountContext(
        double equityRub,
        double goLongRub,
        double goShortRub,
        double maxRiskPctEquity
) {
    public static TrendAccountContext of(double equityRub, double goLongRub, double goShortRub, double maxRiskPct) {
        return new TrendAccountContext(equityRub, goLongRub, goShortRub, maxRiskPct);
    }
}
