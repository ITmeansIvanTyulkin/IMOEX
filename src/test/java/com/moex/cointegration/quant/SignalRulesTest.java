package com.moex.cointegration.quant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalRulesTest {

    @Test
    void thresholdModeEntersOnFirstCross() {
        assertTrue(SignalRules.confirmShortEntry(1.9, 2.1, 2.0, false));
        assertFalse(SignalRules.confirmShortEntry(2.5, 2.6, 2.0, false)); // already above, no cross
    }

    @Test
    void reversalModeWaitsForTurnFromExtreme() {
        // First touch going up — no entry yet
        assertFalse(SignalRules.confirmShortEntry(1.9, 2.5, 2.0, true));
        // Still expanding — no entry
        assertFalse(SignalRules.confirmShortEntry(2.5, 3.0, 2.0, true));
        // Turning down while above entry — enter
        assertTrue(SignalRules.confirmShortEntry(3.0, 2.7, 2.0, true));
        // Already crossed through zero — too late
        assertFalse(SignalRules.confirmShortEntry(3.0, -0.1, 2.0, true));
    }

    @Test
    void longReversalSymmetry() {
        assertFalse(SignalRules.confirmLongEntry(-1.9, -2.5, 2.0, true));
        assertTrue(SignalRules.confirmLongEntry(-2.8, -2.4, 2.0, true));
        assertFalse(SignalRules.confirmLongEntry(-2.8, 0.16, 2.0, true));
    }
}
