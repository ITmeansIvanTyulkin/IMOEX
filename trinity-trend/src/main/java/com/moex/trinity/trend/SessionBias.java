package com.moex.trinity.trend;

import java.util.List;

/**
 * Short-horizon session bias from recent M5 slope (anti-rally short / anti-dump long).
 */
public enum SessionBias {
    RALLY,
    DUMP,
    NEUTRAL;

    public static SessionBias fromBars(List<TrendBar> bars, int lookback, double minMovePoints, double pointSize) {
        if (bars == null || bars.size() < 10 || pointSize <= 0) {
            return NEUTRAL;
        }
        int n = Math.min(Math.max(10, lookback), bars.size());
        List<TrendBar> w = bars.subList(bars.size() - n, bars.size());
        double first = w.get(0).close();
        double last = w.get(w.size() - 1).close();
        double movePts = (last - first) / pointSize;
        if (movePts >= minMovePoints) {
            return RALLY;
        }
        if (movePts <= -minMovePoints) {
            return DUMP;
        }
        return NEUTRAL;
    }

    public boolean allowsBuy() {
        return this != DUMP;
    }

    public boolean allowsSell() {
        return this != RALLY;
    }
}
