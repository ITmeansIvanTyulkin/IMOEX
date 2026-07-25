package com.moex.cointegration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки планировщика еженедельного пересчёта коинтеграции.
 *
 * @param enabled включён ли cron-запуск
 * @param cron    cron-выражение Spring (по умолчанию воскресенье 06:00)
 */
@ConfigurationProperties(prefix = "analysis.schedule")
public record ScheduleProperties(
        boolean enabled,
        String cron
) {
}
