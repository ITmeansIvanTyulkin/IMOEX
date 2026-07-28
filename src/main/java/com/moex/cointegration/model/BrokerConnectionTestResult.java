package com.moex.cointegration.model;

import java.time.LocalDateTime;

public record BrokerConnectionTestResult(
        LocalDateTime checkedAt,
        String provider,
        BrokerMode mode,
        boolean sandbox,
        boolean enabled,
        boolean tokenPresent,
        boolean accountConfigured,
        boolean snapshotAvailable,
        int positionCount,
        int activeOrderCount,
        String summary
) {
}
