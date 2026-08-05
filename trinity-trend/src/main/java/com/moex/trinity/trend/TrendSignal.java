package com.moex.trinity.trend;

/**
 * Operator-facing signal when auto-execution is off: ticker + side only (plus light context).
 */
public record TrendSignal(
        String playbookId,
        String ticker,
        String timeframe,
        String side,
        String mode,
        String state,
        boolean actionable,
        String summary
) {
    public static TrendSignal from(TrendRobotPlan plan) {
        if (plan == null) {
            return new TrendSignal(null, null, null, "NONE", null, "NO_TRADE", false, "no plan");
        }
        String side = plan.actionable() ? (plan.buy() ? "BUY" : "SELL") : "NONE";
        String mode = plan.mode() == null ? null : plan.mode().name();
        String state = plan.state() == null ? null : plan.state().name();
        String summary;
        if (!plan.actionable()) {
            summary = plan.instrument() + ": " + (plan.rationale() == null ? state : plan.rationale());
        } else {
            summary = String.format("%s %s (%s) — %s",
                    plan.instrument(), side, mode == null ? "?" : mode, plan.rationale());
        }
        return new TrendSignal(
                plan.playbookId(),
                plan.instrument(),
                plan.timeframe(),
                side,
                mode,
                state,
                plan.actionable(),
                summary
        );
    }
}
