package com.moex.trinity.trend;

import java.util.List;
import java.util.Optional;

/**
 * Playbook arbitration policy (strategy #2):
 * <ul>
 *   <li><b>One playbook at a time</b> on the trend robot — never two conflicting BR grids.</li>
 *   <li>Eligible when index/regime is TREND-like (or ADX ≥ 25); otherwise empty (pairs own SIDEWAYS).</li>
 *   <li>If several playbooks are registered: prefer id matching {@code imoex.strategies.trend.playbook},
 *       else first registered bean.</li>
 *   <li>Future: score by instrument fit / HTF / session; still at most one armed plan per bar.</li>
 *   <li>Cross-strategy (pairs vs trend vs arb): ADX / CapitalAllocator at app layer — not this class.</li>
 * </ul>
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
        String preferred = context.preferredPlaybookId();
        if (preferred != null && !preferred.isBlank()) {
            Optional<TrendPlaybook> match = playbooks.stream()
                    .filter(p -> preferred.equalsIgnoreCase(p.id()))
                    .findFirst();
            if (match.isPresent()) {
                return match;
            }
        }
        return Optional.of(playbooks.get(0));
    }
}
