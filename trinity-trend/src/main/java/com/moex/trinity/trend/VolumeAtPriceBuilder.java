package com.moex.trinity.trend;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Volume-at-price proxy: distribute each bar's volume across H–L buckets, then merge HVN bands
 * into one entry range (checklist + screens: orange strips → one zone).
 */
public final class VolumeAtPriceBuilder {

    private final double pointSize;
    private final double zoneMinPoints;
    private final double zoneMaxPoints;
    private final int maxBandsToMerge;
    private final boolean allowZonePad;
    private final int minHvnBands;

    public VolumeAtPriceBuilder(TrendInstrumentSpec spec) {
        this(spec, false, 2);
    }

    public VolumeAtPriceBuilder(TrendInstrumentSpec spec, boolean allowZonePad, int minHvnBands) {
        this(
                spec.pointSize(),
                spec.zoneMinPoints(),
                spec.zoneMaxPoints(),
                8,
                allowZonePad,
                minHvnBands
        );
    }

    public VolumeAtPriceBuilder(
            double pointSize,
            double zoneMinPoints,
            double zoneMaxPoints,
            int maxBandsToMerge,
            boolean allowZonePad,
            int minHvnBands
    ) {
        this.pointSize = pointSize > 0 ? pointSize : 0.01;
        this.zoneMinPoints = zoneMinPoints;
        this.zoneMaxPoints = zoneMaxPoints;
        this.maxBandsToMerge = Math.max(2, maxBandsToMerge);
        this.allowZonePad = allowZonePad;
        this.minHvnBands = Math.max(1, minHvnBands);
    }

    /**
     * Build VAP from candles that touched {@code level}.
     * Requires {@code minTouchCount} distinct touch clusters (bounces).
     */
    public MergedVolumeRange buildAroundLevel(
            List<TrendBar> bars,
            double level,
            int touchLookback,
            int candlesPerTouch,
            int minTouchCount
    ) {
        if (bars == null || bars.isEmpty() || Double.isNaN(level)) {
            return MergedVolumeRange.invalid("empty bars or level");
        }
        double tol = pointSize * 2;
        List<Integer> touchIdx = new ArrayList<>();
        for (int i = 0; i < bars.size(); i++) {
            TrendBar b = bars.get(i);
            if (b.valid() && b.touches(level, tol)) {
                touchIdx.add(i);
            }
        }
        if (touchIdx.isEmpty()) {
            return MergedVolumeRange.invalid("no candle touches level");
        }
        int clusters = countTouchClusters(touchIdx, Math.max(3, candlesPerTouch + 1));
        if (clusters < Math.max(1, minTouchCount)) {
            return MergedVolumeRange.invalid(
                    String.format("need ≥%d bounce clusters, got %d", minTouchCount, clusters));
        }
        int fromTouch = Math.max(0, touchIdx.size() - Math.max(1, touchLookback));
        List<TrendBar> window = new ArrayList<>();
        for (int t = fromTouch; t < touchIdx.size(); t++) {
            int idx = touchIdx.get(t);
            int start = Math.max(0, idx - Math.max(0, candlesPerTouch - 1));
            for (int j = start; j <= idx; j++) {
                window.add(bars.get(j));
            }
        }
        return mergeFromBars(window);
    }

    /** Distinct bounce clusters: touches separated by ≥ gap bars. */
    static int countTouchClusters(List<Integer> touchIdx, int gapBars) {
        if (touchIdx == null || touchIdx.isEmpty()) {
            return 0;
        }
        int clusters = 1;
        int prev = touchIdx.get(0);
        for (int i = 1; i < touchIdx.size(); i++) {
            int idx = touchIdx.get(i);
            if (idx - prev >= gapBars) {
                clusters++;
            }
            prev = idx;
        }
        return clusters;
    }

