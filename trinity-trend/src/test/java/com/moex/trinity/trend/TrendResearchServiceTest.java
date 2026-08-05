package com.moex.trinity.trend;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrendResearchServiceTest {

    @Test
    void selectsPlaceholderInTrend() {
        TrendPlaybook pb = new PlaceholderTrendPlaybook();
        TrendResearchService svc = new TrendResearchService(List.of(pb), new DefaultTrendRegimeSelector());
        assertTrue(svc.activePlaybook(new TrendRegimeContext("TREND", 34, true)).isPresent());
        assertEquals("trend-placeholder", svc.activePlaybook(new TrendRegimeContext("TREND", 34, true)).orElseThrow().id());
    }

    @Test
    void emptyInSidewaysLowAdx() {
        TrendResearchService svc = new TrendResearchService(
                List.of(new PlaceholderTrendPlaybook()), new DefaultTrendRegimeSelector());
        assertTrue(svc.activePlaybook(new TrendRegimeContext("SIDEWAYS", 12, false)).isEmpty());
    }
}
