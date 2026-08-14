package com.moex.trinity.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StrategyIdTest {

    @Test
    void threePillars() {
        assertEquals(3, StrategyId.values().length);
    }
}
