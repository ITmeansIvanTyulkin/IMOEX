package com.moex.trinity.trend;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrendSessionEdgeTest {

    private final TrendPlaybookSettings settings = TrendPlaybookSettings.brDefaults();

    @Test
    void blocksMorningNoiseUntilFortyMinutesAfterOpen() {
        // FORTS open 09:00 + 40 min → trade from 09:40
        assertNotNull(TrendSessionEdge.blockReason(LocalDateTime.of(2026, 8, 5, 7, 20), settings));
        assertNotNull(TrendSessionEdge.blockReason(LocalDateTime.of(2026, 8, 5, 8, 40), settings));
        assertNotNull(TrendSessionEdge.blockReason(LocalDateTime.of(2026, 8, 5, 9, 30), settings));
        assertNull(TrendSessionEdge.blockReason(LocalDateTime.of(2026, 8, 5, 9, 40), settings));
        assertTrue(TrendSessionEdge.isTradable(LocalDateTime.of(2026, 8, 5, 9, 55), settings));
        assertTrue(TrendSessionEdge.isTradable(LocalDateTime.of(2026, 8, 5, 10, 15), settings));
    }

    @Test
    void blocksLastThirtyMinutesBeforeClose() {
        // close 23:50 − 30 → from 23:20
        assertTrue(TrendSessionEdge.isTradable(LocalDateTime.of(2026, 8, 5, 23, 5), settings));
        assertTrue(TrendSessionEdge.isTradable(LocalDateTime.of(2026, 8, 5, 23, 15), settings));
        assertFalse(TrendSessionEdge.isTradable(LocalDateTime.of(2026, 8, 5, 23, 20), settings));
        assertFalse(TrendSessionEdge.isTradable(LocalDateTime.of(2026, 8, 5, 23, 40), settings));
    }
}
