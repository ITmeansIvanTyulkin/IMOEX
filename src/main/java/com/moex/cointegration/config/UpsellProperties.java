package com.moex.cointegration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Soft commercial upsell (Full Core / calendar arbitrage). Scaffolding only — no billing.
 */
@ConfigurationProperties(prefix = "imoex.upsell")
public record UpsellProperties(
        Boolean enabled,
        Integer fullPriceRub,
        Integer cooldownHours,
        Integer minEventsBeforePrompt,
        Integer minDashboardViews
) {
    public UpsellProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (fullPriceRub == null || fullPriceRub < 1) {
            fullPriceRub = 15_000;
        }
        if (cooldownHours == null || cooldownHours < 1) {
            cooldownHours = 168; // 7 days
        }
        if (minEventsBeforePrompt == null || minEventsBeforePrompt < 0) {
            minEventsBeforePrompt = 3;
        }
        if (minDashboardViews == null || minDashboardViews < 1) {
            minDashboardViews = 2;
        }
    }

    public static UpsellProperties defaults() {
        return new UpsellProperties(true, 15_000, 168, 3, 2);
    }

    public boolean enabledFlag() {
        return Boolean.TRUE.equals(enabled);
    }
}
