package com.moex.cointegration.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Запланированное рыночное событие для INTRADAY risk overlay (flatten / block входов).
 */
public record EventCalendarEntry(
        LocalDate date,
        String time,
        String type,
        String title,
        List<String> tickers
) {
    public EventCalendarEntry {
        if (tickers == null || tickers.isEmpty()) {
            tickers = List.of("*");
        }
    }
}
