package com.moex.cointegration.model;

public record BrokerOrderSnapshot(
        String orderId,
        String ticker,
        String status,
        long requestedLots,
        long executedLots,
        String side,
        String type
) {
}
