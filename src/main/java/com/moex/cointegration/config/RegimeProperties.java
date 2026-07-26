package com.moex.cointegration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Режим рынка для mean-reversion: блок входов в тренде (ADX).
 */
@ConfigurationProperties(prefix = "imoex.regime")
public record RegimeProperties(
        Boolean enabled,
        Integer adxPeriod,
        Double adxReduce,
        Double adxBlock,
        Double reduceFactor,
        String indexBoard
) {
    public RegimeProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (adxPeriod == null || adxPeriod < 2) {
            adxPeriod = 14;
        }
        if (adxReduce == null) {
            adxReduce = 20.0;
        }
        if (adxBlock == null) {
            adxBlock = 25.0;
        }
        if (reduceFactor == null || reduceFactor <= 0 || reduceFactor > 1) {
            reduceFactor = 0.5;
        }
        if (indexBoard == null || indexBoard.isBlank()) {
            indexBoard = "SNDX";
        }
    }

    public static RegimeProperties defaults() {
        return new RegimeProperties(true, 14, 20.0, 25.0, 0.5, "SNDX");
    }

    public boolean enabledFlag() {
        return Boolean.TRUE.equals(enabled);
    }
}
