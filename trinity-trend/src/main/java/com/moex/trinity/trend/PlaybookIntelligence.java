package com.moex.trinity.trend;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Additive intelligence layer on top of checklist §1–18:
 * day phase, H1 deceleration for dump-day BOT bounce, touch quality, local-shelf focus.
 */
public final class PlaybookIntelligence {

    public enum SessionPhase {
        /** Strong day dump — prefer mean-reversion bounce at BOT when confirm OK. */
        MEAN_REVERT_AFTER_DUMP,
        /** Strong day rally — prefer mean-reversion bounce at TOP. */
        MEAN_REVERT_AFTER_RALLY,
        /** Directional continuation — prefer RETEST with trend. */
        CONTINUATION,
        /** Quiet / unclear. */
        BALANCED
    }

    private PlaybookIntelligence() {
    }

    public static SessionPhase resolvePhase(
            double dayMovePoints,
            HtfTrend htf,
            double macroMinDayMove
    ) {
        double thr = macroMinDayMove > 0 ? macroMinDayMove : 80;
        if (dayMovePoints <= -thr) {
            return SessionPhase.MEAN_REVERT_AFTER_DUMP;
        }
        if (dayMovePoints >= thr) {
            return SessionPhase.MEAN_REVERT_AFTER_RALLY;
        }
        if (htf == HtfTrend.UP || htf == HtfTrend.DOWN) {
            return SessionPhase.CONTINUATION;
        }
        return SessionPhase.BALANCED;
    }

    /**
     * Last completed H1 move is smaller than the prior H1 move (absolute), while still net down —
     * dump is losing steam.
     */
    public static boolean htfDeceleratingDown(List<TrendBar> m5, LocalDateTime now, double pointSize) {
        return htfDecelerating(m5, now, pointSize, true);
    }

    public static boolean htfDeceleratingUp(List<TrendBar> m5, LocalDateTime now, double pointSize) {
        return htfDecelerating(m5, now, pointSize, false);
    }

    static boolean htfDecelerating(List<TrendBar> m5, LocalDateTime now, double pointSize, boolean downBias) {
        if (m5 == null || m5.isEmpty() || now == null || pointSize <= 0) {
            return false;
        }
        List<TrendBar> h1 = HtfTrend.completedBuckets(BarAggregator.aggregateMinutes(m5, 60), now, 60);
        if (h1.size() < 3) {
            return false;
        }
        TrendBar a = h1.get(h1.size() - 3);
        TrendBar b = h1.get(h1.size() - 2);
        TrendBar c = h1.get(h1.size() - 1);
        double prev = (b.close() - a.close()) / pointSize;
        double last = (c.close() - b.close()) / pointSize;
        if (downBias) {
            // Was dumping; last hour less negative or flat/green
            return prev < -15 && last > prev * 0.45;
        }
        return prev > 15 && last < prev * 0.45;
    }

