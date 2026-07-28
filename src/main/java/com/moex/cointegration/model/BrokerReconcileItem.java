package com.moex.cointegration.model;

public record BrokerReconcileItem(
        String ticker,
        double expectedQty,
        long brokerQty,
        String status,
        String note
) {
}
