package com.moex.trinity.trend;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Higher-timeframe / session trend for asymmetric long/short priority.
 * FLAT → no new trades.
 */
public enum HtfTrend {
    UP,
    DOWN,
    FLAT;

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
     * Resolve HTF: session-open→now move, optional slope agreement.
     * Disagreement or insufficient move → {@link #FLAT}.
     */
    public static HtfTrend resolve(List<TrendBar> bars, LocalDateTime now, TrendPlaybookSettings settings) {
        if (bars == null || bars.isEmpty() || now == null || settings == null) {
            return FLAT;
        }
        double point = settings.instrument().pointSize();
        if (point <= 0) {
            return FLAT;
        }
        double minMove = settings.htfMinMovePoints() > 0 ? settings.htfMinMovePoints() : 50;
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
