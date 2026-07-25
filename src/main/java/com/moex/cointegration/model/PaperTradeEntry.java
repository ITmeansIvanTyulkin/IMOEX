package com.moex.cointegration.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Запись paper-trading журнала (открытие / MTM / partial TP / закрытие).
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
        String notes,
        Double bestZ,
        Boolean partialTaken,
        Double remainingFraction,
        Double realizedPartialRub
) {
    public double remainingFracOrOne() {
        return remainingFraction == null || remainingFraction <= 0 ? 1.0 : remainingFraction;
    }

    public boolean partialDone() {
        return Boolean.TRUE.equals(partialTaken);
    }

    public PaperTradeEntry withClose(
            LocalDateTime closedAt,
            double exitZ,
            double pnlPct,
            double pnlRub,
            String notes
    ) {
        double totalRub = pnlRub + (realizedPartialRub == null ? 0.0 : realizedPartialRub);
        return new PaperTradeEntry(
                id, openedAt, asOfDate, tickerY, tickerX, signal, decision, entryZ, hedgeRatio,
                notionalY, notionalX, sizeMultiplier, "CLOSED", closedAt, exitZ, pnlPct, totalRub,
                exitZ, null, null, asOfDate, notes, bestZ, partialTaken, 0.0, realizedPartialRub
        );
    }

    public PaperTradeEntry withMark(
            LocalDate markDate,
            double markZ,
            double unrealizedPct,
            double unrealizedRub,
            Double bestZ
    ) {
        return new PaperTradeEntry(
                id, openedAt, asOfDate, tickerY, tickerX, signal, decision, entryZ, hedgeRatio,
                notionalY, notionalX, sizeMultiplier, status, closedAt, exitZ, pnlPct, pnlRub,
                markZ, unrealizedPct, unrealizedRub, markDate, notes,
                bestZ, partialTaken, remainingFraction, realizedPartialRub
        );
    }

    /** Частичный TP: фиксируем половину PnL, оставляем half notional. */
    public PaperTradeEntry withPartialTp(
            LocalDate markDate,
            double markZ,
            double partialPnlRub,
            String notes
    ) {
        double rem = remainingFracOrOne() * 0.5;
        double newNy = notionalY * 0.5;
        double newNx = notionalX * 0.5;
        double realized = (realizedPartialRub == null ? 0.0 : realizedPartialRub) + partialPnlRub;
        return new PaperTradeEntry(
                id, openedAt, asOfDate, tickerY, tickerX, signal, decision, entryZ, hedgeRatio,
                newNy, newNx, sizeMultiplier * 0.5, "OPEN", null, null, null, null,
                markZ, null, null, markDate, notes,
                bestZ, true, rem, realized
        );
    }
}
