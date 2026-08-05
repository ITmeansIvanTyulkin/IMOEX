package com.moex.cointegration.model;

import java.time.LocalDateTime;

/**
 * Статус автопрогонов DAILY / INTRADAY.
 */
public record AutoRunStatus(
        boolean intradayAutoEnabled,
        boolean dailyAutoEnabled,
        String intradayCron,
        String dailyCron,
        LocalDateTime lastIntradayRunAt,
        String lastIntradayRunStatus,
        LocalDateTime lastDailyRunAt,
        String lastDailyRunStatus
) {
}
