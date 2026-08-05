package com.moex.trinity.trend;

import java.util.List;
import java.util.Optional;

/**
 * Picks at most one trend playbook for the bar / session context.
 */
public interface TrendRegimeSelector {

    Optional<TrendPlaybook> select(TrendRegimeContext context, List<TrendPlaybook> playbooks);
}
