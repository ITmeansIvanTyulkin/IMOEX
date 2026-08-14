package com.moex.cointegration.upsell;

/**
 * Optional soft upsell nudge shown in the operator UI (non-blocking).
 */
public record UpsellPrompt(
        String id,
        String title,
        String body,
        String ctaLabel,
        String ctaHref,
        String targetTier,
        String featureKey
) {
}
