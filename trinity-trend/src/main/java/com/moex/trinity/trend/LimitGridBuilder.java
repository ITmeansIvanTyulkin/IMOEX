package com.moex.trinity.trend;

/**
 * Builds 3-limit grid inside a merged volume range.
 */
public final class LimitGridBuilder {

    private LimitGridBuilder() {
    }

    /**
     * @param buy           true = buy limits (near = high of range for bounce from below approach…);
     *                      for bounce long: approach from above → near=high, far=low.
     *                      for retest long after break above: approach from above → near=high, far=low.
     * @param totalContracts sized position
     */
    public static LimitGridPlan build(
            MergedVolumeRange range,
            boolean buy,
            int totalContracts,
            LimitGridStyle style
    ) {
        if (range == null || !range.validForEntry() || totalContracts <= 0) {
            return new LimitGridPlan(style == null ? LimitGridStyle.MODERATE : style,
                    Double.NaN, 0, Double.NaN, 0, Double.NaN, 0, buy);
        }
        LimitGridStyle st = style == null ? LimitGridStyle.MODERATE : style;
        double high = range.high();
        double low = range.low();
        double mid = range.mid();

        // Near = first touch when price returns into range from outside in trade direction setup.
        // Long bounce/retest: price comes from above → near=high, far=low.
        // Short bounce/retest: price comes from below → near=low, far=high.
        double near;
        double far;
        if (buy) {
            near = high;
            far = low;
        } else {
            near = low;
            far = high;
        }

        int[] qty = splitQty(totalContracts, st);
        return new LimitGridPlan(st, near, qty[0], mid, qty[1], far, qty[2], buy);
    }

    /** Returns [near, mid, far]. */
    static int[] splitQty(int total, LimitGridStyle style) {
        if (total <= 0) {
            return new int[]{0, 0, 0};
        }
        if (total == 1) {
            return new int[]{1, 0, 0};
        }
        if (total == 2) {
            return new int[]{1, 1, 0};
        }
        if (style == LimitGridStyle.AGGRESSIVE) {
            return new int[]{total - 2, 1, 1};
        }
        int base = total / 3;
        int rem = total % 3;
        int near = base + (rem > 0 ? 1 : 0);
        int mid = base + (rem > 1 ? 1 : 0);
        int far = base;
        return new int[]{near, mid, far};
    }
}
