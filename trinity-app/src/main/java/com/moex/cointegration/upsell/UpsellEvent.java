package com.moex.cointegration.upsell;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Operator action beacon for soft upsell heuristics.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpsellEvent(
        String action,
        String page,
        Instant timestamp,
        String tierHint
) {
}
