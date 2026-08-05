package com.moex.trinity.calendararb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalendarArbResearchServiceTest {

    @Test
    void skeletonReportsStrategyId() {
        CalendarArbResearchService svc = new CalendarArbResearchService();
        assertEquals(com.moex.trinity.shared.StrategyId.CALENDAR_ARB, svc.strategyId());
        assertTrue(svc.statusMessage().contains("skeleton"));
    }
}
