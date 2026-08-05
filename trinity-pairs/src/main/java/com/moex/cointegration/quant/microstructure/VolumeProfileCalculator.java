package com.moex.cointegration.quant.microstructure;

import com.moex.cointegration.model.Candle;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Строит POC и value area (70%) по типичным ценам баров.
 */
public final class VolumeProfileCalculator {

    private VolumeProfileCalculator() {
    }

    public static VolumeProfile fromCandles(List<Candle> candles, int lookback) {
        if (candles == null || candles.isEmpty()) {
            return new VolumeProfile(Double.NaN, Double.NaN, Double.NaN, 0);
        }
        int from = Math.max(0, candles.size() - lookback);
        List<Candle> window = candles.subList(from, candles.size());

        Map<Long, Double> volByBucket = new HashMap<>();
        double totalVol = 0;
        for (Candle c : window) {
            if (c.volume() <= 0 || c.close() <= 0) {
                continue;
            }
            long bucket = priceBucket(typicalPrice(c));
            volByBucket.merge(bucket, c.volume(), Double::sum);
            totalVol += c.volume();
        }
        if (totalVol <= 0 || volByBucket.isEmpty()) {
            double last = window.get(window.size() - 1).close();
            return new VolumeProfile(last, last, last, 0);
        }

        long pocBucket = volByBucket.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(0L);
        double poc = bucketToPrice(pocBucket);

        long[] buckets = volByBucket.keySet().stream().mapToLong(Long::longValue).sorted().toArray();
        double[] vols = new double[buckets.length];
        for (int i = 0; i < buckets.length; i++) {
            vols[i] = volByBucket.get(buckets[i]);
        }
        double target = totalVol * 0.70;
        int pocIdx = Arrays.binarySearch(buckets, pocBucket);
        if (pocIdx < 0) {
            pocIdx = 0;
        }
        double acc = vols[pocIdx];
        int lo = pocIdx;
        int hi = pocIdx;
        while (acc < target && (lo > 0 || hi < buckets.length - 1)) {
            double left = lo > 0 ? vols[lo - 1] : -1;
            double right = hi < buckets.length - 1 ? vols[hi + 1] : -1;
            if (right >= left && hi < buckets.length - 1) {
                hi++;
                acc += vols[hi];
            } else if (lo > 0) {
                lo--;
                acc += vols[lo];
            } else if (hi < buckets.length - 1) {
                hi++;
                acc += vols[hi];
            } else {
                break;
            }
        }
        return new VolumeProfile(poc, bucketToPrice(buckets[hi]), bucketToPrice(buckets[lo]), totalVol);
    }

    static double typicalPrice(Candle c) {
        return (c.high() + c.low() + c.close()) / 3.0;
    }

    private static long priceBucket(double price) {
        return Math.round(price * 100.0);
    }

    private static double bucketToPrice(long bucket) {
        return bucket / 100.0;
    }
}
