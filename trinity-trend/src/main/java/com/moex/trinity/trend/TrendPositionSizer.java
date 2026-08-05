package com.moex.trinity.trend;

/**
 * Position sizing: min(risk budget, GO capacity), never «весь депозит / ГО» alone.
 */
public final class TrendPositionSizer {

    private TrendPositionSizer() {
    }

    public static int sizeContracts(
            TrendAccountContext account,
            TrendInstrumentSpec spec,
            boolean buy,
            double stopPoints
    ) {
        if (account == null || spec == null || account.equityRub() <= 0) {
            return 0;
        }
        double go = buy ? account.goLongRub() : account.goShortRub();
        if (go <= 0) {
            return 0;
        }
        int byGo = (int) Math.floor(account.equityRub() / go);

        double riskPct = account.maxRiskPctEquity() > 0 ? account.maxRiskPctEquity() : 1.0;
        double riskRub = account.equityRub() * (riskPct / 100.0);
        double stopPts = stopPoints > 0 ? stopPoints : spec.stopPoints();
        double rubPerPoint = spec.rubPerPoint() > 0 ? spec.rubPerPoint() : 7.0;
        double riskPerContract = stopPts * rubPerPoint;
        int byRisk = riskPerContract <= 0 ? 0 : (int) Math.floor(riskRub / riskPerContract);

        return Math.max(0, Math.min(byGo, byRisk));
    }
}
