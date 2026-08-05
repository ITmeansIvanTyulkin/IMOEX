package com.moex.trinity.trend;

import com.moex.trinity.shared.StrategyId;

/**
 * Strategy-2 playbook contract. Research-only — no live/paper opens yet.
 */
public interface TrendPlaybook {

    String id();

    String displayName();

    /** Short human hint: when this playbook is eligible (e.g. "TREND + volume expansion"). */
    String whenApplicable();

    default StrategyId strategyId() {
        return StrategyId.TREND;
    }
}