    /**
     * Broker / ISS tape VAP around a level (preferred when marketdata streaming).
     * Each row: {@code [price, lots]}.
     * Builds a shelf from all prints within ±zoneMax/2 of the level (ATAS-like),
     * then pads to zoneMin when needed so limit grids stay executable.
     */
    public MergedVolumeRange buildAroundLevelFromPrints(
            List<double[]> prints,
            double level,
            int minTouchCount
    ) {
        if (prints == null || prints.isEmpty() || Double.isNaN(level)) {
            return MergedVolumeRange.invalid("empty tape or level");
        }
        double tol = pointSize * 2;
        double halfShelf = (zoneMaxPoints * pointSize) / 2.0;
        List<Integer> touchIdx = new ArrayList<>();
        NavigableMap<Long, Double> vap = new TreeMap<>();
        double lotsNear = 0;
        for (int i = 0; i < prints.size(); i++) {
            double[] p = prints.get(i);
            if (p == null || p.length < 2 || p[1] <= 0) {
                continue;
            }
            double dist = Math.abs(p[0] - level);
            if (dist <= halfShelf) {
                vap.merge(priceBucket(p[0]), p[1], Double::sum);
                lotsNear += p[1];
            }
            if (dist <= tol) {
                touchIdx.add(i);
            }
        }
        if (vap.isEmpty() || lotsNear < 30) {
            return MergedVolumeRange.invalid("tape shelf too thin near level");
        }
        // Visits: gap of 40 prints ≈ leave-and-return on a busy FORTS tape
        int clusters = touchIdx.isEmpty() ? 0 : countTouchClusters(touchIdx, 40);
        if (clusters < Math.max(1, minTouchCount) && lotsNear < 500) {
            return MergedVolumeRange.invalid(
                    String.format("tape need ≥%d visits or heavy shelf, got clusters=%d lots=%.0f",
                            minTouchCount, clusters, lotsNear));
        }
        MergedVolumeRange raw = mergeFromVolumeMap(vap);
        if (raw.validForEntry()) {
            return raw;
        }
        // Tape shelves are often narrower than bar OHLC proxies — pad to zoneMin for grids
        if (raw.invalidReason() != null && raw.invalidReason().contains("no pad")
                && raw.low() < raw.high()) {
            double mid = (raw.low() + raw.high()) / 2.0;
            double half = (zoneMinPoints * pointSize) / 2.0;
            return new MergedVolumeRange(
                    mid - half,
                    mid + half,
                    raw.totalVolume(),
                    raw.sourceBands(),
                    true,
                    null
            );
        }
        // Fallback: POC-centered zoneMin band from the shelf map
        long poc = vap.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(priceBucket(level));
        double pocPx = bucketToPrice(poc) + pointSize / 2.0;
        double half = (zoneMinPoints * pointSize) / 2.0;
        return new MergedVolumeRange(
                pocPx - half,
                pocPx + half,
                lotsNear,
                List.of(new MarketProfileBand(pocPx - half, pocPx + half, lotsNear)),
                true,
                null
        );
    }

    public MergedVolumeRange mergeFromVolumeMap(NavigableMap<Long, Double> vap) {
        if (vap == null || vap.isEmpty()) {
            return MergedVolumeRange.invalid("no volume in map");
        }
        List<MarketProfileBand> hvn = extractHvnBands(vap);
        if (hvn.size() < minHvnBands) {
            return MergedVolumeRange.invalid(
                    String.format("need ≥%d HVN bands, got %d", minHvnBands, hvn.size()));
        }
        return rangeAroundPoc(vap, hvn);
    }

    public MergedVolumeRange mergeFromBars(List<TrendBar> window) {
        NavigableMap<Long, Double> vap = accumulate(window);
        if (vap.isEmpty()) {
            return MergedVolumeRange.invalid("no volume in window");
        }
        return mergeFromVolumeMap(vap);
    }

    MergedVolumeRange rangeAroundPoc(NavigableMap<Long, Double> vap, List<MarketProfileBand> hvn) {
        long pocBucket = vap.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElseThrow();
        double avg = vap.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double floor = avg * 0.35;

        Long lo = pocBucket;
        Long hi = pocBucket;
        while (true) {
            Long left = vap.lowerKey(lo);
            Long right = vap.higherKey(hi);
            boolean took = false;
            if (left != null && vap.get(left) >= floor) {
                lo = left;
                took = true;
            }
            if (right != null && vap.get(right) >= floor) {
                hi = right;
                took = true;
            }
            if (!took) {
                break;
            }
            if ((hi - lo + 1) >= zoneMaxPoints) {
                break;
            }
        }

        double low = bucketToPrice(lo);
        double high = bucketToPrice(hi) + pointSize;
        double widthPts = (high - low) / pointSize;
        double vol = vap.subMap(lo, true, hi, true).values().stream().mapToDouble(Double::doubleValue).sum();
        List<MarketProfileBand> bands = hvn == null || hvn.isEmpty()
                ? List.of(new MarketProfileBand(low, high, vol))
                : List.copyOf(hvn);

        if (widthPts > zoneMaxPoints) {
            double half = (zoneMaxPoints * pointSize) / 2.0;
            double pocPx = bucketToPrice(pocBucket) + pointSize / 2.0;
            low = pocPx - half;
            high = pocPx + half;
            return new MergedVolumeRange(low, high, vol, bands, true, null);
        }
        if (widthPts < zoneMinPoints) {
            if (!allowZonePad) {
                return new MergedVolumeRange(low, high, vol, bands, false,
                        String.format("range too narrow (no pad): %.1f pts < %.0f", widthPts, zoneMinPoints));
            }
            double need = (zoneMinPoints * pointSize - (high - low)) / 2.0;
            low -= need;
            high += need;
            widthPts = (high - low) / pointSize;
            if (widthPts > zoneMaxPoints + 0.01) {
                return new MergedVolumeRange(low, high, vol, bands, false,
                        String.format("range too narrow before pad / unstable: %.1f pts", widthPts));
            }
            return new MergedVolumeRange(low, high, vol, bands, true, null);
        }
        return new MergedVolumeRange(low, high, vol, bands, true, null);
    }

