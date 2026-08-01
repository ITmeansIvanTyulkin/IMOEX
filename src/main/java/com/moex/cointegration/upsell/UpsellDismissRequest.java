package com.moex.cointegration.upsell;

/**
 * Dismiss a soft upsell prompt (starts cooldown).
 */
public record UpsellDismissRequest(
        String promptId,
        String featureKey
) {
}
