package com.moex.cointegration.config;

/**
 * Слоты и gross-лимиты по книгам DAILY / INTRADAY от equity.
 * Без плеча при equity &lt; allowLeverageAboveRub: сумма gross обеих книг ≤ equity.
 */
public final class CapitalAllocator {

    private CapitalAllocator() {
    }

    public record Allocation(
            double equityRub,
            int dailyMaxPairs,
            int intradayMaxPairs,
            double dailyGrossCap,
            double intradayGrossCap,
            boolean leverageAllowed
    ) {
    }

    public static Allocation allocate(CapitalProperties capital) {
        double equity = capital.equityRub();
        boolean lev = capital.leverageAllowed();
        double totalGross = capital.maxGrossNotional();

        double dailyShare = capital.dailyGrossShare();
        double intradayShare = capital.intradayGrossShare();
        double sum = dailyShare + intradayShare;
        if (sum <= 0) {
            dailyShare = 1.0;
            intradayShare = 0.0;
            sum = 1.0;
        }
        dailyShare /= sum;
        intradayShare /= sum;

        int dailyMax = dailyShare <= 0 ? 0 : clamp((int) Math.floor(equity / 100_000.0), 1, 2);
        int intradayMax = intradayShare <= 0 ? 0 : clamp((int) Math.floor(equity / 100_000.0) + 1, 1, 3);

        return new Allocation(
                equity,
                dailyMax,
                intradayMax,
                totalGross * dailyShare,
                totalGross * intradayShare,
                lev
        );
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
