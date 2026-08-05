package com.moex.cointegration.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Полный результат анализа одной коинтегрированной пары, включая временные ряды для графиков.
 *
 * @param tickerY       тикер Y
 * @param tickerX       тикер X
 * @param intercept     intercept коинтегрирующего уравнения
 * @param hedgeRatio    коэффициент хеджа
 * @param adfStatistic  ADF t-статистика
 * @param pValue        p-value коинтеграции
 * @param sharpeRatio   Sharpe стратегии на спреде
 * @param maxDrawdown   максимальная просадка
 * @param halfLifeDays  half-life mean reversion
 * @param tradeCount    число сделок в симуляции
 * @param totalReturn   суммарная доходность
 * @param spreadSeries  исторический спред
 * @param zScoreSeries  исторический Z-score спреда
 * @param coveragePercent доля общих баров / max(ряд Y, ряд X), %
 * @param coverageWarning предупреждение при низком покрытии (null если ок)
 */
public record PairAnalysisResult(
        String tickerY,
        String tickerX,
        double intercept,
        double hedgeRatio,
        double adfStatistic,
        double pValue,
        double sharpeRatio,
        double maxDrawdown,
        double halfLifeDays,
        int tradeCount,
        double totalReturn,
        double rSquared,
        List<SpreadPoint> spreadSeries,
        List<SpreadPoint> zScoreSeries,
        Double coveragePercent,
        @JsonProperty("warning") String coverageWarning
) {
    public PairAnalysisResult {
        if (coveragePercent == null || coveragePercent.isNaN()) {
            coveragePercent = 100.0;
        }
    }
}
