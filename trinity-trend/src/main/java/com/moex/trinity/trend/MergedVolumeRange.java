package com.moex.trinity.trend;

import java.util.List;

/**
 * Merged HVN bands into one entry range (checklist step 7).
 */
public record MergedVolumeRange(
        double low,
        double high,
        double totalVolume,
        List<MarketProfileBand> sourceBands,
        boolean validForEntry,
        String invalidReason
) {
    public double width() {
        return high - low;
    }

    public double mid() {
        return (low + high) / 2.0;
    }

    /** Width in instrument points (BR: 0.01 = 1 point). */
    public double widthPoints(double pointSize) {
        if (pointSize <= 0) {
            return Double.NaN;
        }
        return width() / pointSize;
    }

    public static MergedVolumeRange invalid(String reason) {
        return new MergedVolumeRange(Double.NaN, Double.NaN, 0, List.of(), false, reason);
    }
}
