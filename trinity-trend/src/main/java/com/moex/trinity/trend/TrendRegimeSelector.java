package com.moex.trinity.trend;

import java.util.List;
import java.util.Optional;

/**
 * Picks at most one playbook for the current regime context. Stub: first registered or empty.
 */
public interface TrendRegimeSelector {

    Optional<TrendPlaybook> select(TrendRegimeContext context, List<TrendPlaybook> playbooks);
}
