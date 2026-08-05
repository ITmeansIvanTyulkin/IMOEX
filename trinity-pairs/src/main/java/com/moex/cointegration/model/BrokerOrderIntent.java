package com.moex.cointegration.model;

/**
 * Намерение по одной ноге пары до отправки брокеру.
 */
public record BrokerOrderIntent(
        String ticker,
        BrokerOrderSide side,
        BrokerOrderType orderType,
        Double referencePrice,
        Double limitPrice,
        Double quantity,
        Double notionalRub
) {
}
