package com.moex.cointegration.quant;

import com.moex.cointegration.model.AdfResult;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.linear.SingularMatrixException;
import org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression;
import org.apache.commons.math3.stat.regression.SimpleRegression;

/**
 * Augmented Dickey-Fuller тест с настраиваемыми константой, трендом и набором критических значений.
 */
public final class AdfTest {

    /** Набор критических значений MacKinnon для интерпретации t-статистики. */
    public enum CriticalValueSet {
        /** Стандартный ADF на уровнях I(1) рядов с константой. */
        STANDARD,
        /** ADF на остатках Engle-Granger для двух переменных с константой на шаге 1. */
        COINTEGRATION_RESIDUALS
    }

    /** Критерий автоподбора числа лагов в ADF-регрессии. */
    public enum LagCriterion {
        AIC,
        BIC
    }

    private static final NormalDistribution NORMAL = new NormalDistribution();

    private AdfTest() {
    }

    /**
     * ADF с 1 лагом, без тренда, с константой и стандартными критическими значениями.
     */
    public static AdfResult test(double[] series) {
        return test(series, 1, false, true, CriticalValueSet.STANDARD);
    }

    /**
     * ADF с заданным числом лагов, настройкой тренда и стандартными критическими значениями.
     */
    public static AdfResult test(double[] series, int lags, boolean includeTrend) {
        return test(series, lags, includeTrend, true, CriticalValueSet.STANDARD);
    }

    /**
     * ADF с автоподбором лагов от 0 до {@code maxLag} по AIC.
     */
    public static AdfResult testWithAutoLag(
            double[] series,
            int maxLag,
            boolean includeTrend,
            boolean includeConstant,
            CriticalValueSet criticalValues
    ) {
        return testWithAutoLag(series, maxLag, includeTrend, includeConstant, criticalValues, LagCriterion.AIC);
    }

    /**
     * ADF с автоподбором лагов по AIC или BIC.
     *
     * @param maxLag           максимальное число лагов разности
     * @param includeTrend     включать ли детерминированный тренд
     * @param includeConstant  включать ли константу в ADF-регрессии
     * @param criticalValues   набор критических значений для p-value
     * @param criterion        AIC или BIC
     */
    public static AdfResult testWithAutoLag(
            double[] series,
            int maxLag,
            boolean includeTrend,
            boolean includeConstant,
            CriticalValueSet criticalValues,
            LagCriterion criterion
    ) {
        int upperLag = Math.max(0, maxLag);
        AdfResult bestResult = null;
        double bestScore = Double.MAX_VALUE;

        for (int lag = 0; lag <= upperLag; lag++) {
            if (series.length <= lag + 3) {
                continue;
            }
            AdfFit fit = fit(series, lag, includeTrend, includeConstant, criticalValues);
            double score = criterion == LagCriterion.BIC ? fit.bic() : fit.aic();
            if (score < bestScore) {
                bestScore = score;
                bestResult = fit.result();
            }
        }

        return bestResult != null ? bestResult : nonStationaryResult();
    }

    /**
     * Полный ADF с явно заданным числом лагов и всеми опциями спецификации.
     */
    public static AdfResult test(
            double[] series,
            int lags,
            boolean includeTrend,
            boolean includeConstant,
            CriticalValueSet criticalValues
    ) {
        if (series.length <= lags + 3) {
            throw new IllegalArgumentException("Series too short for ADF test");
        }
        return fit(series, lags, includeTrend, includeConstant, criticalValues).result();
    }

    /** Выбирает быстрый DF (0 лагов) или расширенную ADF-регрессию. */
    private static AdfFit fit(
            double[] series,
            int lags,
            boolean includeTrend,
            boolean includeConstant,
            CriticalValueSet criticalValues
    ) {
        if (lags == 0 && !includeTrend) {
            return fitZeroLag(series, includeConstant, criticalValues);
        }
        return fitAugmented(series, lags, includeTrend, includeConstant, criticalValues);
    }

    /**
     * Dickey-Fuller без лагов: Δy_t = γ·y_{t-1} (+ константа при необходимости).
     * Используется SimpleRegression для численной устойчивости.
     */
    private static AdfFit fitZeroLag(
            double[] series,
            boolean includeConstant,
            CriticalValueSet criticalValues
    ) {
        SimpleRegression regression = new SimpleRegression(includeConstant);
        for (int i = 1; i < series.length; i++) {
            regression.addData(series[i - 1], series[i] - series[i - 1]);
        }

        double gamma = regression.getSlope();
        double stdErr = regression.getSlopeStdErr();
        if (Double.isNaN(stdErr) || stdErr < 1e-12) {
            return new AdfFit(nonStationaryResult(), Double.MAX_VALUE, Double.MAX_VALUE);
        }

        double tStat = gamma / stdErr;
        double pValue = approximatePValue(tStat, false, includeConstant, criticalValues);
        int sampleSize = series.length - 1;
        int paramCount = includeConstant ? 2 : 1;
        double sse = regression.getSumSquaredErrors();
        double logLikelihoodTerm = sampleSize * Math.log(Math.max(sse / sampleSize, 1e-12));

        return new AdfFit(
                new AdfResult(tStat, pValue, pValue < 0.05),
                logLikelihoodTerm + 2.0 * paramCount,
                logLikelihoodTerm + Math.log(sampleSize) * paramCount
        );
    }

