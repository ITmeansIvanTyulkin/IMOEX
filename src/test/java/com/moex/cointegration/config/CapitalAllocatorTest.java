package com.moex.cointegration.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CapitalAllocatorTest {

    @Test
    void allocates100kSlotsAndGrossSplit() {
        CapitalProperties capital = new CapitalProperties(100_000.0, 1_000_000.0, 1.0, 0.40, 0.60);
        CapitalAllocator.Allocation a = CapitalAllocator.allocate(capital);

        assertEquals(1, a.dailyMaxPairs());
        assertEquals(2, a.intradayMaxPairs());
        assertEquals(40_000.0, a.dailyGrossCap(), 1e-6);
        assertEquals(60_000.0, a.intradayGrossCap(), 1e-6);
        assertFalse(a.leverageAllowed());
    }

    @Test
    void allocates200kSlotsAndGrossSplit() {
        CapitalProperties capital = new CapitalProperties(200_000.0, 1_000_000.0, 1.0, 0.40, 0.60);
        CapitalAllocator.Allocation a = CapitalAllocator.allocate(capital);

        assertEquals(2, a.dailyMaxPairs());
        assertEquals(3, a.intradayMaxPairs());
        assertEquals(80_000.0, a.dailyGrossCap(), 1e-6);
        assertEquals(120_000.0, a.intradayGrossCap(), 1e-6);
    }
}
