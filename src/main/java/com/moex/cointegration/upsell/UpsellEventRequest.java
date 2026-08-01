package com.moex.cointegration.upsell;

/**
 * Client beacon payload for {@code POST /api/upsell/events}.
 */
public record UpsellEventRequest(
        String action,
        String page,
        String tierHint
) {
}
