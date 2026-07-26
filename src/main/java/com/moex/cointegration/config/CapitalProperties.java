package com.moex.cointegration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Капитал оператора: без плеча до порога.
 */
@ConfigurationProperties(prefix = "imoex.capital")
public record CapitalProperties(
        Double equityRub,
        Double allowLeverageAboveRub,
        Double maxGrossWhenNoLeverage
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
    }

    public static CapitalProperties defaults() {
        return new CapitalProperties(100_000.0, 1_000_000.0, 1.0);
    }

    public boolean leverageAllowed() {
        return equityRub >= allowLeverageAboveRub;
    }

    /** Максимальный суммарный notional (обе ноги) при текущем equity. */
    public double maxGrossNotional() {
        double mult = leverageAllowed() ? Math.max(1.0, maxGrossWhenNoLeverage) : maxGrossWhenNoLeverage;
        return equityRub * mult;
    }
}
