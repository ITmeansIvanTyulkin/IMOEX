package com.moex.trinity.trend;

/**
 * Checklist §12 / §17: after TP1 → stop to breakeven; stop qty = remainder; then trail.
 * Advice only — broker amend is separate (bridge / replay applies the new stop).
 */
public final class TrendPositionManager {

    private TrendPositionManager() {
    }

    public record ManageAdvice(
            double stop,
            boolean movedToBe,
            boolean trailing,
            boolean tp1Touched,
            /** §12.2 / §17.2: contracts that must remain on the stop after TP1 partial. */
            int stopQty,
            String note
    ) {
    }

    /**
     * @param buy           long if true
     * @param entryAvg      average fill
     * @param currentStop   active stop
     * @param tp1           first take-profit
     * @param lastPrice     last trade / close
     * @param pointSize     instrument point (BR 0.01)
     * @param trailPoints   trail distance in points after BE (typically stopPoints 15–20)
     * @param positionQty   total contracts before/after fills
     * @param tp1Fraction   fraction closed at TP1 (1/3 or 1/2)
     * @param tp1AlreadyDone whether TP1 already taken
     */
    public static ManageAdvice update(
            boolean buy,
            double entryAvg,
            double currentStop,
            double tp1,
            double lastPrice,
            double pointSize,
            double trailPoints,
            int positionQty,
            double tp1Fraction,
            boolean tp1AlreadyDone
    ) {
        if (!(entryAvg > 0) || !(pointSize > 0) || !Double.isFinite(lastPrice)) {
            return new ManageAdvice(currentStop, false, false, false, Math.max(0, positionQty), "invalid inputs");
        }
        int qty = Math.max(0, positionQty);
        double frac = tp1Fraction > 0 && tp1Fraction < 1 ? tp1Fraction : (1.0 / 3.0);
        int tp1Qty = Math.max(1, (int) Math.round(qty * frac));
        if (tp1Qty >= qty) {
            tp1Qty = Math.max(1, qty - 1);
        }
        int remainder = Math.max(1, qty - tp1Qty);

        double trailPx = Math.max(1, trailPoints) * pointSize;
        boolean tp1Touched = tp1AlreadyDone || (buy ? lastPrice >= tp1 : lastPrice <= tp1);
        double stop = currentStop;
        boolean be = false;
        boolean trail = false;
        int stopQty = tp1Touched ? remainder : qty;

        if (tp1Touched) {
            // Move to breakeven (never worse than entry) — §12.2 / §17.2
            if (buy) {
                if (stop < entryAvg || !Double.isFinite(stop)) {
                    stop = entryAvg;
                    be = true;
                }
                double trailStop = lastPrice - trailPx;
                if (trailStop > stop) {
                    stop = trailStop;
                    trail = true;
                    be = false;
                }
            } else {
                if (stop > entryAvg || !Double.isFinite(stop)) {
                    stop = entryAvg;
                    be = true;
                }
                double trailStop = lastPrice + trailPx;
                if (trailStop < stop) {
                    stop = trailStop;
                    trail = true;
                    be = false;
                }
            }
        }

        String note;
        if (trail) {
            note = String.format("§12 trail SL=%.2f qty=%d (BE locked, trail %.0f pts)", stop, stopQty, trailPoints);
        } else if (be) {
            note = String.format("§12 TP1 hit → BE SL=%.2f stopQty=%d (was %d)", stop, stopQty, qty);
        } else if (tp1Touched) {
            note = String.format("§12 at/above TP1, SL=%.2f stopQty=%d", stop, stopQty);
        } else {
            note = String.format("§12 hold initial SL=%.2f qty=%d until TP1", stop, stopQty);
        }
        return new ManageAdvice(stop, be, trail, tp1Touched, stopQty, note);
    }

    /** Backward-compatible overload (assumes full qty still on stop until caller tracks TP1). */
    public static ManageAdvice update(
            boolean buy,
            double entryAvg,
            double currentStop,
            double tp1,
            double lastPrice,
            double pointSize,
            double trailPoints
    ) {
        return update(buy, entryAvg, currentStop, tp1, lastPrice, pointSize, trailPoints, 1, 1.0 / 3.0, false);
    }
}
