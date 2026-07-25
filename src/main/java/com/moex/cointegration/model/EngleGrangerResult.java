package com.moex.cointegration.model;

/**
 * Результат двухшагового теста Энгла-Грейнджера для пары акций.
 *
 * @param tickerY       зависимая переменная (Y)
 * @param tickerX       независимая переменная (X)
 * @param intercept     intercept коинтегрирующего уравнения
 * @param hedgeRatio    beta / коэффициент хеджа
 * @param adfStatistic  ADF t-статистика на остатках
 * @param pValue        p-value теста коинтеграции
 * @param cointegrated  признак статистически значимой коинтеграции
 */
public record EngleGrangerResult(
        String tickerY,
        String tickerX,
        double intercept,
        double hedgeRatio,
        double adfStatistic,
        double pValue,
        boolean cointegrated
) {
}
