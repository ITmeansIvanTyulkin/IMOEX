package com.moex.cointegration.quant.trend;

import com.moex.cointegration.model.Candle;
import com.moex.cointegration.quant.microstructure.VolumeProfile;
import com.moex.cointegration.quant.microstructure.VolumeProfileCalculator;

import java.util.List;

/**
 * Breakout из value area (ATAS volume profile) — база для трендовой стратегии.
 */
public final class ValueAreaBreakoutDetector {

    public enum BreakoutDirection { NONE, UP, DOWN }

    private ValueAreaBreakoutDetector() {
    }

    public static BreakoutDirection detect(
            List<Candle> candles,
            int profileLookback,
            int confirmBars,
            double minDelta
    ) {
        if (candles == null || candles.size() < profileLookback + confirmBars) {
            return BreakoutDirection.NONE;
        }
        int split = candles.size() - confirmBars;
        List<Candle> profileWindow = candles.subList(split - profileLookback, split);
        VolumeProfile profile = VolumeProfileCalculator.fromCandles(profileWindow, profileLookback);

        if (Double.isNaN(profile.vahPrice()) || Double.isNaN(profile.valPrice())) {
            return BreakoutDirection.NONE;
        }

        List<Candle> confirm = candles.subList(split, candles.size());
        double deltaMom = OrderFlowAnalyzer.volumeWeightedDeltaMomentum(confirm, confirmBars);

        boolean up = confirm.stream().allMatch(c -> c.close() > profile.vahPrice())
                && deltaMom >= minDelta;
        boolean down = confirm.stream().allMatch(c -> c.close() < profile.valPrice())
                && deltaMom <= -minDelta;

        if (up) {
            return BreakoutDirection.UP;
        }
        if (down) {
            return BreakoutDirection.DOWN;
        }
        return BreakoutDirection.NONE;
    }
}
