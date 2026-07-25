package com.moex.cointegration.quant;

/**
 * Kaufman Adaptive Moving Average (KAMA) — адаптивная скользящая средняя,
 * которая ускоряется на тренде и замедляется в боковике.
 */
public final class KaufmanAdaptiveMa {

    private KaufmanAdaptiveMa() {
    }

    /**
     * Считает KAMA по ряду значений.
     *
     * @param values   исходный ряд (спред или цена)
     * @param erPeriod период Efficiency Ratio (обычно 10)
     * @param fastSc   быстрый сглаживающий константный период (обычно 2 → SC≈0.666)
     * @param slowSc   медленный период (обычно 30 → SC≈0.0645)
     * @return массив той же длины; первые точки — NaN до прогрева
     */
    public static double[] compute(double[] values, int erPeriod, int fastSc, int slowSc) {
        double[] kama = new double[values.length];
        if (values.length == 0) {
            return kama;
        }

        double fast = 2.0 / (fastSc + 1.0);
        double slow = 2.0 / (slowSc + 1.0);

        for (int i = 0; i < values.length; i++) {
            kama[i] = Double.NaN;
        }

        if (values.length <= erPeriod) {
            return kama;
        }

        kama[erPeriod] = values[erPeriod];
        for (int i = erPeriod + 1; i < values.length; i++) {
            double change = Math.abs(values[i] - values[i - erPeriod]);
            double volatility = 0.0;
            for (int j = i - erPeriod + 1; j <= i; j++) {
                volatility += Math.abs(values[j] - values[j - 1]);
            }
            double er = volatility > 1e-12 ? change / volatility : 0.0;
            double sc = Math.pow(er * (fast - slow) + slow, 2);
            kama[i] = kama[i - 1] + sc * (values[i] - kama[i - 1]);
        }
        return kama;
    }

    /** KAMA с классическими параметрами Кауфмана: ER=10, fast=2, slow=30. */
    public static double[] computeDefault(double[] values) {
        return compute(values, 10, 2, 30);
    }
}
