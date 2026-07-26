package com.moex.cointegration.quant;

import com.moex.cointegration.model.AdfResult;
import com.moex.cointegration.model.EngleGrangerResult;
import com.moex.cointegration.model.OlsResult;

/**
 * Двухшаговый тест коинтеграции Энгла-Грейнджера для пары log-цен.
 */
public final class EngleGrangerTest {

    private EngleGrangerTest() {
    }

    /**
     * Тест Энгла-Грейнджера для пары (Y, X).
     * <ol>
     *   <li>OLS: logY = intercept + beta·logX</li>
     *   <li>ADF на остатках без константы, лаги по BIC, критические значения MacKinnon для коинтеграции</li>
     * </ol>
     *
     * @param tickerY      тикер зависимой переменной
     * @param tickerX      тикер независимой переменной
     * @param y            log-цены Y
     * @param x            log-цены X
     * @param pThreshold   порог p-value для признания коинтеграции
     */
    public static EngleGrangerResult test(String tickerY, String tickerX, double[] y, double[] x, double pThreshold) {
        OlsResult ols = OlsRegression.regressYOnX(y, x);
        int maxLag = schwertMaxLag(ols.residuals().length);
        AdfResult adf = AdfTest.testWithAutoLag(
                ols.residuals(),
                maxLag,
                false,
                false,
                AdfTest.CriticalValueSet.COINTEGRATION_RESIDUALS,
                AdfTest.LagCriterion.BIC
        );
        boolean cointegrated = adf.pValue() < pThreshold;

        return new EngleGrangerResult(
                tickerY,
                tickerX,
                ols.intercept(),
                ols.beta(),
                adf.testStatistic(),
                adf.pValue(),
                cointegrated,
                ols.rSquared()
        );
    }

    /**
     * Правило Schwert (1989) для максимального числа лагов ADF: 12·(T/100)^0.25, не более 8.
     *
     * @param sampleSize длина ряда остатков
     */
    static int schwertMaxLag(int sampleSize) {
        int rule = (int) Math.floor(12.0 * Math.pow(sampleSize / 100.0, 0.25));
        return Math.min(Math.max(rule, 0), 8);
    }
}
