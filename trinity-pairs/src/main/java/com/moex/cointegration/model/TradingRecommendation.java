package com.moex.cointegration.model;

import java.time.LocalDate;

/**
 * Торговая рекомендация по одной коинтегрированной паре.
 *
 * @param tickerY       акция Y (зависимая в регрессии)
 * @param tickerX       акция X (независимая)
 * @param signal        тип сигнала
 * @param currentZScore Z-score спреда на последнюю дату
 * @param asOfDate      дата последней свечи
 * @param currentSpread значение спреда на последнюю дату
 * @param hedgeRatio    коэффициент хеджа (beta)
 * @param halfLifeDays  half-life mean reversion
 * @param sharpeRatio   Sharpe backtest
 * @param pValue        p-value коинтеграции
 * @param summary       краткая рекомендация на русском
 * @param details       пояснение с конкретными действиями
 * @param coveragePercent доля общих баров пары, %
 * @param coverageWarning предупреждение о низком покрытии
 */
public record TradingRecommendation(
        String tickerY,
        String tickerX,
        TradingSignal signal,
        double currentZScore,
        LocalDate asOfDate,
        double currentSpread,
        double hedgeRatio,
        double halfLifeDays,
        double sharpeRatio,
        double pValue,
        String summary,
        String details,
        Double coveragePercent,
        String coverageWarning
) {
}
