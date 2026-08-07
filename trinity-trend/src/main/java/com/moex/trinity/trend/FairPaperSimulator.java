package com.moex.trinity.trend;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Fair-paper fill / manage shared by day-replay and live sandbox loop.
 * Limit grid fill when price trades through levels; SL/TP1/TP2 with BE trail after TP1.
 */
public final class FairPaperSimulator {

    private FairPaperSimulator() {
    }

    public static OpenPaper tryOpen(TrendRobotPlan plan, TrendBar bar) {
        if (plan == null || plan.grid() == null || bar == null) {
            return null;
        }
        LimitGridPlan g = plan.grid();
        boolean buy = plan.buy();
        List<double[]> fills = new ArrayList<>();
        double[][] limits = {
                {g.nearPrice(), g.nearQty()},
                {g.midPrice(), g.midQty()},
                {g.farPrice(), g.farQty()}
        };
        for (double[] lim : limits) {
            if (lim[1] <= 0) {
                continue;
            }
            if (bar.low() <= lim[0] && lim[0] <= bar.high()) {
                fills.add(new double[]{lim[0], lim[1]});
            }
        }
        if (fills.isEmpty()) {
            return null;
        }
        double qty = fills.stream().mapToDouble(f -> f[1]).sum();
        double avg = fills.stream().mapToDouble(f -> f[0] * f[1]).sum() / qty;
        double gridAvg = g.averagePrice();
        double risk = Math.abs(plan.stopLossPrice() - gridAvg);
        double r1 = Math.abs(plan.tp1Price() - gridAvg);
        double r2 = Math.abs(plan.tp2Price() - gridAvg);
        double sl = buy ? avg - risk : avg + risk;
        double tp1 = buy ? avg + r1 : avg - r1;
        double tp2 = buy ? avg + r2 : avg - r2;
        return new OpenPaper(
                bar.time(),
                buy,
                plan.mode() == null ? "?" : plan.mode().name(),
                plan.instrument(),
                avg,
                (int) qty,
                sl,
                tp1,
                tp2,
                plan.tp1Fraction(),
                false,
                true,
                0.0
        );
    }

    /**
     * @return exit when position closed this bar; null to keep open
     */
    public static ExitResult manage(OpenPaper open, TrendBar bar, double rubPerPoint, double point) {
        if (open == null || bar == null) {
            return null;
        }
        if (open.fillBar) {
            open.fillBar = false;
            return null;
        }
        boolean buy = open.buy;

        if (open.tp1Done) {
            double trailPts = 20;
            var advice = TrendPositionManager.update(
                    buy, open.avg, open.sl, open.tp1, bar.close(), point, trailPts,
                    open.qty, open.tp1Fraction, true);
            if (Double.isFinite(advice.stop())) {
                open.sl = advice.stop();
            }
            if (advice.stopQty() > 0 && advice.stopQty() < open.qty) {
                open.qty = advice.stopQty();
            }
        }

        if (buy && bar.open() <= open.sl) {
            return new ExitResult(open.realized + cashPnl(open, bar.open(), open.qty, point, rubPerPoint),
                    open.tp1Done ? "BE_STOP" : "SL", bar.open());
        }
        if (!buy && bar.open() >= open.sl) {
            return new ExitResult(open.realized + cashPnl(open, bar.open(), open.qty, point, rubPerPoint),
                    open.tp1Done ? "BE_STOP" : "SL", bar.open());
        }
        boolean hitSl = buy ? bar.low() <= open.sl : bar.high() >= open.sl;
        boolean hitTp1 = !open.tp1Done && (buy ? bar.high() >= open.tp1 : bar.low() <= open.tp1);
        boolean hitTp2 = open.tp1Done && (buy ? bar.high() >= open.tp2 : bar.low() <= open.tp2);

        if (hitSl && (hitTp1 || hitTp2)) {
            return new ExitResult(open.realized + cashPnl(open, open.sl, open.qty, point, rubPerPoint),
                    open.tp1Done ? "BE_STOP" : "SL", open.sl);
        }
        if (hitSl) {
            double pnl = open.realized + cashPnl(open, open.sl, open.qty, point, rubPerPoint);
            return new ExitResult(pnl, open.tp1Done ? "BE_STOP" : "SL", open.sl);
        }
        if (hitTp1) {
            int q1 = Math.max(1, (int) Math.round(open.qty * open.tp1Fraction));
            q1 = Math.min(q1, open.qty);
            open.realized += cashPnl(open, open.tp1, q1, point, rubPerPoint);
            open.qty -= q1;
            open.sl = open.avg;
            open.tp1Done = true;
            var advice = TrendPositionManager.update(
                    buy, open.avg, open.sl, open.tp1, bar.close(), point, 20,
                    open.qty + q1, open.tp1Fraction, true);
            if (advice.stopQty() > 0) {
                open.qty = advice.stopQty();
            }
            if (Double.isFinite(advice.stop()) && advice.trailing()) {
                open.sl = advice.stop();
            }
            if (open.qty <= 0) {
                return new ExitResult(open.realized, "TP1_FULL", open.tp1);
            }
            if (buy ? bar.high() >= open.tp2 : bar.low() <= open.tp2) {
                open.realized += cashPnl(open, open.tp2, open.qty, point, rubPerPoint);
                return new ExitResult(open.realized, "TP2", open.tp2);
            }
            return null;
        }
        if (hitTp2) {
            open.realized += cashPnl(open, open.tp2, open.qty, point, rubPerPoint);
            return new ExitResult(open.realized, "TP2", open.tp2);
        }
        return null;
    }

    public static double cashPnl(OpenPaper open, double exit, int qty, double point, double rubPerPoint) {
        if (open == null || qty <= 0 || point <= 0) {
            return 0;
        }
        double pts = (exit - open.avg) / point;
        double signed = open.buy ? pts : -pts;
        return signed * qty * rubPerPoint;
    }

    public static double markToMarket(OpenPaper open, double lastClose, double point, double rubPerPoint) {
        if (open == null) {
            return 0;
        }
        return open.realized + cashPnl(open, lastClose, open.qty, point, rubPerPoint);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class OpenPaper {
        public LocalDateTime entryTime;
        public boolean buy;
        public String mode;
        public String instrument;
        public double avg;
        public int qty;
        public double sl;
        public double tp1;
        public double tp2;
        public double tp1Fraction;
        public boolean tp1Done;
        public boolean fillBar;
        public double realized;

        public OpenPaper() {
        }

        public OpenPaper(
                LocalDateTime entryTime,
                boolean buy,
                String mode,
                String instrument,
                double avg,
                int qty,
                double sl,
                double tp1,
                double tp2,
                double tp1Fraction,
                boolean tp1Done,
                boolean fillBar,
                double realized
        ) {
            this.entryTime = entryTime;
            this.buy = buy;
            this.mode = mode;
            this.instrument = instrument;
            this.avg = avg;
            this.qty = qty;
            this.sl = sl;
            this.tp1 = tp1;
            this.tp2 = tp2;
            this.tp1Fraction = tp1Fraction;
            this.tp1Done = tp1Done;
            this.fillBar = fillBar;
            this.realized = realized;
        }
    }

    public record ExitResult(double pnl, String reason, double exitPrice) {
        public ExitResult(double pnl, String reason) {
            this(pnl, reason, Double.NaN);
        }
    }
}
