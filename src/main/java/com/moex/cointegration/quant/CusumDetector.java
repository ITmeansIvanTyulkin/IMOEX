package com.moex.cointegration.quant;

/**
 * Простой двухсторонний CUSUM на нормированном ряде (например Z или остатки).
 */
public final class CusumDetector {

    private CusumDetector() {
    }

    /**
     * @param series    ряд (NaN пропускаются)
     * @param threshold порог накопления (типично 4–6 на Z)
     * @param drift     допустимый дрейф на шаг (типично 0.5)
     * @return true если |CUSUM| превысил threshold
     */
    public static boolean detect(double[] series, double threshold, double drift) {
        if (series == null || series.length < 5 || threshold <= 0) {
            return false;
        }
        double gp = 0.0;
        double gn = 0.0;
        double d = Math.max(0.0, drift);
        for (double x : series) {
            if (Double.isNaN(x)) {
                continue;
            }
            gp = Math.max(0.0, gp + x - d);
            gn = Math.min(0.0, gn + x + d);
            if (gp > threshold || gn < -threshold) {
                return true;
            }
        }
        return false;
    }

    /** CUSUM на хвосте ряда (последние lookback точек). */
    public static boolean detectTail(double[] series, int lookback, double threshold, double drift) {
        if (series == null || series.length == 0) {
            return false;
        }
        int from = Math.max(0, series.length - Math.max(lookback, 5));
        double[] tail = java.util.Arrays.copyOfRange(series, from, series.length);
        return detect(tail, threshold, drift);
    }
}
