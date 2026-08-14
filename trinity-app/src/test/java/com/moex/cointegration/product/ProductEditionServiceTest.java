package com.moex.cointegration.product;

import com.moex.cointegration.config.ProductProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductEditionServiceTest {

    @Test
    void pairsLocksTrendAndArb() {
        ProductEditionService svc = new ProductEditionService(new ProductProperties("PAIRS"));
        assertTrue(svc.hasPairs());
        assertFalse(svc.hasTrend());
        assertFalse(svc.hasArb());
    }

    @Test
    void runtimeOverrideWinsUntilCleared() {
        ProductEditionService svc = new ProductEditionService(new ProductProperties("FULL"));
        assertTrue(svc.hasArb());
        svc.setOverride(ProductEdition.PAIRS_TREND);
        assertTrue(svc.hasTrend());
        assertFalse(svc.hasArb());
        assertEquals("PAIRS_TREND", svc.current().name());
        svc.clearOverride();
        assertEquals("FULL", svc.current().name());
        assertTrue(svc.hasArb());
    }
}
