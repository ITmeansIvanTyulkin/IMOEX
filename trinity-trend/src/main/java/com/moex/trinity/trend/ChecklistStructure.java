package com.moex.trinity.trend;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Checklist §3–§5: market state, 2–4 levels, majority-with-trend sides.
 */
public final class ChecklistStructure {

    private ChecklistStructure() {
    }

    /**
     * §3: HH/HL → TREND_UP, LH/LL → TREND_DOWN, else RANGE.
     */
    public static MarketState detectMarketState(List<TrendBar> window, double minSwingPoints, double pointSize) {
        if (window == null || window.size() < 20) {
            return MarketState.RANGE;
        }
        double thr = Math.max(5, minSwingPoints) * (pointSize > 0 ? pointSize : 0.01);
        List<Double> swingH = new ArrayList<>();
        List<Double> swingL = new ArrayList<>();
        int look = Math.min(window.size(), 72);
        List<TrendBar> w = window.subList(window.size() - look, window.size());
        for (int i = 2; i < w.size() - 2; i++) {
            TrendBar b = w.get(i);
            if (!b.valid()) {
                continue;
            }
            boolean sh = b.high() >= w.get(i - 1).high() && b.high() >= w.get(i - 2).high()
                    && b.high() >= w.get(i + 1).high() && b.high() >= w.get(i + 2).high();
            boolean sl = b.low() <= w.get(i - 1).low() && b.low() <= w.get(i - 2).low()
                    && b.low() <= w.get(i + 1).low() && b.low() <= w.get(i + 2).low();
            if (sh) {
                swingH.add(b.high());
            }
            if (sl) {
                swingL.add(b.low());
            }
        }
        if (swingH.size() < 2 || swingL.size() < 2) {
            return MarketState.RANGE;
        }
        double h1 = swingH.get(swingH.size() - 2);
        double h2 = swingH.get(swingH.size() - 1);
        double l1 = swingL.get(swingL.size() - 2);
        double l2 = swingL.get(swingL.size() - 1);
        boolean hh = h2 > h1 + thr;
        boolean hl = l2 > l1 + thr;
        boolean lh = h2 < h1 - thr;
        boolean ll = l2 < l1 - thr;
        if (hh && hl) {
            return MarketState.TREND_UP;
        }
        if (lh && ll) {
            return MarketState.TREND_DOWN;
        }
        return MarketState.RANGE;
    }

