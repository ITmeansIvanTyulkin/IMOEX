package com.moex.cointegration.model;

import java.time.LocalDateTime;
import java.util.List;

public record BrokerAccountSnapshot(
        String provider,
        LocalDateTime fetchedAt,
        boolean available,
        List<BrokerPositionSnapshot> positions,
        List<BrokerOrderSnapshot> activeOrders,
        String summary
) {
}