    NavigableMap<Long, Double> accumulate(List<TrendBar> window) {
        NavigableMap<Long, Double> vap = new TreeMap<>();
        if (window == null) {
            return vap;
        }
        for (TrendBar b : window) {
            if (b == null || !b.valid() || b.volume() <= 0) {
                continue;
            }
            long lo = priceBucket(b.low());
            long hi = priceBucket(b.high());
            if (hi < lo) {
                long tmp = lo;
                lo = hi;
                hi = tmp;
            }
            long steps = hi - lo + 1;
            double per = b.volume() / (double) steps;
            for (long bucket = lo; bucket <= hi; bucket++) {
                vap.merge(bucket, per, Double::sum);
            }
        }
        return vap;
    }

    List<MarketProfileBand> extractHvnBands(NavigableMap<Long, Double> vap) {
        if (vap.isEmpty()) {
            return List.of();
        }
        double avg = vap.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double threshold = avg * 1.15;
        List<Map.Entry<Long, Double>> peaks = new ArrayList<>();
        Long[] keys = vap.keySet().toArray(Long[]::new);
        for (int i = 0; i < keys.length; i++) {
            double v = vap.get(keys[i]);
            if (v < threshold) {
                continue;
            }
            double left = i > 0 ? vap.get(keys[i - 1]) : 0;
            double right = i < keys.length - 1 ? vap.get(keys[i + 1]) : 0;
            if (v >= left && v >= right) {
                peaks.add(Map.entry(keys[i], v));
            }
        }
        if (peaks.isEmpty()) {
            peaks = vap.entrySet().stream()
                    .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                    .limit(maxBandsToMerge)
                    .toList();
        } else {
            peaks = peaks.stream()
                    .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                    .limit(maxBandsToMerge)
                    .toList();
        }
        List<MarketProfileBand> bands = new ArrayList<>();
        for (Map.Entry<Long, Double> e : peaks) {
            double px = bucketToPrice(e.getKey());
            bands.add(new MarketProfileBand(px, px + pointSize, e.getValue()));
        }
        bands.sort(Comparator.comparingDouble(MarketProfileBand::low));
        return bands;
    }

    MergedVolumeRange mergeBands(List<MarketProfileBand> bands) {
        if (bands == null || bands.isEmpty()) {
            return MergedVolumeRange.invalid("no bands");
        }
        List<MarketProfileBand> sorted = new ArrayList<>(bands);
        sorted.sort(Comparator.comparingDouble(MarketProfileBand::low));

        double gap = pointSize * 2.5;
        List<List<MarketProfileBand>> clusters = new ArrayList<>();
        List<MarketProfileBand> cur = new ArrayList<>();
        cur.add(sorted.get(0));
        for (int i = 1; i < sorted.size(); i++) {
            MarketProfileBand prev = cur.get(cur.size() - 1);
            MarketProfileBand b = sorted.get(i);
            if (b.low() - prev.high() <= gap) {
                cur.add(b);
            } else {
                clusters.add(cur);
                cur = new ArrayList<>();
                cur.add(b);
            }
        }
        clusters.add(cur);

        List<MarketProfileBand> best = clusters.stream()
                .max(Comparator.comparingDouble(c -> c.stream().mapToDouble(MarketProfileBand::volume).sum()))
                .orElse(sorted);

        double low = best.stream().mapToDouble(MarketProfileBand::low).min().orElse(Double.NaN);
        double high = best.stream().mapToDouble(MarketProfileBand::high).max().orElse(Double.NaN);
        double vol = best.stream().mapToDouble(MarketProfileBand::volume).sum();
        double widthPts = (high - low) / pointSize;

        if (widthPts < zoneMinPoints) {
            return new MergedVolumeRange(low, high, vol, List.copyOf(best), false,
                    String.format("range too narrow: %.1f pts < %.0f", widthPts, zoneMinPoints));
        }
        if (widthPts > zoneMaxPoints) {
            return new MergedVolumeRange(low, high, vol, List.copyOf(best), false,
                    String.format("range too wide: %.1f pts > %.0f", widthPts, zoneMaxPoints));
        }
        return new MergedVolumeRange(low, high, vol, List.copyOf(best), true, null);
    }

    private long priceBucket(double price) {
        return Math.round(price / pointSize);
    }

    private double bucketToPrice(long bucket) {
        return bucket * pointSize;
    }
}
