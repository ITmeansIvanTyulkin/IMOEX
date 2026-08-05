package com.moex.trinity.trend;

import java.util.List;
import java.util.Optional;

/**
 * Default selector: at most one playbook — first in list when regime is TREND-like; else empty.
 */
public class DefaultTrendRegimeSelector implements TrendRegimeSelector {

    @Override
    public Optional<TrendPlaybook> select(TrendRegimeContext context, List<TrendPlaybook> playbooks) {
        if (context == null || playbooks == null || playbooks.isEmpty()) {
            return Optional.empty();
        }
        String label = context.regimeLabel() == null ? "" : context.regimeLabel().trim().toUpperCase();
        if (!label.contains("TREND") && context.adx() < 25) {
            return Optional.empty();
        }
        return Optional.of(playbooks.get(0));
    }
}
