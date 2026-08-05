package com.moex.trinity.marketdata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketDataResearchServiceTest {

    @Test
    void noopFeedReportsIdleContour() {
        MarketDataResearchService svc = new MarketDataResearchService(new NoopMarketDataFeed());
        assertEquals(MarketDataProviderId.NOOP, svc.feed().providerId());
        assertFalse(svc.liveReady());
        assertTrue(svc.statusMessage().contains("NOOP"));
    }

    @Test
    void tInvestFeedIdleWithoutStart() {
        MarketDataResearchService svc = new MarketDataResearchService(TInvestMarketDataFeed.unconfigured());
        assertEquals(MarketDataProviderId.T_INVEST, svc.feed().providerId());
        assertFalse(svc.liveReady());
        assertTrue(svc.statusMessage().contains("idle") || svc.statusMessage().contains("T-Invest"));
    }
}