    /**
     * §4 discover 2–4 levels; §5 assign preferBuy by majority-with-trend.
     */
    public static List<ChecklistLevel> discoverLevels(
            List<TrendBar> window,
            MarketState state,
            double trendHigh,
            double trendLow,
            double previousZero,
            double lastClose,
            TrendInstrumentSpec spec,
            VolumeAtPriceBuilder vap
    ) {
        List<Raw> raw = new ArrayList<>();
        if (Double.isFinite(trendHigh)) {
            raw.add(new Raw(trendHigh, "TREND_HI", "LIVE"));
        }
        if (Double.isFinite(trendLow)) {
            raw.add(new Raw(trendLow, "TREND_LO", "LIVE"));
        }
        if (Double.isFinite(previousZero)) {
            raw.add(new Raw(previousZero, "ZERO", "PREV_TREND"));
        }
        // Accumulation / hidden: POC HVN away from HI/LO/zero
        for (double poc : accumulationPocs(window, vap, spec, 2)) {
            if (farFromAll(poc, raw, spec.zoneMaxPoints() * spec.pointSize())) {
                raw.add(new Raw(poc, "ACCUM", "POC"));
            }
        }
        // Always keep structural HI/LO (§4 max/min); then fill with zero/ACCUM up to 4 — never drop HI/LO for nearer POC
        List<Raw> must = new ArrayList<>();
        List<Raw> optional = new ArrayList<>();
        for (Raw r : raw) {
            if ("TREND_HI".equals(r.role) || "TREND_LO".equals(r.role)) {
                must.add(r);
            } else {
                optional.add(r);
            }
        }
        must = dedupe(must, spec.zoneMinPoints() * spec.pointSize());
        optional = dedupe(optional, spec.zoneMinPoints() * spec.pointSize());
        optional.sort(Comparator.comparingDouble(r -> Math.abs(r.price - lastClose)));
        List<Raw> kept = new ArrayList<>(must);
        for (Raw r : optional) {
            if (kept.size() >= 4) {
                break;
            }
            if (farFromAll(r.price, kept, spec.zoneMinPoints() * spec.pointSize())) {
                kept.add(r);
            }
        }
        while (kept.size() < 2 && Double.isFinite(trendHigh) && Double.isFinite(trendLow)) {
            if (kept.stream().noneMatch(r -> "TREND_HI".equals(r.role))) {
                kept.add(new Raw(trendHigh, "TREND_HI", "LIVE"));
            } else if (kept.stream().noneMatch(r -> "TREND_LO".equals(r.role))) {
                kept.add(new Raw(trendLow, "TREND_LO", "LIVE"));
            } else {
                break;
            }
            kept = dedupe(kept, spec.zoneMinPoints() * spec.pointSize());
        }
        if (kept.size() > 4) {
            // Prefer keeping HI/LO; trim optional from farthest
            kept.sort((a, b) -> {
                int pa = ("TREND_HI".equals(a.role) || "TREND_LO".equals(a.role)) ? 0 : 1;
                int pb = ("TREND_HI".equals(b.role) || "TREND_LO".equals(b.role)) ? 0 : 1;
                if (pa != pb) {
                    return Integer.compare(pa, pb);
                }
                return Double.compare(Math.abs(a.price - lastClose), Math.abs(b.price - lastClose));
            });
            kept = new ArrayList<>(kept.subList(0, 4));
        }

        return assignMajoritySides(kept, state, trendHigh, trendLow, lastClose);
    }

    /**
     * §5: in uptrend majority BUY; downtrend majority SELL; RANGE — top short / bot long for bounce.
     */
    static List<ChecklistLevel> assignMajoritySides(
            List<Raw> raw,
            MarketState state,
            double trendHigh,
            double trendLow,
            double lastClose
    ) {
        List<ChecklistLevel> out = new ArrayList<>(raw.size());
        if (raw.isEmpty()) {
            return out;
        }
        // Sort by price ascending for role clarity
        List<Raw> byPx = new ArrayList<>(raw);
        byPx.sort(Comparator.comparingDouble(r -> r.price));

        if (state == MarketState.RANGE) {
            double mid = (trendHigh + trendLow) / 2.0;
            if (!Double.isFinite(mid)) {
                mid = lastClose;
            }
            for (Raw r : byPx) {
                boolean buy = r.price <= mid; // lower half → buy bounce; upper → sell bounce
                out.add(new ChecklistLevel(r.price, r.role, r.source, buy, null, false));
            }
            return out;
        }

        // Trend: assign by position — below price / lower levels → with-trend bounce
        // Ensure majority with trend (at least ceil(n*0.75) or n-1)
        int n = byPx.size();
        int needWith = Math.max(n - 1, (n + 1) / 2 + (n >= 3 ? 1 : 0));
        if (n >= 4) {
            needWith = 3;
        }
        boolean[] buyFlags = new boolean[n];
        if (state == MarketState.TREND_UP) {
            // Prefer BUY on lower levels; at most one SELL on highest
            for (int i = 0; i < n; i++) {
                buyFlags[i] = true;
            }
            if (n >= 2) {
                buyFlags[n - 1] = false; // highest = optional counter short
            }
            long buys = 0;
            for (boolean b : buyFlags) {
                if (b) {
                    buys++;
                }
            }
            if (buys < needWith) {
                for (int i = 0; i < n && buys < needWith; i++) {
                    if (!buyFlags[i]) {
                        buyFlags[i] = true;
                        buys++;
                    }
                }
            }
        } else {
            for (int i = 0; i < n; i++) {
                buyFlags[i] = false;
            }
            if (n >= 2) {
                buyFlags[0] = true; // lowest = optional counter long
            }
            long sells = 0;
            for (boolean b : buyFlags) {
                if (!b) {
                    sells++;
                }
            }
            if (sells < needWith) {
                for (int i = n - 1; i >= 0 && sells < needWith; i--) {
                    if (buyFlags[i]) {
                        buyFlags[i] = false;
                        sells++;
                    }
                }
            }
        }
        for (int i = 0; i < n; i++) {
            Raw r = byPx.get(i);
            out.add(new ChecklistLevel(r.price, r.role, r.source, buyFlags[i], null, false));
        }
        return out;
    }

