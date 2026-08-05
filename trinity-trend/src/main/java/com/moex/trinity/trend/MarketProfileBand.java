package com.moex.trinity.trend;

/**
 * Single high-volume price band from VAP (as on Quik/ATAS orange strips).
 */
public record MarketProfileBand(
        double low,
        double high,
        double volume
) {
    public double mid() {
        return (low + high) / 2.0;
    }

    public double width() {
        return high - low;
    }
}
