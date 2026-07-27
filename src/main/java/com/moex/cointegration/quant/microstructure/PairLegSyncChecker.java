package com.moex.cointegration.quant.microstructure;

import com.moex.cointegration.model.TradingSignal;

/**
 * Согласованность order-flow proxy между ногами пары.
 */
public final class PairLegSyncChecker {

    private PairLegSyncChecker() {
    }

    /**
     * @return alignment score 0..1 (1 = идеально для сигнала)
     */
    public static double alignmentScore(
            TradingSignal signal,
            CandleMicrostructure legY,
            CandleMicrostructure legX
    ) {
        if (signal != TradingSignal.LONG_SPREAD && signal != TradingSignal.SHORT_SPREAD) {
            return 1.0;
        }
        // LONG spread: buy Y, short X — хотим Y не сильно в sell delta, X не сильно в buy delta
        double yTarget = signal == TradingSignal.LONG_SPREAD ? 1.0 : -1.0;
        double xTarget = signal == TradingSignal.LONG_SPREAD ? -1.0 : 1.0;
        double yScore = directionalScore(legY.deltaProxy(), yTarget);
        double xScore = directionalScore(legX.deltaProxy(), xTarget);
        return (yScore + xScore) / 2.0;
    }

    public static double volumeRatio(CandleMicrostructure legY, CandleMicrostructure legX) {
        double lo = Math.min(legY.volume(), legX.volume());
        double hi = Math.max(legY.volume(), legX.volume());
        if (lo <= 0) {
            return hi > 0 ? Double.POSITIVE_INFINITY : 1.0;
        }
        return hi / lo;
    }

    private static double directionalScore(double delta, double targetSign) {
        if (targetSign > 0) {
            return clamp01((delta + 1.0) / 2.0);
        }
        return clamp01((1.0 - delta) / 2.0);
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
