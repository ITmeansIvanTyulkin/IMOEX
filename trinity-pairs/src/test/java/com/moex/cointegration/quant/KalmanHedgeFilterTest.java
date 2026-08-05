package com.moex.cointegration.quant;

import com.moex.cointegration.model.TradingMetrics;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KalmanHedgeFilterTest {

    @Test
    void recoversStableHedgeOnCointegratedPair() {
        int n = 800;
        Random random = new Random(7);
        double[] logX = new double[n];
        double[] logY = new double[n];
        double eps = 0.0;
        logX[0] = 4.0;
        logY[0] = 1.0 + 1.4 * logX[0];
        for (int t = 1; t < n; t++) {
            logX[t] = logX[t - 1] + random.nextGaussian() * 0.02;
            eps = 0.5 * eps + random.nextGaussian() * 0.03;
            logY[t] = 1.0 + 1.4 * logX[t] + eps;
        }

        KalmanHedgeFilter.Result result = KalmanHedgeFilter.filter(logY, logX, 1e-5, 1e-3);
        assertEquals(n, result.beta().length);
        assertTrue(Math.abs(result.lastBeta() - 1.4) < 0.15, "beta=" + result.lastBeta());
        assertTrue(Math.abs(result.lastIntercept() - 1.0) < 0.3, "alpha=" + result.lastIntercept());
    }

    @Test
    void borrowCostLowersSharpeVersusZeroBorrow() {
        double[] spread = new double[80];
        double[] z = new double[80];
        for (int i = 0; i < 80; i++) {
            if (i < 10) {
                z[i] = 0;
                spread[i] = 0;
            } else if (i < 40) {
                z[i] = 2.5;
                spread[i] = 1.0 - 0.01 * (i - 10); // slowly mean-reverting while short
            } else {
                z[i] = -0.1;
                spread[i] = 0.0;
            }
        }
        TradingMetrics noBorrow = SpreadAnalytics.simulateMeanReversion(
                spread, 0.0, 2.0, 0.0, z, Double.NaN, 0, 0.0);
        TradingMetrics withBorrow = SpreadAnalytics.simulateMeanReversion(
                spread, 0.0, 2.0, 0.0, z, Double.NaN, 0, 0.20);
        assertTrue(withBorrow.totalReturn() <= noBorrow.totalReturn() + 1e-12,
                "borrow should not improve return");
    }
}
