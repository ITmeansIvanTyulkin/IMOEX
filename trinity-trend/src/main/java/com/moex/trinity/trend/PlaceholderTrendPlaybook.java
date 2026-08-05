package com.moex.trinity.trend;

/**
 * Placeholder playbook until operator-selected strategies are wired.
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
}
