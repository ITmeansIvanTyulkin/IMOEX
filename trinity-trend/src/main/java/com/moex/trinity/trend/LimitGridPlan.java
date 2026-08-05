package com.moex.trinity.trend;

/**
 * Three limit orders inside the volume range (near / mid / far from approach).
 */
public record LimitGridPlan(
        LimitGridStyle style,
        double nearPrice,
        int nearQty,
        double midPrice,
        int midQty,
        double farPrice,
        int farQty,
        boolean buy
) {
    public int totalQty() {
        return nearQty + midQty + farQty;
    }

    /** Average fill price if all three fill. */
    public double averagePrice() {
        int t = totalQty();
        if (t <= 0) {
            return Double.NaN;
        }
        return (nearPrice * nearQty + midPrice * midQty + farPrice * farQty) / t;
    }
}
