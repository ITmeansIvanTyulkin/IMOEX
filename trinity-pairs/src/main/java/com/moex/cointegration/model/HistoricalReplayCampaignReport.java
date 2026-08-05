package com.moex.cointegration.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Сводка пакетного исторического replay по нескольким парам.
 */
public record HistoricalReplayCampaignReport(
        String label,
        BookKind book,
        LocalDate from,
        LocalDate to,
        double equityRub,
        int pairsRequested,
        int pairsCompleted,
        int pairsFailed,
        double totalNetPnlRub,
        double totalRealizedPnlRub,
        int totalTradesOpened,
        int totalTradesClosed,
        double aggregateWinRate,
        LocalDateTime generatedAt,
        List<HistoricalReplayReport> pairReports,
        List<String> errors
) {
}
