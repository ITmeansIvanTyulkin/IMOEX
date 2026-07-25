package com.moex.cointegration.model;

/**
 * Результат Augmented Dickey-Fuller теста на стационарность.
 *
 * @param testStatistic t-статистика коэффициента при лагированном уровне
 * @param pValue        аппроксимированный p-value
 * @param stationary    {@code true}, если p-value ниже порога значимости
 */
public record AdfResult(
        double testStatistic,
        double pValue,
        boolean stationary
) {
}