    /** Last {@code bars} closes held at/above (buy) or at/below (sell) range mid. */
    public static boolean heldBeyondMid(List<TrendBar> window, MergedVolumeRange range, boolean buy, int bars) {
        if (window == null || range == null || window.isEmpty() || bars < 1) {
            return false;
        }
        int n = Math.min(bars, window.size());
        for (int i = window.size() - n; i < window.size(); i++) {
            TrendBar b = window.get(i);
            if (buy) {
                if (b.close() < range.mid()) {
                    return false;
                }
            } else if (b.close() > range.mid()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Dump-day BOT long exception: confirmed bounce AND (HTF≠DOWN OR H1 decelerating OR held above mid).
     */
    public static boolean allowsDumpDayBotBounce(
            TrendTradeMode mode,
            String role,
            HtfTrend htf,
            List<TrendBar> window,
            MergedVolumeRange range,
            LocalDateTime now,
            double pointSize
    ) {
        if (mode != TrendTradeMode.BOUNCE || !LevelsProfileBrPlaybook.isBotShelfRole(role)) {
            return false;
        }
        if (!LevelsProfileBrPlaybook.bounceConfirmed(window, range, true)) {
            return false;
        }
        if (htf != HtfTrend.DOWN) {
            return true;
        }
        // HTF still DOWN — allow only if dump is decelerating or price held above mid
        return htfDeceleratingDown(window, now, pointSize)
                || heldBeyondMid(window, range, true, 2);
    }

    /**
     * Touch quality 0..3: poke + reject mid + body in trade direction (+ optional DOM later).
     */
    public static int touchQuality(List<TrendBar> window, MergedVolumeRange range, boolean buy) {
        if (window == null || window.isEmpty() || range == null) {
            return 0;
        }
        TrendBar last = window.get(window.size() - 1);
        int score = 0;
        if (buy) {
            boolean poked = last.low() <= range.high() && last.low() >= range.low() - range.width() * 0.5;
            boolean rejected = last.close() >= range.mid();
            boolean bullBody = last.close() > last.open();
            if (poked) {
                score++;
            }
            if (rejected) {
                score++;
            }
            if (bullBody && rejected) {
                score++;
            }
        } else {
            boolean poked = last.high() >= range.low() && last.high() <= range.high() + range.width() * 0.5;
            boolean rejected = last.close() <= range.mid();
            boolean bearBody = last.close() < last.open();
            if (poked) {
                score++;
            }
            if (rejected) {
                score++;
            }
            if (bearBody && rejected) {
                score++;
            }
        }
        return score;
    }

    /** Soft DOM bonus 0 or 1 when book skew supports the bounce side. */
    public static int domSoftBonus(boolean buy, double bidLots5, double askLots5) {
        double tot = bidLots5 + askLots5;
        if (tot <= 0) {
            return 0;
        }
        double skew = 100.0 * (bidLots5 - askLots5) / tot;
        if (buy && skew > 25) {
            return 1;
        }
        if (!buy && skew < -25) {
            return 1;
        }
        return 0;
    }

    /**
     * If price has been living near the opposite shelf for {@code dwellBars}, prefer that shelf
     * over a far broken+held retest target.
     */
    public static ChecklistLevel preferLocalShelf(
            ChecklistLevel active,
            List<ChecklistLevel> levels,
            List<TrendBar> window,
            double lastClose,
            double armPts,
            double pointSize,
            int dwellBars
    ) {
        if (active == null || levels == null || window == null || window.isEmpty() || pointSize <= 0) {
            return active;
        }
        ChecklistLevel hi = null;
        ChecklistLevel lo = null;
        for (ChecklistLevel l : levels) {
            if (l == null || !l.hasValidRange()) {
                continue;
            }
            if ("TREND_HI".equals(l.role())) {
                hi = l;
            }
            if ("TREND_LO".equals(l.role())) {
                lo = l;
            }
        }
        double arm = Math.max(1, armPts) * pointSize;
        int n = Math.min(Math.max(6, dwellBars), window.size());
        List<TrendBar> recent = window.subList(window.size() - n, window.size());

        boolean dwellBot = lo != null && dwellNear(recent, lo.range(), arm);
        boolean dwellTop = hi != null && dwellNear(recent, hi.range(), arm);
        String role = active.role() == null ? "" : active.role();

        // Stuck on HI retest while living at BOT → switch to BOT
        if (dwellBot && !dwellTop && lo != null
                && ("TREND_HI".equals(role) || active.brokenHeld())) {
            double distActive = Math.abs(lastClose - active.range().mid());
            double distLo = Math.abs(lastClose - lo.range().mid());
            if (distLo + arm * 0.5 < distActive) {
                return lo;
            }
        }
        // Stuck on LO retest while living at TOP → switch to TOP
        if (dwellTop && !dwellBot && hi != null
                && ("TREND_LO".equals(role) || active.brokenHeld())) {
            double distActive = Math.abs(lastClose - active.range().mid());
            double distHi = Math.abs(lastClose - hi.range().mid());
            if (distHi + arm * 0.5 < distActive) {
                return hi;
            }
        }
        return active;
    }

    static boolean dwellNear(List<TrendBar> recent, MergedVolumeRange range, double armPrice) {
        if (recent == null || range == null || recent.isEmpty()) {
            return false;
        }
        int hits = 0;
        for (TrendBar b : recent) {
            double mid = range.mid();
            boolean near = Math.abs(b.close() - mid) <= armPrice * 2
                    || (b.low() <= range.high() + armPrice && b.high() >= range.low() - armPrice);
            if (near) {
                hits++;
            }
        }
        return hits >= Math.max(3, (recent.size() * 2) / 3);
    }

    public static String phaseRu(SessionPhase p) {
        return switch (p) {
            case MEAN_REVERT_AFTER_DUMP -> "фаза: отскок после дампа";
            case MEAN_REVERT_AFTER_RALLY -> "фаза: отскок после ралли";
            case CONTINUATION -> "фаза: продолжение тренда";
            case BALANCED -> "фаза: баланс";
        };
    }
}
