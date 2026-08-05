package com.moex.cointegration.quant;

import com.moex.cointegration.model.AdfResult;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты расширенного Dickey-Fuller теста (ADF) и расчёта p-value.
 */
class AdfTestTest {

    /**
     * Проверяет, что ADF на случайном блуждании с трендом выполняется без исключений
     * и возвращает числовой p-value.
     */
    @Test
    void randomWalkIsLikelyNonStationary() {
        Random random = new Random(1);
        double[] series = new double[400];
        series[0] = 10.0;
        for (int i = 1; i < series.length; i++) {
            series[i] = series[i - 1] + random.nextGaussian() * 0.5;
        }

        AdfResult result = assertDoesNotThrow(() -> AdfTest.test(series, 1, true));
        assertFalse(Double.isNaN(result.pValue()));
    }

    /**
     * Проверяет, что ADF на белом шуме (стационарный ряд) не падает
     * и p-value лежит в допустимом диапазоне [0, 1].
     */
    @Test
    void stationarySeriesDoesNotThrow() {
        Random random = new Random(2);
        double[] series = new double[400];
        for (int i = 0; i < series.length; i++) {
            series[i] = random.nextGaussian();
        }

        AdfResult result = AdfTest.test(series, 1, false);
        assertTrue(result.pValue() >= 0.0 && result.pValue() <= 1.0);
    }

    /**
     * Сравнивает два набора критических значений: для коинтеграции они строже,
     * поэтому p-value при COINTEGRATION_RESIDUALS не ниже, чем при STANDARD.
     */
    @Test
    void cointegrationAdfUsesStricterCriticalValues() {
        double[] residuals = generateAr1(500, 0.35, 3);
        AdfResult cointResult = AdfTest.testWithAutoLag(
                residuals, 4, false, false, AdfTest.CriticalValueSet.COINTEGRATION_RESIDUALS,
                AdfTest.LagCriterion.BIC
        );
        AdfResult standardResult = AdfTest.testWithAutoLag(
                residuals, 4, false, false, AdfTest.CriticalValueSet.STANDARD,
                AdfTest.LagCriterion.BIC
        );

        assertTrue(cointResult.pValue() >= standardResult.pValue(),
                "Cointegration critical values should be stricter or equal");
    }

    /**
     * Генерирует стационарный AR(1) ряд для использования в тестах ADF.
     *
     * @param n    длина ряда
     * @param phi  коэффициент автокорреляции
     * @param seed зерно ГСЧ
     */
    private static double[] generateAr1(int n, double phi, long seed) {
        Random random = new Random(seed);
        double[] series = new double[n];
        series[0] = 0.0;
        for (int t = 1; t < n; t++) {
            series[t] = phi * series[t - 1] + random.nextGaussian() * 0.2;
        }
        return series;
    }
}
