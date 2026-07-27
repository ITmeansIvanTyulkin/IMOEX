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

        int dailyMax = clamp((int) Math.floor(equity / 100_000.0), 1, 2);
        int intradayMax = clamp((int) Math.floor(equity / 100_000.0) + 1, 1, 3);

        double dailyShare = capital.dailyGrossShare();
        double intradayShare = capital.intradayGrossShare();
        double sum = dailyShare + intradayShare;
        if (sum <= 0) {
            dailyShare = 0.40;
            intradayShare = 0.60;
            sum = 1.0;
        }
        dailyShare /= sum;
        intradayShare /= sum;

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
