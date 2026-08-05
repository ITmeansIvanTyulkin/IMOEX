package com.moex.cointegration.quant.microstructure;

import com.moex.cointegration.model.Candle;

import java.util.List;

/**
 * Footprint proxy: buy/sell volume imbalance внутри бара (ATAS-style без tick tape).
 */
public final class FootprintAnalyzer {

    private FootprintAnalyzer() {
    }

    /**
     * @return [-1, 1]: положительный = buy pressure
     */
    public static double barImbalance(Candle c) {
        if (c.volume() <= 0 || c.high() <= c.low()) {
            return 0;
        }
        double buyShare = (c.close() - c.low()) / (c.high() - c.low());
        return 2.0 * buyShare - 1.0;
    }

    public static double volumeWeightedImbalance(List<Candle> candles, int lookback) {
        if (candles == null || candles.isEmpty()) {
            return 0;
        }
        int from = Math.max(0, candles.size() - lookback);
        double num = 0;
        double den = 0;
        for (int i = from; i < candles.size(); i++) {
            Candle c = candles.get(i);
            double v = c.volume();
            if (v <= 0) {
                continue;
            }
            num += barImbalance(c) * v;
            den += v;
        }
        return den <= 0 ? 0 : num / den;
    }
}
