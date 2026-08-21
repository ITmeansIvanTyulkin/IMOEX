package com.moex.cointegration.smoke;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupSmokeStatusTest {

    @Test
    void failThenOkKeepsExecutionBlockedFlag() {
        StartupSmokeStatus status = new StartupSmokeStatus();
        status.fail(1, 5, List.of(new StartupSmokeStatus.Check("x", false, "boom")), "FAIL");
        assertFalse(status.get().ok());
        assertTrue(status.get().executionBlocked());

        status.ok(2, 5, List.of(new StartupSmokeStatus.Check("x", true, "ok")), true);
        assertTrue(status.get().ok());
        assertTrue(status.get().executionBlocked());
        assertTrue(status.get().message().contains("auto/live"));

        Map<String, Object> dto = status.dto();
        assertEquals("OK", dto.get("phase"));
        assertTrue((Boolean) dto.get("executionBlocked"));
        assertTrue(dto.containsKey("checklist"));
    }
}
