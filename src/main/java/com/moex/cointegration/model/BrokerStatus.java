package com.moex.cointegration.model;

/**
 * Краткий статус broker-слоя для UI/API.
 */
public record BrokerStatus(
        boolean enabled,
        String provider,
        BrokerMode mode,
        boolean sandbox,
        boolean tokenPresent,
        boolean accountConfigured,
        boolean killSwitch,
        boolean autoExecuteAfterAnalysis,
        String summary
) {
}
