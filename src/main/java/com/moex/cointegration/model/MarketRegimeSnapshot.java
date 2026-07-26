package com.moex.cointegration.model;

/**
 * Снимок рыночного режима для UI и гейтов.
 */
public record MarketRegimeSnapshot(
        double adx,
        String label,
        boolean blockEntries,
        boolean reduceSize,
        String detail
) {
    public static MarketRegimeSnapshot unknown() {
        return new MarketRegimeSnapshot(Double.NaN, "UNKNOWN", false, false,
                "ADX недоступен — режим не определён, mean-reversion разрешена с осторожностью");
    }

    public static MarketRegimeSnapshot of(double adx, double reduceTh, double blockTh) {
        if (Double.isNaN(adx)) {
            return unknown();
        }
        if (adx >= blockTh) {
            return new MarketRegimeSnapshot(adx, "TREND", true, false,
                    String.format(
                            "ADX=%.1f ≥ %.0f — выявлен тренд. Стратегия только боковик: не торговать",
                            adx, blockTh));
        }
        if (adx >= reduceTh) {
            return new MarketRegimeSnapshot(adx, "NEUTRAL", false, true,
                    String.format(
                            "ADX=%.1f — переходный режим: входы разрешены, размер уменьшен",
                            adx));
        }
        return new MarketRegimeSnapshot(adx, "SIDEWAYS", false, false,
                String.format(
                        "ADX=%.1f < %.0f — боковик: mean-reversion активна",
                        adx, reduceTh));
    }
}
