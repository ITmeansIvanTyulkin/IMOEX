package com.moex.cointegration.quant;

/**
 * Двумерный Kalman-фильтр для динамического hedge: logY_t = α_t + β_t·logX_t + ε_t.
 * <p>
 * Спред для сигналов — innovation (prediction residual) до обновления состояния,
 * чтобы не было look-ahead внутри бара. Последние α/β — для размера ног.
 */
public final class KalmanHedgeFilter {

    private KalmanHedgeFilter() {
    }

    public record Result(
            double[] intercept,
            double[] beta,
            double[] spread,
            double lastIntercept,
            double lastBeta
    ) {
    }

    /**
     * Фильтрует пару log-цен. Инициализация: α=0, β=1, большая априорная неопределённость.
     *
     * @param delta дисперсия process noise (на шаг) для α и β
     * @param ve    дисперсия observation noise
     */
    public static Result filter(double[] logY, double[] logX, double delta, double ve) {
        return filter(logY, logX, 0.0, 1.0, delta, ve);
    }

    /**
     * То же с явным prior (например OLS с train-окна).
     */
    public static Result filter(
            double[] logY,
            double[] logX,
            double priorAlpha,
            double priorBeta,
            double delta,
            double ve
    ) {
        if (logY.length != logX.length) {
            throw new IllegalArgumentException("logY/logX length mismatch");
        }
        if (logY.length == 0) {
            return new Result(new double[0], new double[0], new double[0], priorAlpha, priorBeta);
        }
        if (delta < 0 || ve <= 0) {
            throw new IllegalArgumentException("delta >= 0 and ve > 0 required");
        }

        int n = logY.length;
        double[] alpha = new double[n];
        double[] beta = new double[n];
        double[] spread = new double[n];

        double a0 = priorAlpha;
        double a1 = priorBeta;
        // Prior covariance (moderately diffuse)
        double p00 = 1.0;
        double p01 = 0.0;
        double p10 = 0.0;
        double p11 = 1.0;

        for (int t = 0; t < n; t++) {
            // Predict
            p00 += delta;
            p11 += delta;

            double x = logX[t];
            double y = logY[t];
            double yHat = a0 + a1 * x;
            double innovation = y - yHat;

            // S = H P H' + R, H = [1, x]
            double s = p00 + x * (p01 + p10) + x * x * p11 + ve;
            if (s < 1e-18) {
                s = 1e-18;
            }

            double k0 = (p00 + p01 * x) / s;
            double k1 = (p10 + p11 * x) / s;

            a0 += k0 * innovation;
            a1 += k1 * innovation;

            // Joseph-lite update: P = (I - K H) P
            double h0 = 1.0;
            double h1 = x;
            double i00 = 1.0 - k0 * h0;
            double i01 = -k0 * h1;
            double i10 = -k1 * h0;
            double i11 = 1.0 - k1 * h1;

            double np00 = i00 * p00 + i01 * p10;
            double np01 = i00 * p01 + i01 * p11;
            double np10 = i10 * p00 + i11 * p10;
            double np11 = i10 * p01 + i11 * p11;

            p00 = np00;
            p01 = np01;
            p10 = np10;
            p11 = np11;

            // Symmetrize
            double mid = 0.5 * (p01 + p10);
            p01 = mid;
            p10 = mid;

            alpha[t] = a0;
            beta[t] = a1;
            spread[t] = innovation;
        }

        return new Result(alpha, beta, spread, a0, a1);
    }
}
