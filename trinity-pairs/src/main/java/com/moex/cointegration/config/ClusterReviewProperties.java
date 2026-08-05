package com.moex.cointegration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ежемесячный пересмотр кластеров пар: sector cash PnL / PF за lookback.
 * Нефть (OIL_GAS) для DAILY pairs выключена — roadmap фьючерсы/опционы.
 */
@ConfigurationProperties(prefix = "imoex.cluster-review")
public record ClusterReviewProperties(
        Boolean enabled,
        Integer lookbackMonths,
        Double minProfitFactor,
        Integer minClosedTrades,
        Boolean excludeOilGas
) {
    public ClusterReviewProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (lookbackMonths == null || lookbackMonths < 1) {
            lookbackMonths = 6;
        }
        if (minProfitFactor == null || minProfitFactor < 0) {
            minProfitFactor = 1.10;
        }
        if (minClosedTrades == null || minClosedTrades < 1) {
            minClosedTrades = 4;
        }
        if (excludeOilGas == null) {
            excludeOilGas = true;
        }
    }

    public static ClusterReviewProperties defaults() {
        return new ClusterReviewProperties(true, 6, 1.10, 4, true);
    }

    public boolean enabledFlag() {
        return Boolean.TRUE.equals(enabled);
    }

    public boolean excludeOilGasFlag() {
        return Boolean.TRUE.equals(excludeOilGas);
    }
}
