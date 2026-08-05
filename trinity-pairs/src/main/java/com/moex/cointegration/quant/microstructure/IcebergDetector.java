package com.moex.cointegration.quant.microstructure;

import com.moex.cointegration.model.Candle;

import java.util.List;

/**
 * Iceberg proxy: повторяющийся высокий объём при узком диапазоне (hidden liquidity).
 */
public final class IcebergDetector {

    private IcebergDetector() {
    }

    public static boolean suspectedIceberg(
            List<Candle> candles,
            int lookback,
            double volumeMult,
            double maxRangeBps
    ) {
        if (candles == null || candles.size() < lookback + 1) {
            return false;
        }
        int hits = 0;
        List<Candle> window = candles.subList(candles.size() - lookback, candles.size());
        double median = medianVolume(window);
        if (median <= 0) {
            return false;
        }
        for (Candle c : window) {
            if (c.volume() >= median * volumeMult
                    && CandleMicrostructureAnalyzer.spreadProxyBps(c) <= maxRangeBps) {
                hits++;
            }
        }
        return hits >= Math.max(2, lookback / 4);
    }

    private static double medianVolume(List<Candle> candles) {
        double[] v = candles.stream().mapToDouble(Candle::volume).filter(x -> x > 0).sorted().toArray();
        if (v.length == 0) {
            return 0;
        }
        return v[v.length / 2];
    }
}
