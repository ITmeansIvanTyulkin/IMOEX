package com.moex.cointegration.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Итог исторического прогона paper-пайплайна «как если бы торговали» на сохранённых свечах.
 */
public record HistoricalReplayReport(
        String tickerY,
        String tickerX,
        BookKind book,
        LocalDate from,
        LocalDate to,
        int barsProcessed,
        int tradesOpened,
        int tradesClosed,
        double netPnlRub,
        double realizedPnlRub,
        double maxDrawdownRub,
        double winRate,
        double equityStartRub,
        double equityEndRub,
        LocalDateTime generatedAt,
        List<PaperTradeEntry> entries
) {
}
