package com.moex.cointegration.model;

import com.moex.cointegration.quant.WalkForwardAnalyzer;

import java.time.LocalDate;
import java.util.List;

/**
 * Агрегированный walk-forward отчёт по universe / топ-парам.
 */
public record WalkForwardReport(
        LocalDate analysisDate,
        int pairsEvaluated,
        int pairsWithPositiveMedianOosSharpe,
        double meanMedianOosSharpe,
        List<PairWalkForward> pairs
) {
    public record PairWalkForward(
            String tickerY,
            String tickerX,
            WalkForwardAnalyzer.Summary summary
    ) {
    }
}
