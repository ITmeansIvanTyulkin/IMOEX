package com.moex.trinity.calendararb;

import com.moex.trinity.shared.StrategyId;

/**
 * Strategy-3 research facade (futures calendar). Skeleton only — no trading logic.
 */
public class CalendarArbResearchService {

    public StrategyId strategyId() {
        return StrategyId.CALENDAR_ARB;
    }

    public boolean enabled() {
        return true;
    }

    public String statusMessage() {
        return "Calendar arbitrage module loaded (research skeleton; no live trading).";
    }
}
