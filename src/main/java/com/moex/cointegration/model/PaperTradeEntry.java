package com.moex.cointegration.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Запись paper-trading журнала (открытие / mark-to-market / закрытие).
 */
public record PaperTradeEntry(
        String id,
        LocalDateTime openedAt,
        LocalDate asOfDate,
        String tickerY,
        String tickerX,
        TradingSignal signal,
        FinalTradeDecision decision,
        double entryZ,
        double hedgeRatio,
        double notionalY,
        double notionalX,
        double sizeMultiplier,
        String status,
        LocalDateTime closedAt,
        Double exitZ,
        Double pnlPct,
        Double pnlRub,
        Double markZ,
        Double unrealizedPnlPct,
        Double unrealizedPnlRub,
        LocalDate lastMarkDate,
        String notes
) {
    public PaperTradeEntry withClose(
            LocalDateTime closedAt,
            double exitZ,
            double pnlPct,
            double pnlRub,
            String notes
    ) {
        return new PaperTradeEntry(
                id, openedAt, asOfDate, tickerY, tickerX, signal, decision, entryZ, hedgeRatio,
                notionalY, notionalX, sizeMultiplier, "CLOSED", closedAt, exitZ, pnlPct, pnlRub,
                exitZ, null, null, asOfDate, notes
        );
    }

    public PaperTradeEntry withMark(LocalDate markDate, double markZ, double unrealizedPct, double unrealizedRub) {
        return new PaperTradeEntry(
                id, openedAt, asOfDate, tickerY, tickerX, signal, decision, entryZ, hedgeRatio,
                notionalY, notionalX, sizeMultiplier, status, closedAt, exitZ, pnlPct, pnlRub,
                markZ, unrealizedPct, unrealizedRub, markDate, notes
        );
    }
}
