package com.moex.trinity.shared;

/**
 * TRINITY strategy pillars. Used for module boundaries / feature flags — not a trading signal.
 */
public enum StrategyId {
    PAIRS,
    TREND,
    CALENDAR_ARB
}
