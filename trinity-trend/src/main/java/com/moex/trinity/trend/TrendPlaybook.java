package com.moex.trinity.trend;

import com.moex.trinity.shared.StrategyId;

import java.util.Optional;

/**
 * Strategy-2 playbook contract — trading robot brain (sandbox-first execution in app).
 */
public interface TrendPlaybook {

    String id();

    String displayName();

    /** Short human hint: when this playbook is eligible. */
    String whenApplicable();

    default StrategyId strategyId() {
        return StrategyId.TREND;
    }

    /**
     * Evaluate M5 series + account → optional order plan (limits / SL / staged TP).
     */
    Optional<TrendRobotPlan> evaluate(TrendBarSeries series, TrendAccountContext account);

    /**
     * Structure for operator chart (levels/zone) — no entry gates. Empty if unsupported.
     */
    default TrendStructureSnapshot structure(TrendBarSeries series) {
        return TrendStructureSnapshot.empty("playbook has no structure overlay");
    }
}
