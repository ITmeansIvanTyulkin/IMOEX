package com.moex.cointegration.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Запись paper-trading журнала (открытие / MTM / partial TP / закрытие).
 * qty/prices — для cash PnL; если null, UI/сервис падает назад на Z-прокси.
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
        Double realizedPartialRub,
        Double qtyY,
        Double qtyX,
        Double entryPriceY,
        Double entryPriceX,
        String closeComment,
        String book
) {
    public PaperTradeEntry {
        if (book == null || book.isBlank()) {
            book = "DAILY";
        }
    }

    public double remainingFracOrOne() {
        return remainingFraction == null || remainingFraction <= 0 ? 1.0 : remainingFraction;
    }

    public boolean partialDone() {
        return Boolean.TRUE.equals(partialTaken);
    }

    public boolean hasCashLegs() {
        return qtyY != null && qtyX != null && entryPriceY != null && entryPriceX != null
                && qtyY > 0 && qtyX > 0 && entryPriceY > 0 && entryPriceX > 0;
    }

    public PaperTradeEntry withClose(
            LocalDateTime closedAt,
            double exitZ,
            double pnlPct,
            double pnlRub,
            String closeReason
    ) {
        double totalRub = pnlRub + (realizedPartialRub == null ? 0.0 : realizedPartialRub);
        String comment = CloseComment.categorize(closeReason);
        String mergedNotes = notes == null || notes.isBlank()
                ? closeReason
                : notes + " | " + closeReason;
        return new PaperTradeEntry(
                id, openedAt, asOfDate, tickerY, tickerX, signal, decision, entryZ, hedgeRatio,
                notionalY, notionalX, sizeMultiplier, "CLOSED", closedAt, exitZ, pnlPct, totalRub,
                exitZ, null, null, asOfDate, mergedNotes, bestZ, partialTaken, 0.0, realizedPartialRub,
                qtyY, qtyX, entryPriceY, entryPriceX, comment, book
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
                bestZ, partialTaken, remainingFraction, realizedPartialRub,
                qtyY, qtyX, entryPriceY, entryPriceX, closeComment, book
        );
    }

    public PaperTradeEntry withPartialTp(
            LocalDate markDate,
            double markZ,
            double partialPnlRub,
            String notes
    ) {
        double rem = remainingFracOrOne() * 0.5;
        double newNy = notionalY * 0.5;
        double newNx = notionalX * 0.5;
        Double newQtyY = qtyY == null ? null : qtyY * 0.5;
        Double newQtyX = qtyX == null ? null : qtyX * 0.5;
        double realized = (realizedPartialRub == null ? 0.0 : realizedPartialRub) + partialPnlRub;
        return new PaperTradeEntry(
                id, openedAt, asOfDate, tickerY, tickerX, signal, decision, entryZ, hedgeRatio,
                newNy, newNx, sizeMultiplier * 0.5, "OPEN", null, null, null, null,
                markZ, null, null, markDate, notes,
                bestZ, true, rem, realized,
                newQtyY, newQtyX, entryPriceY, entryPriceX, closeComment, book
        );
    }
}
