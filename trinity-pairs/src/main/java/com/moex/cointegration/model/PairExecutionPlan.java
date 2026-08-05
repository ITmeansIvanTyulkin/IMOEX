package com.moex.cointegration.model;

import java.time.LocalDateTime;

/**
 * Подготовленный план входа в парную сделку двумя ногами.
 */
public record PairExecutionPlan(
        String pairKey,
        BookKind book,
        BrokerMode mode,
        String provider,
        LocalDateTime createdAt,
        FinalTradeDecision decision,
        TradingSignal signal,
        double currentZScore,
        double hedgeRatio,
        String rationale,
        BrokerOrderIntent legY,
        BrokerOrderIntent legX,
        boolean marketFallbackAllowed,
        int secondLegTimeoutSeconds,
        double maxLegDriftBps
) {
}
