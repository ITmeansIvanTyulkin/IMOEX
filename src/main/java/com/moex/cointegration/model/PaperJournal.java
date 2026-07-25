package com.moex.cointegration.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Снимок paper-журнала на диске / для UI.
 *
 * @param realizedPnlRub   сумма закрытых сделок (псевдо ₽)
 * @param unrealizedPnlRub mark-to-market открытых (псевдо ₽)
 */
public record PaperJournal(
        LocalDateTime updatedAt,
        List<PaperTradeEntry> entries,
        Double realizedPnlRub,
        Double unrealizedPnlRub,
        Integer openCount,
        Integer closedCount
) {
    public PaperJournal(LocalDateTime updatedAt, List<PaperTradeEntry> entries) {
        this(updatedAt, entries, null, null, null, null);
    }
}