    /**
     * Pick active level: RETEST only when price is approaching a broken shelf;
     * else prefer TREND_HI/LO bounce (checklist shelves), not a far ACCUM stuck in break+hold.
     */
    public static ChecklistLevel pickActive(
            List<ChecklistLevel> levels,
            double lastClose,
            MarketState state,
            double retestArmMaxDistancePoints,
            double pointSize
    ) {
        if (levels == null || levels.isEmpty()) {
            return null;
        }
        double maxDist = Math.max(1, retestArmMaxDistancePoints) * (pointSize > 0 ? pointSize : 0.01);
        ChecklistLevel approachRetest = null;
        double bestApproach = Double.POSITIVE_INFINITY;
        for (ChecklistLevel l : levels) {
            if (l == null || !l.hasValidRange() || !l.brokenHeld()) {
                continue;
            }
            double edge = l.preferBuy() ? l.range().high() : l.range().low();
            // long retest: price above zone; short retest: price below
            boolean outside = l.preferBuy()
                    ? lastClose > l.range().high()
                    : lastClose < l.range().low();
            if (!outside) {
                continue;
            }
            double dist = Math.abs(lastClose - edge);
            if (dist <= maxDist && dist < bestApproach) {
                bestApproach = dist;
                approachRetest = l;
            }
        }
        if (approachRetest != null) {
            return approachRetest;
        }

        // Prefer TREND_HI bounce-short / TREND_LO bounce-long when price is AT the shelf
        // (within arm distance), not merely anywhere above an old broken TOP.
        if (state == MarketState.RANGE || state == MarketState.TREND_UP || state == MarketState.TREND_DOWN) {
            ChecklistLevel hi = null;
            ChecklistLevel lo = null;
            for (ChecklistLevel l : levels) {
                if ("TREND_HI".equals(l.role()) && l.hasValidRange()) {
                    hi = l;
                }
                if ("TREND_LO".equals(l.role()) && l.hasValidRange()) {
                    lo = l;
                }
            }
            if (hi != null) {
                double distHi = distToRange(lastClose, hi.range());
                if (distHi <= maxDist && !(approachRetest != null && approachRetest.preferBuy())) {
                    return hi;
                }
            }
            if (lo != null) {
                double distLo = distToRange(lastClose, lo.range());
                if (distLo <= maxDist && !(approachRetest != null && !approachRetest.preferBuy())) {
                    return lo;
                }
            }
        }

        // Bounce pool: majority side; prefer structural HI/LO over ACCUM/ZERO
        List<ChecklistLevel> pool = new ArrayList<>();
        if (state == MarketState.TREND_UP) {
            for (ChecklistLevel l : levels) {
                if (l.preferBuy()) {
                    pool.add(l);
                }
            }
        } else if (state == MarketState.TREND_DOWN) {
            for (ChecklistLevel l : levels) {
                if (!l.preferBuy()) {
                    pool.add(l);
                }
            }
        } else {
            pool.addAll(levels);
        }
        if (pool.isEmpty()) {
            pool = new ArrayList<>(levels);
        }
        ChecklistLevel best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (ChecklistLevel l : pool) {
            if (l == null || !l.hasValidRange()) {
                continue;
            }
            double d = distToRange(lastClose, l.range());
            // Broken+held shelves only via approachRetest above — never bounce-pick a far broken zone
            if (l.brokenHeld() && d > maxDist) {
                continue;
            }
            // Prefer TREND_HI / TREND_LO structurally
            double rolePenalty = ("TREND_HI".equals(l.role()) || "TREND_LO".equals(l.role())) ? 0 : maxDist * 2;
            double score = d + rolePenalty;
            if (score < bestScore) {
                bestScore = score;
                best = l;
            }
        }
        if (best != null) {
            return best;
        }
        // Fallback: nearest valid level that is not a far broken retest-wait
        for (ChecklistLevel l : levels) {
            if (l == null || !l.hasValidRange()) {
                continue;
            }
            double d = distToRange(lastClose, l.range());
            if (l.brokenHeld() && d > maxDist) {
                continue;
            }
            if (d < bestScore) {
                bestScore = d;
                best = l;
            }
        }
        return best;
    }

