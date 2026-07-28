package com.moex.cointegration.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Результат preview / submit брокерского исполнения.
 */
public record BrokerExecutionReport(
        String pairKey,
        BrokerExecutionStatus status,
        String provider,
        BrokerMode mode,
        LocalDateTime createdAt,
        String summary,
        List<String> messages,
        List<String> orderIds,
        PairExecutionPlan plan
) {
}
