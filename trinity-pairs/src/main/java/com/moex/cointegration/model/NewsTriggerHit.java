package com.moex.cointegration.model;

import java.time.LocalDateTime;

/**
 * Сработавший триггер по одной ноге пары.
 */
public record NewsTriggerHit(
        String ticker,
        NewsTriggerType type,
        NewsRiskLevel severity,
        String title,
        LocalDateTime publishedAt,
        String explanation,
        boolean asymmetricHint
) {
}
