package com.moex.trinity.trend;

import java.util.Optional;

/**
 * Placeholder until operator selects a concrete playbook — kept for tests / fallback.
 */
public class PlaceholderTrendPlaybook implements TrendPlaybook {

    @Override
    public String id() {
        return "trend-placeholder";
    }

    @Override
    public String displayName() {
        return "Trend playbook (placeholder)";
    }

    @Override
    public String whenApplicable() {
        return "TREND regime — replace with selected volume/range/breakout playbooks";
    }

    @Override
    public Optional<TrendRobotPlan> evaluate(TrendBarSeries series, TrendAccountContext account) {
        return Optional.empty();
    }
}
