package com.moex.cointegration.model;

public record BrokerSettingsView(
        boolean enabled,
        String provider,
        String mode,
        boolean sandbox,
        boolean tokenConfigured,
        String maskedToken,
        String accountId,
        boolean autoExecuteAfterAnalysis,
        boolean preferLimitOrders,
        boolean allowMarketFallback,
        boolean emergencyMarketExitEnabled,
        double passivePriceOffsetBps,
        int secondLegTimeoutSeconds,
        double maxLegDriftBps,
        boolean killSwitch
) {
}
