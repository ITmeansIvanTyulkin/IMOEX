package com.moex.cointegration.model;

import java.time.LocalDateTime;

/**
 * Unified operator toast payload for dashboard polling (pairs + trend).
 */
public record OperatorTradeToast(
        String id,
        String strategy,
        String kind,
        String title,
        String summary,
        String instrument,
        String side,
        String book,
        Double pnlRub,
        Double potentialPnlRub,
        LocalDateTime at,
        String href
) {
}
