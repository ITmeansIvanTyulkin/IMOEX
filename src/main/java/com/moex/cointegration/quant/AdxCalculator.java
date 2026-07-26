package com.moex.cointegration.quant;

/**
 * Wilder ADX (Average Directional Index) по OHLC.
 */
public final class AdxCalculator {

    private AdxCalculator() {
    }

    /**
     * @param high  highs
     * @param low   lows
     * @param close closes
     * @param period обычно 14
     * @return последний ADX или NaN если ряда мало
     */
    public static double lastAdx(double[] high, double[] low, double[] close, int period) {
        double[] series = adxSeries(high, low, close, period);
        if (series.length == 0) {
            return Double.NaN;
        }
        for (int i = series.length - 1; i >= 0; i--) {
            if (!Double.isNaN(series[i])) {
                return series[i];
            }
        }
        return Double.NaN;
    }

    public static double[] adxSeries(double[] high, double[] low, double[] close, int period) {
        int n = close.length;
        if (high.length != n || low.length != n || period < 2 || n < period * 2) {
            return new double[0];
        }

        double[] tr = new double[n];
        double[] plusDm = new double[n];
        double[] minusDm = new double[n];
        tr[0] = high[0] - low[0];
        for (int i = 1; i < n; i++) {
            double up = high[i] - high[i - 1];
            double down = low[i - 1] - low[i];
            plusDm[i] = up > down && up > 0 ? up : 0.0;
            minusDm[i] = down > up && down > 0 ? down : 0.0;
            double hl = high[i] - low[i];
            double hc = Math.abs(high[i] - close[i - 1]);
            double lc = Math.abs(low[i] - close[i - 1]);
            tr[i] = Math.max(hl, Math.max(hc, lc));
        }

        double atr = sum(tr, 1, period);
        double plusDiSmooth = sum(plusDm, 1, period);
        double minusDiSmooth = sum(minusDm, 1, period);
        double[] dx = new double[n];
        double[] adx = new double[n];
        java.util.Arrays.fill(adx, Double.NaN);

        for (int i = period; i < n; i++) {
            if (i > period) {
                atr = atr - atr / period + tr[i];
                plusDiSmooth = plusDiSmooth - plusDiSmooth / period + plusDm[i];
                minusDiSmooth = minusDiSmooth - minusDiSmooth / period + minusDm[i];
            }
            double plusDi = atr > 0 ? 100.0 * plusDiSmooth / atr : 0.0;
            double minusDi = atr > 0 ? 100.0 * minusDiSmooth / atr : 0.0;
            double den = plusDi + minusDi;
            dx[i] = den > 0 ? 100.0 * Math.abs(plusDi - minusDi) / den : 0.0;
        }

        int start = period * 2 - 1;
        if (start >= n) {
            return adx;
        }
        double adxSmooth = 0.0;
        for (int i = period; i <= start; i++) {
            adxSmooth += dx[i];
        }
        adxSmooth /= period;
        adx[start] = adxSmooth;
        for (int i = start + 1; i < n; i++) {
            adxSmooth = (adxSmooth * (period - 1) + dx[i]) / period;
            adx[i] = adxSmooth;
        }
        return adx;
    }

    private static double sum(double[] v, int fromInclusive, int count) {
        double s = 0.0;
        int end = Math.min(v.length, fromInclusive + count);
        for (int i = fromInclusive; i < end; i++) {
            s += v[i];
        }
        return s;
    }
}
