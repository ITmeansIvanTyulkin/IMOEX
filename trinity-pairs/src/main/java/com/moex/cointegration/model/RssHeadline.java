package com.moex.cointegration.model;

import java.time.LocalDateTime;

/**
 * Карточка RSS для UI «Итог + новости» (контекст FA, не сигнал).
 */
public record RssHeadline(
        String title,
        String source,
        LocalDateTime publishedAt,
        String url,
        String tickerHint
) {
}
