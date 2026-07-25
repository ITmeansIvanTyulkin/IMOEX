package com.moex.cointegration.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Запись paper-trading журнала.
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
        String notes
) {
    public PaperTradeEntry withClose(LocalDateTime closedAt, double exitZ, double pnlPct, String notes) {
        return new PaperTradeEntry(
                id, openedAt, asOfDate, tickerY, tickerX, signal, decision, entryZ, hedgeRatio,
                notionalY, notionalX, sizeMultiplier, "CLOSED", closedAt, exitZ, pnlPct, notes
        );
    }
}
