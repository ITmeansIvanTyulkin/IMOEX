package com.moex.cointegration.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Итог портфельного исторического replay: один счёт, live/research-отбор, cash PnL.
 */
public record PortfolioHistoricalReplayReport(
        String label,
        String profile,
        BookKind book,
        LocalDate from,
        LocalDate to,
        double equityStartRub,
        double equityEndRub,
        double netPnlRub,
        double realizedPnlRub,
        double unrealizedPnlRub,
        double maxDrawdownRub,
        double expectancyRub,
        double avgWinRub,
        double avgLossRub,
        double profitFactor,
        int barsProcessed,
        int barsWithFdrPairs,
        int tradesOpened,
        int tradesClosed,
        double winRate,
        int maxPairsSlot,
        double grossCapRub,
        LocalDateTime generatedAt,
        List<PairCashStats> pairBreakdown,
        List<PaperTradeEntry> entries
) {
}
