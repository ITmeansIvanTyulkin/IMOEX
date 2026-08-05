package com.moex.cointegration.quant;

import com.moex.cointegration.model.AdfResult;
import com.moex.cointegration.model.EngleGrangerResult;
import com.moex.cointegration.model.OlsResult;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты двухшагового теста Энгла-Грейнджера и связанной ADF-логики на синтетических рядах.
 */
class EngleGrangerTestTest {

    /**
     * Проверяет полный цикл Engle-Granger на искусственной коинтегрированной паре:
     * logY = 2 + 1.5·logX + AR(1)-спред. Ожидается p-value &lt; 0.05.
     */
    @Test
    void detectsCointegratedSyntheticPair() {
        int n = 1000;
        Random random = new Random(42);
        double[] logX = new double[n];
        double[] logY = new double[n];
        double[] eps = new double[n];

        logX[0] = 4.0;
        eps[0] = 0.0;
        logY[0] = 2.0 + 1.5 * logX[0] + eps[0];

        for (int t = 1; t < n; t++) {
            logX[t] = logX[t - 1] + random.nextGaussian() * 0.03;
            eps[t] = 0.40 * eps[t - 1] + random.nextGaussian() * 0.05;
            logY[t] = 2.0 + 1.5 * logX[t] + eps[t];
        }

        EngleGrangerResult result = EngleGrangerTest.test("Y", "X", logY, logX, 0.05);
        assertTrue(
                result.cointegrated(),
                "Expected synthetic cointegrated pair, stat=" + result.adfStatistic() + ", p=" + result.pValue()
        );
    }

    /**
     * Разбивает тест на два шага: OLS должен восстановить beta ≈ 1.5,
     * а ADF на остатках — подтвердить стационарность спреда.
     */
    @Test
    void olsRecoversStationarySpread() {
        int n = 1000;
        Random random = new Random(42);
        double[] logX = new double[n];
        double[] logY = new double[n];
        double[] eps = new double[n];

        logX[0] = 4.0;
        eps[0] = 0.0;
        logY[0] = 2.0 + 1.5 * logX[0];

        for (int t = 1; t < n; t++) {
            logX[t] = logX[t - 1] + random.nextGaussian() * 0.03;
            eps[t] = 0.40 * eps[t - 1] + random.nextGaussian() * 0.05;
            logY[t] = 2.0 + 1.5 * logX[t] + eps[t];
        }

        OlsResult ols = OlsRegression.regressYOnX(logY, logX);
        AdfResult adf = AdfTest.testWithAutoLag(
                ols.residuals(),
                EngleGrangerTest.schwertMaxLag(ols.residuals().length),
                false,
                false,
                AdfTest.CriticalValueSet.COINTEGRATION_RESIDUALS,
                AdfTest.LagCriterion.BIC
        );

        assertTrue(Math.abs(ols.beta() - 1.5) < 0.05, "Unexpected hedge ratio: " + ols.beta());
        assertTrue(adf.pValue() < 0.05, "OLS residuals should be stationary, stat="
                + adf.testStatistic() + ", p=" + adf.pValue());
    }

    /**
     * Проверяет ADF без лагов на чистом стационарном AR(1)-ряде.
     * t-статистика должна быть ниже 5%-го критического значения MacKinnon (−3.37).
     */
    @Test
    void stationaryResidualsRejectUnitRootWithZeroLag() {
        double[] residuals = generateAr1(600, 0.35, 11);

        AdfResult adf = AdfTest.test(
                residuals,
                0,
                false,
                false,
                AdfTest.CriticalValueSet.COINTEGRATION_RESIDUALS
        );
        assertTrue(
                adf.testStatistic() < -3.37,
                "Stationary AR(1) residuals should reject unit root, stat=" + adf.testStatistic() + ", p=" + adf.pValue()
        );
    }

    /**
     * Убеждается, что подбор лагов по BIC не переусложняет модель
     * и корректно обнаруживает mean reversion на AR(1)-спреде.
     */
    @Test
    void bicSelectsParsimoniousLagForAr1Spread() {
        double[] residuals = generateAr1(600, 0.35, 11);

        AdfResult bic = AdfTest.testWithAutoLag(
                residuals, 4, false, false,
                AdfTest.CriticalValueSet.COINTEGRATION_RESIDUALS,
                AdfTest.LagCriterion.BIC
        );

        assertTrue(bic.pValue() < 0.05,
                "BIC-based ADF should detect mean reversion, stat=" + bic.testStatistic() + ", p=" + bic.pValue());
    }

    /**
     * Smoke-тест: ADF на случайном блуждании не падает и возвращает валидный p-value.
     */
    @Test
    void adfDoesNotThrowOnRealisticSeries() {
        int n = 300;
        Random random = new Random(7);
        double[] series = new double[n];
        series[0] = 100.0;
        for (int t = 1; t < n; t++) {
            series[t] = series[t - 1] + random.nextGaussian();
        }

        AdfResult result = AdfTest.test(series, 1, true);
        assertFalse(Double.isNaN(result.pValue()));
    }

    /**
     * Генерирует стационарный AR(1) ряд: x_t = phi·x_{t-1} + epsilon_t.
     *
     * @param n    длина ряда
     * @param phi  коэффициент автокорреляции (|phi| &lt; 1 для стационарности)
     * @param seed зерно ГСЧ для воспроизводимости
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
