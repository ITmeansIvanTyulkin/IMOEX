package com.moex.cointegration.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Итоговый отчёт одного прогона анализа по всему индексу IMOEX.
 *
 * @param analysisDate      дата расчёта
 * @param tickersAnalyzed   число акций после выравнивания
 * @param pairsTested       число протестированных уникальных пар
 * @param cointegratedPairs число пар, прошедших фильтр коинтеграции
 * @param topPairs          топ-N пар по Sharpe с полными рядами для графиков
 */
public record AnalysisReport(
        LocalDate analysisDate,
        int tickersAnalyzed,
        int pairsTested,
        int cointegratedPairs,
        List<PairAnalysisResult> topPairs
) {
}
