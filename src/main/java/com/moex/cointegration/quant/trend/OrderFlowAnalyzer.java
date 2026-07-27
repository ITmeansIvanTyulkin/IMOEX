package com.moex.cointegration.quant.trend;

import com.moex.cointegration.model.Candle;
import com.moex.cointegration.quant.microstructure.CandleMicrostructureAnalyzer;

import java.util.List;

/**
 * Order-flow delta momentum (ATAS footprint proxy) для будущей трендовой стратегии.
 */
public final class OrderFlowAnalyzer {

    private OrderFlowAnalyzer() {
    }

    /**
     * Средний delta-proxy последних {@code lookback} баров, взвешенный объёмом.
     *
     * @return [-1, 1]: &gt;0 buy pressure, &lt;0 sell pressure
     */
    public static double volumeWeightedDeltaMomentum(List<Candle> candles, int lookback) {
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
            num += CandleMicrostructureAnalyzer.deltaProxy(c) * v;
            den += v;
        }
        return den <= 0 ? 0 : num / den;
    }

    public static boolean bullishMomentum(List<Candle> candles, int lookback, double threshold) {
        return volumeWeightedDeltaMomentum(candles, lookback) >= threshold;
    }

    public static boolean bearishMomentum(List<Candle> candles, int lookback, double threshold) {
        return volumeWeightedDeltaMomentum(candles, lookback) <= -threshold;
    }
}
