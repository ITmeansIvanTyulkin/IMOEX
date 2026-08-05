package com.moex.cointegration.model;

import java.time.LocalDateTime;

/**
 * Одна новость/событие из источника (MOEX sitenews и т.п.).
 */
public record NewsItem(
        long id,
        String title,
        LocalDateTime publishedAt,
        String source
) {
}
