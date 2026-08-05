package com.moex.cointegration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Soft commercial upsell (Full Core / calendar arbitrage). Scaffolding only — no billing.
 */
@ConfigurationProperties(prefix = "imoex.upsell")
public record UpsellProperties(
        Boolean enabled,
        Integer overviewPriceRub,
        Integer operatorPriceRub,
        Integer fullPriceRub,
        Integer cooldownHours,
        Integer minEventsBeforePrompt,
        Integer minDashboardViews,
        Integer reverseTrialDays,
        Boolean autoStartReverseTrial,
        /** Local/dev: force Full Core trial as active. */
        Boolean simulateTrialActive,
        /** Local/dev: force trial expired (locks on). */
        Boolean simulateTrialExpired
) {
    public UpsellProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (overviewPriceRub == null || overviewPriceRub < 1) {
            overviewPriceRub = 5_000;
        }
        if (operatorPriceRub == null || operatorPriceRub < 1) {
            operatorPriceRub = 7_500;
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
        if (reverseTrialDays == null || reverseTrialDays < 1) {
            reverseTrialDays = 14;
        }
        if (autoStartReverseTrial == null) {
            autoStartReverseTrial = true;
        }
        if (simulateTrialActive == null) {
            simulateTrialActive = false;
        }
        if (simulateTrialExpired == null) {
            simulateTrialExpired = false;
        }
    }

    public static UpsellProperties defaults() {
        return new UpsellProperties(
                true, 5_000, 7_500, 15_000, 168, 3, 2, 14, true, false, false
        );
    }

    public boolean enabledFlag() {
        return Boolean.TRUE.equals(enabled);
    }

    public boolean autoStartReverseTrialFlag() {
        return Boolean.TRUE.equals(autoStartReverseTrial);
    }

    public boolean simulateTrialActiveFlag() {
        return Boolean.TRUE.equals(simulateTrialActive);
    }

    public boolean simulateTrialExpiredFlag() {
        return Boolean.TRUE.equals(simulateTrialExpired);
    }
}
