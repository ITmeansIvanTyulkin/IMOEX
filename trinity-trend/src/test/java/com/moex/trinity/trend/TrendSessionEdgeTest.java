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
    void blocksMorningNoiseUntilTwentyMinutesAfterMainOpen() {
        // Main session 10:00 + 20 min → trade from 10:20
        assertNotNull(TrendSessionEdge.blockReason(LocalDateTime.of(2026, 8, 5, 7, 20), settings));
        assertNotNull(TrendSessionEdge.blockReason(LocalDateTime.of(2026, 8, 5, 9, 30), settings));
        assertNotNull(TrendSessionEdge.blockReason(LocalDateTime.of(2026, 8, 5, 10, 10), settings));
        assertNull(TrendSessionEdge.blockReason(LocalDateTime.of(2026, 8, 5, 10, 20), settings));
        assertTrue(TrendSessionEdge.isTradable(LocalDateTime.of(2026, 8, 5, 10, 25), settings));
        assertTrue(TrendSessionEdge.isTradable(LocalDateTime.of(2026, 8, 5, 14, 0), settings));
    }

    @Test
    void blocksEveningThinSessionAndLastThirtyMinutesBeforeMainClose() {
        // close 19:00 − 30 → from 18:30; evening 19–23:50 blocked
        assertTrue(TrendSessionEdge.isTradable(LocalDateTime.of(2026, 8, 5, 18, 0), settings));
        assertTrue(TrendSessionEdge.isTradable(LocalDateTime.of(2026, 8, 5, 18, 25), settings));
        assertFalse(TrendSessionEdge.isTradable(LocalDateTime.of(2026, 8, 5, 18, 30), settings));
        assertFalse(TrendSessionEdge.isTradable(LocalDateTime.of(2026, 8, 5, 19, 5), settings));
        assertFalse(TrendSessionEdge.isTradable(LocalDateTime.of(2026, 8, 5, 21, 0), settings));
        assertFalse(TrendSessionEdge.isTradable(LocalDateTime.of(2026, 8, 5, 23, 20), settings));
    }
}
