package com.moex.trinity.trend;

import java.util.List;
import java.util.Optional;

/**
 * Facade for strategy 2 playbooks + robot evaluation.
 */
public class TrendResearchService {

    private final List<TrendPlaybook> playbooks;
    private final TrendRegimeSelector selector;

    public TrendResearchService(List<TrendPlaybook> playbooks, TrendRegimeSelector selector) {
        this.playbooks = playbooks == null ? List.of() : List.copyOf(playbooks);
        this.selector = selector == null ? new DefaultTrendRegimeSelector() : selector;
    }

    public List<TrendPlaybook> playbooks() {
        return playbooks;
    }

    public Optional<TrendPlaybook> activePlaybook(TrendRegimeContext context) {
        return selector.select(context, playbooks);
    }

    public Optional<TrendRobotPlan> evaluate(
            TrendRegimeContext context,
            TrendBarSeries series,
            TrendAccountContext account
    ) {
        return activePlaybook(context).flatMap(pb -> pb.evaluate(series, account));
    }
}
