package com.moex.trinity.trend;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Higher-timeframe / session trend for asymmetric long/short priority.
 * Soft filter only: FLAT does not hard-block; against-trend uses bounce-only / size cut.
 * Prefer aggregated H1/M15 from M5 (hourly refresh via completed bars), else M5 proxy.
 */
public enum HtfTrend {
    UP,
    DOWN,
    FLAT;

    /** Resolution with source label for desk ({@code H1}, {@code M15}, {@code M5_PROXY}). */
    public record Resolved(HtfTrend trend, String source) {
        public Resolved {
            trend = trend == null ? FLAT : trend;
            source = source == null || source.isBlank() ? "M5_PROXY" : source;
        }
    }

    public boolean isFlat() {
        return this == FLAT;
    }

    /** Trade is with the HTF trend. */
    public boolean withTrend(boolean buy) {
        if (this == FLAT) {
            return false;
        }
        return buy ? this == UP : this == DOWN;
    }

    /** Trade is against HTF (counter-trend). */
    public boolean againstTrend(boolean buy) {
        return !isFlat() && !withTrend(buy);
    }

    /**
     * Prefer aggregated senior TF (H1/M15); fall back to M5 session/slope proxy.
     */
    public static HtfTrend resolve(List<TrendBar> bars, LocalDateTime now, TrendPlaybookSettings settings) {
        return resolveDetailed(bars, now, settings).trend();
    }

    public static Resolved resolveDetailed(
            List<TrendBar> bars,
            LocalDateTime now,
            TrendPlaybookSettings settings
    ) {
        if (bars == null || bars.isEmpty() || now == null || settings == null) {
            return new Resolved(FLAT, "NONE");
        }
        double point = settings.instrument().pointSize();
        if (point <= 0) {
            return new Resolved(FLAT, "NONE");
        }
        double minMove = settings.htfMinMovePoints() > 0 ? settings.htfMinMovePoints() : 50;
        int aggMin = settings.htfAggregatedMinutes();
        if (aggMin > 0) {
            HtfTrend agg = fromAggregated(
                    bars, now, aggMin, settings.htfAggregatedBars(), minMove, point);
            if (agg != FLAT) {
                String src = aggMin >= 60 ? "H1" : (aggMin == 15 ? "M15" : "M" + aggMin);
                return new Resolved(agg, src);
            }
        }
        return new Resolved(resolveM5Proxy(bars, now, settings, minMove, point), "M5_PROXY");
    }

    static HtfTrend resolveM5Proxy(
            List<TrendBar> bars,
            LocalDateTime now,
            TrendPlaybookSettings settings,
            double minMove,
            double point
    ) {
        HtfTrend fromOpen = fromSessionOpen(bars, now, settings.tradeSessionOpen(), minMove, point);
        HtfTrend fromSlope = fromSlope(bars, settings.htfSlopeBars(), minMove, point);
        if (settings.htfRequireAgreement()) {
            if (fromOpen == FLAT || fromSlope == FLAT || fromOpen != fromSlope) {
                return FLAT;
            }
            return fromOpen;
        }
        if (fromOpen != FLAT) {
            return fromOpen;
        }
        return fromSlope;
    }

    /**
     * Slope of last {@code lookback} <em>completed</em> aggregated bars (excludes current open bucket).
     * Updates naturally each hour (H1) / 15m (M15) when a bar closes.
     */
    static HtfTrend fromAggregated(
            List<TrendBar> m5,
            LocalDateTime now,
            int periodMinutes,
            int lookbackBars,
            double minMovePoints,
            double pointSize
    ) {
        if (m5 == null || m5.isEmpty() || now == null || periodMinutes <= 0 || pointSize <= 0) {
            return FLAT;
        }
        List<TrendBar> agg = BarAggregator.aggregateMinutes(m5, periodMinutes);
        List<TrendBar> completed = completedBuckets(agg, now, periodMinutes);
        int need = Math.max(2, lookbackBars);
        if (completed.size() < need) {
            return FLAT;
        }
        List<TrendBar> w = completed.subList(completed.size() - need, completed.size());
        double movePts = (w.get(w.size() - 1).close() - w.get(0).close()) / pointSize;
        if (movePts >= minMovePoints) {
            return UP;
        }
        if (movePts <= -minMovePoints) {
            return DOWN;
        }
        return FLAT;
    }

    /** Drop the in-progress bucket whose start is still open at {@code now}. */
    static List<TrendBar> completedBuckets(List<TrendBar> agg, LocalDateTime now, int periodMinutes) {
        if (agg == null || agg.isEmpty()) {
            return List.of();
        }
        List<TrendBar> out = new ArrayList<>(agg.size());
        for (TrendBar b : agg) {
            if (b == null || b.time() == null) {
                continue;
            }
            LocalDateTime end = b.time().plusMinutes(periodMinutes);
            if (!end.isAfter(now)) {
                out.add(b);
            }
        }
        return out;
    }

    static HtfTrend fromSessionOpen(
            List<TrendBar> bars,
            LocalDateTime now,
            String sessionOpenRaw,
            double minMovePoints,
            double pointSize
    ) {
        LocalTime open = TrendSessionEdge.parse(sessionOpenRaw, LocalTime.of(9, 0));
        LocalDate day = now.toLocalDate();
        TrendBar openBar = null;
        for (TrendBar b : bars) {
            if (b == null || b.time() == null) {
                continue;
            }
            if (!b.time().toLocalDate().equals(day)) {
                continue;
            }
            if (!b.time().toLocalTime().isBefore(open)) {
                openBar = b;
                break;
            }
        }
        if (openBar == null) {
            return FLAT;
        }
        double movePts = (bars.get(bars.size() - 1).close() - openBar.open()) / pointSize;
        if (movePts >= minMovePoints) {
            return UP;
        }
        if (movePts <= -minMovePoints) {
            return DOWN;
        }
        return FLAT;
    }

    static HtfTrend fromSlope(List<TrendBar> bars, int lookbackBars, double minMovePoints, double pointSize) {
        if (bars == null || bars.size() < 10 || pointSize <= 0) {
            return FLAT;
        }
        int n = Math.min(Math.max(10, lookbackBars), bars.size());
        List<TrendBar> w = bars.subList(bars.size() - n, bars.size());
        double movePts = (w.get(w.size() - 1).close() - w.get(0).close()) / pointSize;
        if (movePts >= minMovePoints) {
            return UP;
        }
        if (movePts <= -minMovePoints) {
            return DOWN;
        }
        return FLAT;
    }
}