    /**
     * Расширенный ADF с лагами разностей и опциональным трендом.
     * Регрессия: Δy_t = γ·y_{t-1} + Σδ_i·Δy_{t-i} (+ тренд/константа).
     */
    private static AdfFit fitAugmented(
            double[] series,
            int lags,
            boolean includeTrend,
            boolean includeConstant,
            CriticalValueSet criticalValues
    ) {
        int sampleSize = series.length - lags - 1;
        double[] y = new double[sampleSize];
        double[][] x = new double[sampleSize][];

        for (int t = 0; t < sampleSize; t++) {
            int idx = t + lags + 1;
            y[t] = series[idx] - series[idx - 1];

            int regressorCount = 1 + lags + (includeTrend ? 1 : 0);
            double[] row = new double[regressorCount];
            int col = 0;
            row[col++] = series[idx - 1];
            if (includeTrend) {
                row[col++] = idx;
            }
            for (int lag = 1; lag <= lags; lag++) {
                row[col++] = series[idx - lag] - series[idx - lag - 1];
            }
            x[t] = row;
        }

        try {
            OLSMultipleLinearRegression regression = new OLSMultipleLinearRegression();
            if (!includeConstant) {
                regression.setNoIntercept(true);
            }
            regression.newSampleData(y, x);
            double[] params = regression.estimateRegressionParameters();
            double[][] cov = regression.estimateRegressionParametersVariance();
            double[] residuals = regression.estimateResiduals();

            int gammaIdx = includeConstant ? 1 : 0;
            double gamma = params[gammaIdx];
            double stdErr = Math.sqrt(Math.max(cov[gammaIdx][gammaIdx], 0.0));
            if (stdErr < 1e-12) {
                return new AdfFit(nonStationaryResult(), Double.MAX_VALUE, Double.MAX_VALUE);
            }

            double tStat = gamma / stdErr;
            double pValue = approximatePValue(tStat, includeTrend, includeConstant, criticalValues);
            double sse = 0.0;
            for (double residual : residuals) {
                sse += residual * residual;
            }
            double logLikelihoodTerm = sampleSize * Math.log(Math.max(sse / sampleSize, 1e-12));
            int paramCount = params.length;

            return new AdfFit(
                    new AdfResult(tStat, pValue, pValue < 0.05),
                    logLikelihoodTerm + 2.0 * paramCount,
                    logLikelihoodTerm + Math.log(sampleSize) * paramCount
            );
        } catch (SingularMatrixException ex) {
            return new AdfFit(nonStationaryResult(), Double.MAX_VALUE, Double.MAX_VALUE);
        }
    }

    /** Внутренний контейнер результата ADF и информационных критериев AIC/BIC. */
    private record AdfFit(AdfResult result, double aic, double bic) {
    }

    /** Возвращает «нестационарный» результат при вырожденной регрессии. */
    private static AdfResult nonStationaryResult() {
        return new AdfResult(0.0, 1.0, false);
    }

    /**
     * Аппроксимирует p-value по t-статистике ADF и табличным критическим значениям MacKinnon.
     */
    private static double approximatePValue(
            double tau,
            boolean includeTrend,
            boolean includeConstant,
            CriticalValueSet criticalValues
    ) {
        double cv1;
        double cv5;
        double cv10;

        if (criticalValues == CriticalValueSet.COINTEGRATION_RESIDUALS) {
            cv1 = -3.96;
            cv5 = -3.37;
            cv10 = -3.04;
        } else if (includeTrend) {
            cv1 = -3.963;
            cv5 = -3.413;
            cv10 = -3.128;
        } else if (includeConstant) {
            cv1 = -3.433;
            cv5 = -2.863;
            cv10 = -2.567;
        } else {
            cv1 = -2.576;
            cv5 = -1.950;
            cv10 = -1.620;
        }

        if (tau <= cv1) {
            return 0.001;
        }
        if (tau <= cv5) {
            return interpolatePValue(tau, cv1, cv5, 0.01, 0.05);
        }
        if (tau <= cv10) {
            return interpolatePValue(tau, cv5, cv10, 0.05, 0.10);
        }

        double z = (tau - cv10) / Math.max(0.001, Math.abs(cv10));
        double p = NORMAL.cumulativeProbability(z);
        return Math.min(0.99, Math.max(0.10, p));
    }

    /** Линейная интерполяция p-value между двумя критическими точками. */
    private static double interpolatePValue(double tau, double lowerStat, double upperStat,
                                            double lowerP, double upperP) {
        double ratio = (tau - lowerStat) / (upperStat - lowerStat);
        return lowerP + ratio * (upperP - lowerP);
    }
}
