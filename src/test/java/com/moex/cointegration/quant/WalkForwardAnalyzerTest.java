package com.moex.cointegration.quant;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalkForwardAnalyzerTest {

    @Test
    void benjaminiHochbergRejectsOnlyLowPValues() {
        double[] p = {0.001, 0.01, 0.04, 0.20, 0.50};
        boolean[] reject = WalkForwardAnalyzer.benjaminiHochberg(p, 0.10);
        assertTrue(reject[0]);
        assertTrue(reject[1]);
        assertFalse(reject[4]);
    }

    @Test
    void walkForwardRunsOnCointegratedSyntheticPair() {
        int n = 800;
        Random random = new Random(42);
        double[] logX = new double[n];
        double[] logY = new double[n];
        double eps = 0.0;
        logX[0] = 4.0;
        logY[0] = 2.0 + 1.5 * logX[0];
        for (int t = 1; t < n; t++) {
            logX[t] = logX[t - 1] + random.nextGaussian() * 0.02;
            eps = 0.5 * eps + random.nextGaussian() * 0.03;
            logY[t] = 2.0 + 1.5 * logX[t] + eps;
        }

        WalkForwardAnalyzer.Summary summary = WalkForwardAnalyzer.evaluate(
                logY, logX, 0.05, 0.0005, 2.0, 0.0, 40, 3.5, 30,
                300, 60, 60
        );

        assertTrue(summary.windows() > 0);
        assertTrue(summary.cointegratedWindows() > 0);
        assertEquals(summary.windows(), summary.details().size());
    }
}
