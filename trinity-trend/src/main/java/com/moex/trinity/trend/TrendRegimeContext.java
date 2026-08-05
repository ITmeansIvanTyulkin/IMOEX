package com.moex.trinity.trend;

/**
 * Regime hint for playbook selection.
 * {@code preferredPlaybookId} — config hint ({@code imoex.strategies.trend.playbook}); may be null.
 */
public record TrendRegimeContext(
        String regimeLabel,
        double adx,
        boolean volumeExpansion,
        String preferredPlaybookId
) {
    public TrendRegimeContext(String regimeLabel, double adx, boolean volumeExpansion) {
        this(regimeLabel, adx, volumeExpansion, null);
    }

    public static TrendRegimeContext unknown() {
        return new TrendRegimeContext("UNKNOWN", 0, false, null);
    }
}
