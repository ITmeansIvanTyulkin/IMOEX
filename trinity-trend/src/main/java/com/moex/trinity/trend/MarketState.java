package com.moex.trinity.trend;

/**
 * Checklist §3: current market state — can we draw a trend line?
 */
public enum MarketState {
    TREND_UP,
    TREND_DOWN,
    RANGE;

    public boolean isTrend() {
        return this == TREND_UP || this == TREND_DOWN;
    }

    public TrendBias toBias() {
        return switch (this) {
            case TREND_UP -> TrendBias.UP;
            case TREND_DOWN -> TrendBias.DOWN;
            case RANGE -> TrendBias.NONE;
        };
    }
}
