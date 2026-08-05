package com.moex.cointegration.model;

public record BrokerSettingsUpdateRequest(
        Boolean enabled,
        String provider,
        String mode,
        Boolean sandbox,
        String token,
        String accountId,
        Boolean autoExecuteAfterAnalysis,
        Boolean preferLimitOrders,
        Boolean allowMarketFallback,
        Boolean emergencyMarketExitEnabled,
        Double passivePriceOffsetBps,
        Integer secondLegTimeoutSeconds,
        Double maxLegDriftBps,
        Boolean killSwitch
) {
}
