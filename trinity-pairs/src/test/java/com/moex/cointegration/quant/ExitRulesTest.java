package com.moex.cointegration.quant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExitRulesTest {

    @Test
    void halfwayLongAndShort() {
        assertTrue(ExitRules.halfwayToZero(-2.4, -1.2, 0.5));
        assertFalse(ExitRules.halfwayToZero(-2.4, -1.8, 0.5));
        assertTrue(ExitRules.halfwayToZero(2.4, 1.2, 0.5));
        assertFalse(ExitRules.halfwayToZero(2.4, 1.9, 0.5));
    }

    @Test
    void trailingFromBest() {
        assertTrue(ExitRules.trailStopHit(true, -0.5, -1.4, 0.75));
        assertFalse(ExitRules.trailStopHit(true, -0.5, -0.8, 0.75));
        assertTrue(ExitRules.trailStopHit(false, 0.5, 1.4, 0.75));
    }

    @Test
    void betaAndPBreak() {
        assertTrue(ExitRules.betaBreak(1.0, 1.4, 0.35));
        assertFalse(ExitRules.betaBreak(1.0, 1.2, 0.35));
        assertTrue(ExitRules.cointegrationBroken(0.25, 0.20));
        assertFalse(ExitRules.cointegrationBroken(0.05, 0.20));
    }
}
