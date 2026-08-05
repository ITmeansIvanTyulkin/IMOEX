package com.moex.cointegration.universe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TierOneCatalogTest {

    @Test
    void recognizesBlueChips() {
        assertTrue(TierOneCatalog.isTierOne("SBER"));
        assertTrue(TierOneCatalog.isTierOne("LKOH"));
        assertTrue(TierOneCatalog.pairTierOne("SBER", "VTBR"));
    }

    @Test
    void rejectsSecondTier() {
        assertFalse(TierOneCatalog.isTierOne("SAGO"));
        assertFalse(TierOneCatalog.pairTierOne("SBER", "SAGO"));
    }
}
