package com.moex.cointegration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Контур брокера для live/assist исполнения парных сделок.
 * По умолчанию выключен до появления токена.
 */
@ConfigurationProperties(prefix = "imoex.broker")
public record BrokerProperties(
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
    public BrokerProperties {
        if (enabled == null) {
            enabled = false;
        }
        if (provider == null || provider.isBlank()) {
            provider = "T_INVEST";
        }
        if (mode == null || mode.isBlank()) {
            mode = "AUTO";
        }
        if (sandbox == null) {
            sandbox = true;
        }
        if (autoExecuteAfterAnalysis == null) {
            autoExecuteAfterAnalysis = true;
        }
        if (preferLimitOrders == null) {
            preferLimitOrders = true;
        }
        if (allowMarketFallback == null) {
            allowMarketFallback = false;
        }
        if (emergencyMarketExitEnabled == null) {
            emergencyMarketExitEnabled = false;
        }
        if (passivePriceOffsetBps == null || passivePriceOffsetBps < 0) {
            passivePriceOffsetBps = 15.0;
        }
        if (secondLegTimeoutSeconds == null || secondLegTimeoutSeconds < 1) {
            secondLegTimeoutSeconds = 60;
        }
        if (maxLegDriftBps == null || maxLegDriftBps < 0) {
            maxLegDriftBps = 35.0;
        }
        if (killSwitch == null) {
            killSwitch = false;
        }
    }

    public static BrokerProperties defaults() {
        return new BrokerProperties(false, "T_INVEST", "AUTO", true, "", "",
                true, true, false, false, 15.0, 60, 35.0, false);
    }

    public boolean enabledFlag() {
        return Boolean.TRUE.equals(enabled);
    }

    public boolean sandboxFlag() {
        return Boolean.TRUE.equals(sandbox);
    }

    public boolean autoExecuteAfterAnalysisFlag() {
        return Boolean.TRUE.equals(autoExecuteAfterAnalysis);
    }

    public boolean preferLimitOrdersFlag() {
        return Boolean.TRUE.equals(preferLimitOrders);
    }

    public boolean allowMarketFallbackFlag() {
        return Boolean.TRUE.equals(allowMarketFallback);
    }

    public boolean emergencyMarketExitEnabledFlag() {
        return Boolean.TRUE.equals(emergencyMarketExitEnabled);
    }

    public boolean killSwitchEnabled() {
        return Boolean.TRUE.equals(killSwitch);
    }
}
