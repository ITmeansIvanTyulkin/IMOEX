package com.moex.cointegration.universe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectorCatalogTest {

    @Test
    void banksSameSector() {
        assertTrue(SectorCatalog.sameSector("SBER", "VTBR"));
        assertTrue(SectorCatalog.sameSector("sber", "TCSG"));
    }

    @Test
    void oilVsBankRejected() {
        assertFalse(SectorCatalog.sameSector("LKOH", "SBER"));
        assertFalse(SectorCatalog.sameSector("GAZP", "NLMK"));
    }

    @Test
    void relatedEnergyGroup() {
        assertTrue(SectorCatalog.sameOrRelatedSector("LKOH", "FEES")); // oil + utilities
        assertFalse(SectorCatalog.sameOrRelatedSector("LKOH", "SBER"));
    }
}
