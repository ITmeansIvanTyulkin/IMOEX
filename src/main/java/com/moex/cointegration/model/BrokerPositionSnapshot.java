package com.moex.cointegration.model;

public record BrokerPositionSnapshot(
        String ticker,
        String figi,
        long balance
) {
}
