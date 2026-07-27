package com.moex.cointegration.quant.trend;

import com.moex.cointegration.model.Candle;
import com.moex.cointegration.quant.microstructure.CandleMicrostructureAnalyzer;

import java.util.List;

/**
 * Absorption: высокий объём при узком диапазоне (ATAS cluster absorption).
 */
public final class TrendAbsorptionDetector {

    private TrendAbsorptionDetector() {
    }

    public static boolean absorptionAtLevel(
            List<Candle> candles,
            int lookback,
            double volumeMult,
            double maxRangeBps
    ) {
        if (candles == null || candles.size() < lookback + 1) {
            return false;
        }
        Candle last = candles.get(candles.size() - 1);
        List<Candle> hist = candles.subList(candles.size() - lookback - 1, candles.size() - 1);

        double medianVol = hist.stream()
                .mapToDouble(Candle::volume)
                .filter(v -> v > 0)
                .sorted()
                .average()
                .orElse(0);
        if (medianVol <= 0 || last.volume() < medianVol * volumeMult) {
            return false;
        }
        return CandleMicrostructureAnalyzer.spreadProxyBps(last) <= maxRangeBps;
    }
}
