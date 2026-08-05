package com.moex.cointegration.quant.microstructure;

import com.moex.cointegration.model.Candle;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Volume cluster: уровень цены с аномально высоким объёмом (ATAS cluster proxy).
 */
public final class ClusterDetector {

    private ClusterDetector() {
    }

    public record ClusterHit(double priceLevel, double volumeShare, boolean atEdge) {
    }

    public static ClusterHit strongestCluster(List<Candle> candles, int lookback, double volumeMult) {
        if (candles == null || candles.size() < 3) {
            return null;
        }
        int from = Math.max(0, candles.size() - lookback);
        Map<Long, Double> volByBucket = new HashMap<>();
        double total = 0;
        for (int i = from; i < candles.size(); i++) {
            Candle c = candles.get(i);
            if (c.volume() <= 0) {
                continue;
            }
            long bucket = Math.round(VolumeProfileCalculator.typicalPrice(c) * 100);
            volByBucket.merge(bucket, c.volume(), Double::sum);
            total += c.volume();
        }
        if (total <= 0 || volByBucket.isEmpty()) {
            return null;
        }
        long bestBucket = 0;
        double bestVol = 0;
        for (var e : volByBucket.entrySet()) {
            if (e.getValue() > bestVol) {
                bestVol = e.getValue();
                bestBucket = e.getKey();
            }
        }
        double median = medianBarVolume(candles.subList(from, candles.size()));
        double share = bestVol / total;
        boolean edge = share >= 0.35 && bestVol >= median * volumeMult;
        return new ClusterHit(bestBucket / 100.0, share, edge);
    }

    private static double medianBarVolume(List<Candle> candles) {
        double[] v = candles.stream().mapToDouble(Candle::volume).filter(x -> x > 0).sorted().toArray();
        if (v.length == 0) {
            return 0;
        }
        return v[v.length / 2];
    }
}
