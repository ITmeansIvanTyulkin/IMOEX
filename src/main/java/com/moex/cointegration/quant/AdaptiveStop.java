package com.moex.cointegration.quant;

/**
 * Адаптивный stop по Z: шире при росте волатильности спреда.
 */
public final class AdaptiveStop {

    private AdaptiveStop() {
    }

    /**
     * @param spread    ряд спреда
     * @param baseStop  базовый stop (напр. 2.5)
     * @param capStop   верхняя крышка (напр. 4.0)
     * @param shortWin  короткое окно σ (20)
     * @param longWin   длинное окно σ (252)
     */
    public static double stopZ(
            double[] spread,
            double baseStop,
            double capStop,
            int shortWin,
            int longWin
    ) {
        if (spread == null || spread.length < shortWin + 2) {
            return baseStop;
        }
        double sShort = stdTail(spread, shortWin);
        double sLong = stdTail(spread, Math.min(longWin, spread.length));
        if (sShort <= 0 || sLong <= 0 || Double.isNaN(sShort) || Double.isNaN(sLong)) {
            return baseStop;
        }
        double ratio = sShort / sLong;
        double adaptive = baseStop + 0.5 * Math.log(Math.max(ratio, 1e-6));
        return Math.min(capStop, Math.max(baseStop * 0.8, adaptive));
    }

    private static double stdTail(double[] v, int win) {
        int n = Math.min(win, v.length);
        int from = v.length - n;
        double mean = 0.0;
        int count = 0;
        for (int i = from; i < v.length; i++) {
            if (!Double.isNaN(v[i])) {
                mean += v[i];
                count++;
            }
        }
        if (count < 2) {
            return Double.NaN;
        }
        mean /= count;
        double var = 0.0;
        for (int i = from; i < v.length; i++) {
            if (!Double.isNaN(v[i])) {
                double d = v[i] - mean;
                var += d * d;
            }
        }
        return Math.sqrt(var / (count - 1));
    }
}
