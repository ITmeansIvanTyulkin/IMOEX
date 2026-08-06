package com.moex.cointegration.model;

import java.time.LocalDateTime;

/**
 * Уведомление о paper OPEN/CLOSE (для polling в браузере).
 */
public record PaperTradeAlert(
        String id,
        String kind,
        String book,
        String tickerY,
        String tickerX,
        TradingSignal signal,
        double entryZ,
        LocalDateTime at,
        String summary,
        Double pnlRub,
        Double potentialPnlRub
) {
    /** Back-compat accessor used by older clients. */
    public LocalDateTime openedAt() {
        return at;
    }
}
