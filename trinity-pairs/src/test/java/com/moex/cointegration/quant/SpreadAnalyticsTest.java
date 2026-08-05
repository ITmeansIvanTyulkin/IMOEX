package com.moex.cointegration.quant;

import com.moex.cointegration.model.OlsResult;
import com.moex.cointegration.model.SpreadPoint;
import com.moex.cointegration.model.TradingMetrics;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpreadAnalyticsTest {

    @Test
    void computeSpreadUsesCointegratingEquation() {
        double[] y = {2.0, 3.5, 5.0};
        double[] x = {1.0, 2.0, 3.0};
        double[] spread = SpreadAnalytics.computeSpread(y, x, 0.5, 1.5);
        assertEquals(0.0, spread[0], 1e-12);
        assertEquals(0.0, spread[1], 1e-12);
        assertEquals(0.0, spread[2], 1e-12);
    }

    @Test
    void computeSpreadRejectsLengthMismatch() {
        assertThrows(IllegalArgumentException.class,
                () -> SpreadAnalytics.computeSpread(new double[]{1}, new double[]{1, 2}, 0, 1));
    }

    @Test
    void zScoresHaveZeroMeanAndUnitVariance() {
        double[] spread = {1.0, 2.0, 3.0, 4.0, 5.0};
        double[] z = SpreadAnalytics.zScores(spread);
        double mean = 0.0;
        for (double v : z) {
            mean += v;
        }
        mean /= z.length;
        assertEquals(0.0, mean, 1e-12);

        double var = 0.0;
        for (double v : z) {
            var += v * v;
        }
        var /= (z.length - 1);
        assertEquals(1.0, var, 1e-12);
    }

    @Test
    void zScoresOnConstantSeriesAreZero() {
        double[] z = SpreadAnalytics.zScores(new double[]{3.0, 3.0, 3.0, 3.0});
        for (double v : z) {
            assertEquals(0.0, v, 1e-12);
        }
    }

    @Test
    void rollingZScoresAreCausalAndWarmupIsNan() {
        double[] spread = generateAr1(80, 0.5, 1);
        int window = 20;
        double[] z = SpreadAnalytics.rollingZScores(spread, window);

        for (int i = 0; i < window - 1; i++) {
            assertTrue(Double.isNaN(z[i]), "warmup index " + i);
        }
        assertFalse(Double.isNaN(z[window - 1]));

        int i = spread.length - 1;
        double sum = 0.0;
        for (int j = i - window + 1; j <= i; j++) {
            sum += spread[j];
        }
        double mean = sum / window;
        double var = 0.0;
        for (int j = i - window + 1; j <= i; j++) {
            double d = spread[j] - mean;
            var += d * d;
        }
        double std = Math.sqrt(var / (window - 1));
        assertEquals((spread[i] - mean) / std, z[i], 1e-12);
    }

    @Test
    void rollingZRejectsTinyWindow() {
        assertThrows(IllegalArgumentException.class,
                () -> SpreadAnalytics.rollingZScores(new double[]{1, 2, 3}, 1));
    }

    @Test
    void halfLifeRecoversAr1Phi() {
        double[] spread = generateAr1(2000, 0.5, 42);
        double hl = SpreadAnalytics.halfLifeDays(spread);
        assertEquals(1.0, hl, 0.15);
    }

    @Test
    void halfLifeIsNanWhenExplosive() {
        double[] series = new double[200];
        series[0] = 0.1;
        for (int i = 1; i < series.length; i++) {
            series[i] = 1.05 * series[i - 1] + 0.01;
        }
        assertTrue(Double.isNaN(SpreadAnalytics.halfLifeDays(series)));
    }

    @Test
    void halfLifeNanOnShortSeries() {
        assertTrue(Double.isNaN(SpreadAnalytics.halfLifeDays(new double[]{1.0, 2.0})));
    }

    @Test
    void backtestEntersOnExtremeZAndExitsNearZero() {
        int n = 40;
        double[] spread = new double[n];
        double[] z = new double[n];
        for (int i = 0; i < n; i++) {
            if (i < 10) {
                spread[i] = 0.0;
                z[i] = 0.0;
            } else if (i < 20) {
                spread[i] = 1.0;
                z[i] = 2.5; // short entry
            } else if (i < 25) {
                spread[i] = 0.5;
                z[i] = 0.5; // still short, approaching exit
            } else {
                spread[i] = 0.0;
                z[i] = -0.1; // crossed through 0 → exit
            }
        }

        TradingMetrics metrics = SpreadAnalytics.simulateMeanReversion(
                spread, 0.0, 2.0, 0.0, z, Double.NaN, 0);
        assertTrue(metrics.tradeCount() >= 2, "trades=" + metrics.tradeCount());
        assertTrue(metrics.maxDrawdown() >= 0.0 && metrics.maxDrawdown() <= 1.0);
        assertTrue(Math.abs(metrics.totalReturn()) < 100.0, "ret=" + metrics.totalReturn());
    }

    @Test
    void backtestNearZeroSpreadDoesNotExplode() {
        double[] spread = new double[100];
        Random random = new Random(99);
        for (int i = 0; i < spread.length; i++) {
            spread[i] = random.nextGaussian() * 1e-8;
        }
        spread[50] = 5.0;
        spread[51] = 0.0;

        TradingMetrics metrics = SpreadAnalytics.simulateMeanReversion(spread, 0.0005, 2.0, 0.0);
        assertTrue(metrics.maxDrawdown() <= 1.0 + 1e-9, "MDD=" + metrics.maxDrawdown());
        assertTrue(Math.abs(metrics.totalReturn()) < 1_000.0, "ret=" + metrics.totalReturn());
    }

    @Test
    void stopLossExitsWhenAbsZExceedsStop() {
        int n = 30;
        double[] spread = new double[n];
        double[] z = new double[n];
        for (int i = 0; i < n; i++) {
            if (i < 5) {
                spread[i] = 0.0;
                z[i] = 0.0;
            } else if (i < 10) {
                spread[i] = 1.0;
                z[i] = 2.0; // enter short
            } else {
                spread[i] = 2.0;
                z[i] = 3.0; // stop at |Z|>=2.5
            }
        }

        TradingMetrics withStop = SpreadAnalytics.simulateMeanReversion(
                spread, 0.0, 1.5, 0.0, z, 2.5, 0);
        assertTrue(withStop.tradeCount() >= 2, "trades=" + withStop.tradeCount());
        assertTrue(withStop.maxDrawdown() <= 1.0);
    }

    @Test
    void timeStopClosesPosition() {
        int n = 40;
        double[] spread = new double[n];
        double[] z = new double[n];
        for (int i = 0; i < n; i++) {
            if (i < 5) {
                z[i] = 0.0;
                spread[i] = 0.0;
            } else {
                z[i] = 2.5; // stay in entry zone, never cross exit
                spread[i] = 1.0;
            }
        }
        TradingMetrics metrics = SpreadAnalytics.simulateMeanReversion(
                spread, 0.0, 1.5, 0.0, z, Double.NaN, 5);
        assertTrue(metrics.tradeCount() >= 4,
                "time-stop should close and allow re-entry, trades=" + metrics.tradeCount());
    }

    @Test
    void toSeriesMapsDatesAndValues() {
        LocalDate[] dates = {LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2)};
        List<SpreadPoint> points = SpreadAnalytics.toSeries(dates, new double[]{1.5, -0.5});
        assertEquals(2, points.size());
        assertEquals(dates[0], points.get(0).date());
        assertEquals(1.5, points.get(0).value());
    }

    @Test
    void fitSpreadDelegatesToOls() {
        double[] x = {1, 2, 3, 4, 5};
        double[] y = {2, 4, 6, 8, 10};
        OlsResult ols = SpreadAnalytics.fitSpread(y, x);
        assertEquals(2.0, ols.beta(), 1e-9);
        assertEquals(0.0, ols.intercept(), 1e-9);
    }

    private static double[] generateAr1(int n, double phi, long seed) {
        Random random = new Random(seed);
        double[] series = new double[n];
        for (int t = 1; t < n; t++) {
            series[t] = phi * series[t - 1] + random.nextGaussian() * 0.2;
        }
        return series;
    }
}