    /** Absolute distance from price to range (0 if inside). */
    static double distToRange(double px, MergedVolumeRange r) {
        if (r == null) {
            return Double.POSITIVE_INFINITY;
        }
        if (px < r.low()) {
            return r.low() - px;
        }
        if (px > r.high()) {
            return px - r.high();
        }
        return 0;
    }

    /** @deprecated use overload with retest arm distance */
    public static ChecklistLevel pickActive(
            List<ChecklistLevel> levels,
            double lastClose,
            MarketState state
    ) {
        return pickActive(levels, lastClose, state, 10, 0.01);
    }

    static List<Double> accumulationPocs(
            List<TrendBar> window,
            VolumeAtPriceBuilder vap,
            TrendInstrumentSpec spec,
            int max
    ) {
        if (window == null || window.isEmpty() || vap == null) {
            return List.of();
        }
        int n = Math.min(window.size(), 288); // ~1–2 days M5
        List<TrendBar> w = window.subList(window.size() - n, window.size());
        NavigableMap<Long, Double> map = new TreeMap<>();
        double pt = spec.pointSize();
        for (TrendBar b : w) {
            if (!b.valid() || b.volume() <= 0) {
                continue;
            }
            long lo = Math.round(b.low() / pt);
            long hi = Math.round(b.high() / pt);
            if (hi < lo) {
                long t = lo;
                lo = hi;
                hi = t;
            }
            double per = b.volume() / Math.max(1, (hi - lo + 1));
            for (long k = lo; k <= hi; k++) {
                map.merge(k, per, Double::sum);
            }
        }
        if (map.isEmpty()) {
            return List.of();
        }
        List<Double> pocs = new ArrayList<>();
        NavigableMap<Long, Double> rem = new TreeMap<>(map);
        for (int i = 0; i < max && !rem.isEmpty(); i++) {
            long poc = rem.entrySet().stream()
                    .max(java.util.Map.Entry.comparingByValue())
                    .map(java.util.Map.Entry::getKey)
                    .orElseThrow();
            pocs.add(poc * pt + pt / 2.0);
            // clear neighborhood so next POC is distinct
            long half = Math.round(spec.zoneMaxPoints());
            for (long k = poc - half; k <= poc + half; k++) {
                rem.remove(k);
            }
        }
        return pocs;
    }

    private static boolean farFromAll(double px, List<Raw> raw, double minDist) {
        for (Raw r : raw) {
            if (Math.abs(r.price - px) < minDist) {
                return false;
            }
        }
        return true;
    }

    private static List<Raw> dedupe(List<Raw> raw, double minDist) {
        List<Raw> out = new ArrayList<>();
        for (Raw r : raw) {
            boolean near = false;
            for (Raw o : out) {
                if (Math.abs(o.price - r.price) < minDist) {
                    near = true;
                    break;
                }
            }
            if (!near) {
                out.add(r);
            }
        }
        return out;
    }

    public static String majorityNote(List<ChecklistLevel> levels, MarketState state) {
        if (levels == null || levels.isEmpty()) {
            return "§5 no levels";
        }
        long buys = levels.stream().filter(ChecklistLevel::preferBuy).count();
        long sells = levels.size() - buys;
        return String.format(Locale.ROOT, "§5 %s: BUY %d / SELL %d of %d",
                state, buys, sells, levels.size());
    }

    record Raw(double price, String role, String source) {
    }
}
