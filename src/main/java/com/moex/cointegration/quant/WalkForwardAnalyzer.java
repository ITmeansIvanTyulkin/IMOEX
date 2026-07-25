package com.moex.cointegration.quant;

import com.moex.cointegration.model.EngleGrangerResult;
import com.moex.cointegration.model.TradingMetrics;

import java.util.ArrayList;
import java.util.List;

/**
 * Walk-forward (rolling train/test) валидация pair-trading без look-ahead в OOS-окне.
 * <p>
 * На каждом шаге: EG на train → Kalman/OLS спред на test → rolling-Z + risk/borrow симуляция.
 */
public final class WalkForwardAnalyzer {

    private WalkForwardAnalyzer() {
    }

    public record WindowResult(
            int trainStart,
            int trainEndExclusive,
            int testStart,
            int testEndExclusive,
            boolean cointegratedOnTrain,
            double trainPValue,
            double oosSharpe,
            double oosMaxDrawdown,
            double oosTotalReturn,
            int oosTrades
    ) {
    }

    public record Summary(
            int windows,
            int cointegratedWindows,
            double meanOosSharpe,
            double medianOosSharpe,
            double meanOosMaxDrawdown,
            double meanOosReturn,
            List<WindowResult> details
    ) {
    }

    public static Summary evaluate(
            double[] logY,
            double[] logX,
            double pThreshold,
            double commissionRate,
            double zEntry,
            double zExit,
            int rollingZWindow,
            double stopZ,
            int maxHoldBars,
            int trainBars,
            int testBars,
            int stepBars
    ) {
        return evaluate(logY, logX, pThreshold, commissionRate, zEntry, zExit, rollingZWindow,
                stopZ, maxHoldBars, trainBars, testBars, stepBars, true, 1e-5, 1e-3, 0.08, true);
    }

    public static Summary evaluate(
            double[] logY,
            double[] logX,
            double pThreshold,
            double commissionRate,
            double zEntry,
            double zExit,
            int rollingZWindow,
            double stopZ,
            int maxHoldBars,
            int trainBars,
            int testBars,
            int stepBars,
            boolean useKalman,
            double kalmanDelta,
            double kalmanVe,
            double borrowRateAnnual
    ) {
        return evaluate(logY, logX, pThreshold, commissionRate, zEntry, zExit, rollingZWindow,
                stopZ, maxHoldBars, trainBars, testBars, stepBars,
                useKalman, kalmanDelta, kalmanVe, borrowRateAnnual, true);
    }

    public static Summary evaluate(
            double[] logY,
            double[] logX,
            double pThreshold,
            double commissionRate,
            double zEntry,
            double zExit,
            int rollingZWindow,
            double stopZ,
            int maxHoldBars,
            int trainBars,
            int testBars,
            int stepBars,
            boolean useKalman,
            double kalmanDelta,
            double kalmanVe,
            double borrowRateAnnual,
            boolean requireEntryReversal
    ) {
        if (logY.length != logX.length) {
            throw new IllegalArgumentException("logY/logX length mismatch");
        }
        if (trainBars < 50 || testBars < 10 || stepBars < 1) {
            throw new IllegalArgumentException("Invalid walk-forward window sizes");
        }

        List<WindowResult> windows = new ArrayList<>();
        int n = logY.length;
        for (int trainStart = 0; trainStart + trainBars + testBars <= n; trainStart += stepBars) {
            int trainEnd = trainStart + trainBars;
            int testEnd = trainEnd + testBars;

            double[] yTrain = slice(logY, trainStart, trainEnd);
            double[] xTrain = slice(logX, trainStart, trainEnd);
            EngleGrangerResult eg = EngleGrangerTest.test("Y", "X", yTrain, xTrain, pThreshold);

            if (!eg.cointegrated()) {
                windows.add(new WindowResult(
                        trainStart, trainEnd, trainEnd, testEnd,
                        false, eg.pValue(), 0.0, 0.0, 0.0, 0
                ));
                continue;
            }

            double[] yTest = slice(logY, trainEnd, testEnd);
            double[] xTest = slice(logX, trainEnd, testEnd);
            double[] spreadTest;
            if (useKalman) {
                spreadTest = KalmanHedgeFilter.filter(
                        yTest, xTest, eg.intercept(), eg.hedgeRatio(), kalmanDelta, kalmanVe
                ).spread();
            } else {
                spreadTest = SpreadAnalytics.computeSpread(yTest, xTest, eg.intercept(), eg.hedgeRatio());
            }
            int zWindow = Math.min(rollingZWindow, Math.max(2, spreadTest.length / 2));
            double[] zTest = SpreadAnalytics.rollingZScores(spreadTest, zWindow);
            TradingMetrics metrics = SpreadAnalytics.simulateMeanReversion(
                    spreadTest, commissionRate, zEntry, zExit, zTest, stopZ, maxHoldBars,
                    borrowRateAnnual, requireEntryReversal
            );

            windows.add(new WindowResult(
                    trainStart, trainEnd, trainEnd, testEnd,
                    true, eg.pValue(),
                    metrics.sharpeRatio(), metrics.maxDrawdown(), metrics.totalReturn(), metrics.tradeCount()
            ));
        }

        List<WindowResult> coint = windows.stream().filter(WindowResult::cointegratedOnTrain).toList();
        double meanSharpe = average(coint.stream().mapToDouble(WindowResult::oosSharpe).toArray());
        double meanDd = average(coint.stream().mapToDouble(WindowResult::oosMaxDrawdown).toArray());
        double meanRet = average(coint.stream().mapToDouble(WindowResult::oosTotalReturn).toArray());
        double medianSharpe = median(coint.stream().mapToDouble(WindowResult::oosSharpe).toArray());

        return new Summary(windows.size(), coint.size(), meanSharpe, medianSharpe, meanDd, meanRet, windows);
    }

    public static boolean[] benjaminiHochberg(double[] pValues, double q) {
        int m = pValues.length;
        boolean[] reject = new boolean[m];
        if (m == 0) {
            return reject;
        }
        Integer[] order = new Integer[m];
        for (int i = 0; i < m; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(pValues[a], pValues[b]));

        int maxK = -1;
        for (int k = 0; k < m; k++) {
            double threshold = q * (k + 1.0) / m;
            if (pValues[order[k]] <= threshold) {
                maxK = k;
            }
        }
        for (int k = 0; k <= maxK; k++) {
            reject[order[k]] = true;
        }
        return reject;
    }

    private static double[] slice(double[] src, int from, int toExclusive) {
        double[] out = new double[toExclusive - from];
        System.arraycopy(src, from, out, 0, out.length);
        return out;
    }

    private static double average(double[] values) {
        if (values.length == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    private static double median(double[] values) {
        if (values.length == 0) {
            return 0.0;
        }
        double[] copy = values.clone();
        java.util.Arrays.sort(copy);
        int mid = copy.length / 2;
        if (copy.length % 2 == 0) {
            return 0.5 * (copy[mid - 1] + copy[mid]);
        }
        return copy[mid];
    }
}
