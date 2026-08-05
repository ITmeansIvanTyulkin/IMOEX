package com.moex.trinity.trend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LimitGridAndSizerTest {

    @Test
    void moderateSplitsEvenly() {
        assertArrayEquals(new int[]{2, 2, 2}, LimitGridBuilder.splitQty(6, LimitGridStyle.MODERATE));
        assertArrayEquals(new int[]{2, 2, 1}, LimitGridBuilder.splitQty(5, LimitGridStyle.MODERATE));
    }

    @Test
    void aggressiveNearHeavy() {
        assertArrayEquals(new int[]{3, 1, 1}, LimitGridBuilder.splitQty(5, LimitGridStyle.AGGRESSIVE));
        assertArrayEquals(new int[]{4, 1, 1}, LimitGridBuilder.splitQty(6, LimitGridStyle.AGGRESSIVE));
    }

    @Test
    void gridPricesBuyNearIsHigh() {
        MergedVolumeRange range = new MergedVolumeRange(84.54, 84.71, 1000, java.util.List.of(), true, null);
        LimitGridPlan grid = LimitGridBuilder.build(range, true, 6, LimitGridStyle.MODERATE);
        assertEquals(84.71, grid.nearPrice(), 1e-9);
        assertEquals(84.54, grid.farPrice(), 1e-9);
        assertEquals(6, grid.totalQty());
        assertTrue(grid.averagePrice() > 84.54 && grid.averagePrice() < 84.71);
    }

    @Test
    void sizingUsesRiskCapNotFullGo() {
        // equity 100k, GO 15k → byGo=6; risk 1% = 1000 rub; stop 20 pts * 7 = 140 → byRisk=7 → min=6
        TrendAccountContext acct = TrendAccountContext.of(100_000, 15_000, 16_000, 1.0);
        int n = TrendPositionSizer.sizeContracts(acct, TrendInstrumentSpec.brDefaults(), true, 20);
        assertEquals(6, n);

        // tighter risk 0.5% → 500/140 = 3
        acct = TrendAccountContext.of(100_000, 15_000, 16_000, 0.5);
        n = TrendPositionSizer.sizeContracts(acct, TrendInstrumentSpec.brDefaults(), true, 20);
        assertEquals(3, n);
    }
}
