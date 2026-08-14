package com.moex.cointegration.upsell;

import java.time.Instant;

/**
 * Snapshot of Full Core commercial access (reverse trial + locks). No billing.
 */
public record UpsellAccess(
        boolean enabled,
        /** Trial active → hide hard locks; show trial banner. */
        boolean hasFullCoreAccess,
        /** OFF | TRIAL | LOCKED | EXPIRED */
        String phase,
        Instant trialStartedAt,
        Instant trialEndsAt,
        Integer daysRemaining,
        int overviewPriceRub,
        int operatorPriceRub,
        int fullPriceRub
) {
    public boolean locksVisible() {
        return enabled && !hasFullCoreAccess;
    }
}
