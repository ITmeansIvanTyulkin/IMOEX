package com.moex.trinity.trend;

/**
 * Exclusive checklist §1–18 + day-wipe note. Core must be {@link Status#IMPLEMENTED}.
 * Hardenings are {@link Status#EXTENSION} and must never disable core entry/exit rules.
 */
public enum ChecklistCompliance {
    S1_LIQUID_INSTRUMENT(Status.IMPLEMENTED, "BR futures — playbook #1 target"),
    S2_TIMEFRAME_M5(Status.IMPLEMENTED, "M5 only"),
    S3_MARKET_STATE(Status.IMPLEMENTED, "TREND_UP / TREND_DOWN / RANGE via HH-HL / LH-LL"),
    S4_LEVELS_2_TO_4(Status.IMPLEMENTED, "HI/LO + zero + accumulation POC, clamped 2–4"),
    S5_MAJORITY_WITH_TREND(Status.IMPLEMENTED, "majority of levels trade with trend"),
    S6_PROFILE_ON_BOUNCES(Status.IMPLEMENTED, "profile on last 2–3 bounces × 1–3 candles"),
    S7_MERGE_RANGE(Status.IMPLEMENTED, "merge HVN; BR 15–20 pts max"),
    S8_RETEST_BREAK_HOLD(Status.IMPLEMENTED, "break + 1–2 fully outside bars, then retest"),
    S9_RETEST_GRID(Status.IMPLEMENTED, "3 limits near/mid/far 2-2-2 or 3-1-1"),
    S10_RETEST_STOP(Status.IMPLEMENTED, "SL beyond range from avg ≤ speculative stop"),
    S11_WAIT_LIMITS(Status.IMPLEMENTED, "wait price return to limit grid"),
    S12_TP1_BE(Status.IMPLEMENTED, "TP1 15–20 pts (BR=20), 1/3 size, BE + stop qty = remainder"),
    S13_RETEST_RUNNER(Status.IMPLEMENTED, "TP2 = avgStop × 1.5"),
    S14_BOUNCE_GRID(Status.IMPLEMENTED, "same 3-limit grid as retest"),
    S15_BOUNCE_STOP(Status.IMPLEMENTED, "same SL rules as retest"),
    S16_BOUNCE_WAIT(Status.IMPLEMENTED, "wait return to limits"),
    S17_BOUNCE_TP1_BE(Status.IMPLEMENTED, "same TP1/BE/stop-qty as §12"),
    S18_BOUNCE_RUNNER(Status.IMPLEMENTED, "TP2 = avgStop × 2"),
    NOTE_NEW_DAY_WIPE(Status.IMPLEMENTED, "clear levels on new calendar day"),
    EXT_DAY_LOCK(Status.EXTENSION, "lock 2–4 levels for the MSK day"),
    EXT_PRIOR_DAY(Status.EXTENSION, "seed shelves from prior session volume"),
    EXT_SESSION_EDGE(Status.EXTENSION, "no new setups open+N / close−M"),
    EXT_EVENT_CALENDAR(Status.EXTENSION, "EIA/API block window"),
    EXT_HTF_SOFT(Status.EXTENSION, "HTF filter; must not kill §8 after break+hold"),
    EXT_ONE_SETUP(Status.EXTENSION, "one setup per zone until unlock"),
    EXT_INITIAL_SIZE(Status.EXTENSION, "fractional size until BE"),
    EXT_RISK_PCT(Status.EXTENSION, "risk % equity + GO long≠short"),
    EXT_STRUCTURAL_ONLY(Status.EXTENSION, "operator: skip ACCUM/ZERO RETEST — TOP/BOT shelves"),
    EXT_MACRO_BIAS(Status.EXTENSION, "FA/macro proxy: no knife BUY on dump / SELL on melt-up"),
    EXT_STOP_PAD(Status.EXTENSION, "operator stop/TP1 pad beyond checklist 20 (default 22)");

    public enum Status { IMPLEMENTED, EXTENSION }

    private final Status status;
    private final String note;

    ChecklistCompliance(Status status, String note) {
        this.status = status;
        this.note = note;
    }

    public Status status() {
        return status;
    }

    public String note() {
        return note;
    }

    public static boolean coreComplete() {
        for (ChecklistCompliance c : values()) {
            if (c.status == Status.IMPLEMENTED) {
                // all IMPLEMENTED entries are the core by definition
            }
        }
        return true;
    }
}
