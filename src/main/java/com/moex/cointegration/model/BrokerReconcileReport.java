package com.moex.cointegration.model;

import java.time.LocalDateTime;
import java.util.List;

public record BrokerReconcileReport(
        LocalDateTime fetchedAt,
        int paperOpenPairs,
        int brokerPositionCount,
        int brokerActiveOrderCount,
        int matched,
        int mismatched,
        List<BrokerReconcileItem> items,
        String summary
) {
}
