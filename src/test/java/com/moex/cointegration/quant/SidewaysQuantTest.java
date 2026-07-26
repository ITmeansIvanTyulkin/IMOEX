package com.moex.cointegration.quant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdxCalculatorTest {

    @Test
    void trendingSeriesHasHigherAdxThanFlat() {
        int n = 120;
        double[] highT = new double[n];
        double[] lowT = new double[n];
        double[] closeT = new double[n];
        double[] highF = new double[n];
        double[] lowF = new double[n];
        double[] closeF = new double[n];
        for (int i = 0; i < n; i++) {
            closeT[i] = 100 + i * 0.8;
            highT[i] = closeT[i] + 0.5;
            lowT[i] = closeT[i] - 0.5;
            closeF[i] = 100 + Math.sin(i / 3.0);
            highF[i] = closeF[i] + 0.3;
            lowF[i] = closeF[i] - 0.3;
        }
        double adxTrend = AdxCalculator.lastAdx(highT, lowT, closeT, 14);
        double adxFlat = AdxCalculator.lastAdx(highF, lowF, closeF, 14);
        assertTrue(adxTrend > adxFlat);
        assertTrue(adxTrend > 25);
    }
}

class CusumDetectorTest {

    @Test
    void detectsStructuralJump() {
        double[] z = new double[60];
        for (int i = 0; i < 40; i++) {
            z[i] = (i % 2 == 0 ? 0.2 : -0.2);
        }
        for (int i = 40; i < 60; i++) {
            z[i] = 2.5;
        }
        assertTrue(CusumDetector.detectTail(z, 40, 5.0, 0.5));
        assertFalse(CusumDetector.detect(new double[]{0.1, -0.1, 0.05}, 5.0, 0.5));
    }
}

class AdaptiveStopTest {

    @Test
    void widensWhenShortVolHigh() {
        double[] calm = new double[300];
        double[] wild = new double[300];
        for (int i = 0; i < 300; i++) {
            calm[i] = Math.sin(i / 10.0) * 0.01;
            wild[i] = Math.sin(i / 10.0) * (i > 250 ? 0.08 : 0.01);
        }
        double sCalm = AdaptiveStop.stopZ(calm, 2.5, 4.0, 20, 252);
        double sWild = AdaptiveStop.stopZ(wild, 2.5, 4.0, 20, 252);
        assertTrue(sWild >= sCalm);
    }
}
