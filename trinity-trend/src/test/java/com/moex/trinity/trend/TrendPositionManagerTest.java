package com.moex.trinity.trend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrendPositionManagerTest {

    @Test
    void beforeTp1KeepsInitialStop() {
        var a = TrendPositionManager.update(true, 80.0, 79.80, 80.20, 80.10, 0.01, 20);
        assertFalse(a.tp1Touched());
        assertEquals(79.80, a.stop(), 1e-9);
        assertFalse(a.movedToBe());
        assertEquals(1, a.stopQty());
    }

    @Test
    void tp1MovesLongToBreakeven() {
        var a = TrendPositionManager.update(true, 80.0, 79.80, 80.20, 80.20, 0.01, 20, 3, 1.0 / 3.0, false);
        assertTrue(a.tp1Touched());
        assertEquals(80.0, a.stop(), 1e-9);
        assertTrue(a.movedToBe());
        assertEquals(2, a.stopQty(), "§12.2 stop qty = remainder after 1/3");
    }

    @Test
    void beyondTp1TrailsLong() {
        // last=80.50 → trail 20pts = 0.20 → stop 80.30
        var a = TrendPositionManager.update(true, 80.0, 80.0, 80.20, 80.50, 0.01, 20, 3, 1.0 / 3.0, true);
        assertTrue(a.tp1Touched());
        assertTrue(a.trailing());
        assertEquals(80.30, a.stop(), 1e-9);
    }

    @Test
    void shortBeThenTrail() {
        var be = TrendPositionManager.update(false, 80.0, 80.20, 79.80, 79.80, 0.01, 20, 3, 1.0 / 3.0, false);
        assertTrue(be.tp1Touched());
        assertEquals(80.0, be.stop(), 1e-9);
        assertEquals(2, be.stopQty());
        var trail = TrendPositionManager.update(false, 80.0, 80.0, 79.80, 79.50, 0.01, 20, 2, 1.0 / 3.0, true);
        assertTrue(trail.trailing());
        assertEquals(79.70, trail.stop(), 1e-9);
    }
}
