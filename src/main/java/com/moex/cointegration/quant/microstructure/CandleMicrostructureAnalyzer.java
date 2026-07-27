package com.moex.cointegration.quant.microstructure;

import com.moex.cointegration.model.Candle;

import java.util.Arrays;
import java.util.List;

/**
 * Анализ последнего бара: relative volume, spread proxy, delta proxy.
 */
public final class CandleMicrostructureAnalyzer {

    private CandleMicrostructureAnalyzer() {
    }

    public static CandleMicrostructure analyze(List<Candle> candles, int medianLookback) {
        if (candles == null || candles.isEmpty()) {
            return new CandleMicrostructure(0, Double.POSITIVE_INFINITY, 0, 0);
        }
        Candle last = candles.get(candles.size() - 1);
        int from = Math.max(0, candles.size() - medianLookback - 1);
        List<Candle> hist = candles.subList(from, candles.size() - 1);

        double medianVol = medianVolume(hist);
        double rvol = medianVol <= 0 ? (last.volume() > 0 ? 1.0 : 0) : last.volume() / medianVol;
        double spreadBps = spreadProxyBps(last);
        double delta = deltaProxy(last);
        return new CandleMicrostructure(rvol, spreadBps, delta, last.volume());
    }

    /** (close-low)/(high-low): +1 = закрытие у high, -1 = у low. */
    public static double deltaProxy(Candle c) {
        double range = c.high() - c.low();
        if (range <= 1e-12 || c.close() <= 0) {
            return 0;
        }
        double mid = (c.high() + c.low()) / 2.0;
        return (c.close() - mid) / (range / 2.0);
    }

    public static double spreadProxyBps(Candle c) {
        if (c.close() <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return (c.high() - c.low()) / c.close() * 10_000.0;
    }

    private static double medianVolume(List<Candle> candles) {
        if (candles.isEmpty()) {
            return 0;
        }
        double[] v = candles.stream()
                .mapToDouble(Candle::volume)
                .filter(x -> x > 0 && !Double.isNaN(x))
                .toArray();
        if (v.length == 0) {
            return 0;
        }
        Arrays.sort(v);
        return v[v.length / 2];
    }
}
