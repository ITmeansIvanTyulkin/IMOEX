package com.moex.cointegration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Капитал оператора: без плеча до порога; dual-book split daily/intraday.
 */
@ConfigurationProperties(prefix = "imoex.capital")
public record CapitalProperties(
        Double equityRub,
        Double allowLeverageAboveRub,
        Double maxGrossWhenNoLeverage,
        Double dailyGrossShare,
        Double intradayGrossShare
) {
    public CapitalProperties {
        if (equityRub == null || equityRub <= 0) {
            equityRub = 100_000.0;
        }
        if (allowLeverageAboveRub == null || allowLeverageAboveRub <= 0) {
            allowLeverageAboveRub = 1_000_000.0;
        }
        if (maxGrossWhenNoLeverage == null || maxGrossWhenNoLeverage <= 0) {
            maxGrossWhenNoLeverage = 1.0;
        }
        if (dailyGrossShare == null || dailyGrossShare < 0) {
            dailyGrossShare = 1.0;
        }
        if (intradayGrossShare == null || intradayGrossShare < 0) {
            // 0 = INTRADAY research-only (капитал на DAILY)
            intradayGrossShare = 0.0;
        }
    }

    public static CapitalProperties defaults() {
        return new CapitalProperties(100_000.0, 1_000_000.0, 1.0, 1.0, 0.0);
    }

    public boolean leverageAllowed() {
        return equityRub >= allowLeverageAboveRub;
    }

    /** Максимальный суммарный notional (обе книги) при текущем equity. */
    public double maxGrossNotional() {
        double mult = leverageAllowed() ? Math.max(1.0, maxGrossWhenNoLeverage) : maxGrossWhenNoLeverage;
        return equityRub * mult;
    }

    public CapitalAllocator.Allocation allocation() {
        return CapitalAllocator.allocate(this);
    }
}
