package com.moex.cointegration.model;

/**
 * Итоговая строка: технический сигнал + новости + финальное решение.
 */
public record FinalTradeRecommendation(
        TradingRecommendation technical,
        PairNewsAssessment news,
        FinalTradeDecision decision,
        String decisionSummary,
        String beginnerGuide
) {
    public String tickerY() {
        return technical.tickerY();
    }

    public String tickerX() {
        return technical.tickerX();
    }
}
