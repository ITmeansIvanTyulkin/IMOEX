package com.moex.cointegration.model;

import java.time.LocalDateTime;

/**
 * Уведомление о новой paper-сделке (для polling в браузере).
 */
public record PaperTradeAlert(
        String id,
        String book,
        String tickerY,
        String tickerX,
        TradingSignal signal,
        double entryZ,
        LocalDateTime openedAt,
        String summary
) {
}
