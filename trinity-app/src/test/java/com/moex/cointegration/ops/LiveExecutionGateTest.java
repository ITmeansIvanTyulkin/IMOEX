package com.moex.cointegration.ops;

import com.moex.cointegration.smoke.StartupSmokeStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveExecutionGateTest {

    @Test
    void blocksLiveWhileSmokePending() {
        StartupSmokeStatus smoke = new StartupSmokeStatus();
        smoke.pending(5);
        LiveExecutionGate gate = new LiveExecutionGate(smoke);
        assertNotNull(gate.blockEnableLiveReason());
        assertFalse(Boolean.TRUE.equals(gate.checklist().get("readyForLive")));
    }

    @Test
    void readyWhenSmokeOk() {
        StartupSmokeStatus smoke = new StartupSmokeStatus();
        smoke.ok(1, 5, java.util.List.of(), false);
        LiveExecutionGate gate = new LiveExecutionGate(smoke);
        assertNull(gate.blockEnableLiveReason());
        assertTrue(Boolean.TRUE.equals(gate.checklist().get("readyForLive")));
    }
}
