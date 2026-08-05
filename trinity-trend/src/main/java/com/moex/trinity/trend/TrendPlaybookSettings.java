package com.moex.trinity.trend;

/**
 * Immutable playbook knobs (bound from imoex.strategies.trend.*).
 */
public record TrendPlaybookSettings(
        String playbookId,
        LimitGridStyle gridStyle,
        double maxRiskPctEquity,
        TrendInstrumentSpec instrument,
        double tp1Fraction,
        int touchLookback,
        int candlesPerTouch,
        int confirmBarsAfterBreak,
        int levelLookbackBars,
        boolean oneSetupPerZone,
        double unlockDistancePoints,
        boolean allowZonePad,
        int minTouchCount,
        int minHvnBands,
        int sessionBiasBars,
        double sessionBiasMinPoints,
        boolean requireBounceConfirm,
        double minRewardRisk,
        int maxSetupsPerDay,
        int cooldownBarsAfterSl,
        double retestArmMaxDistancePoints,
        String tradeSessionOpen,
        String tradeSessionClose,
        int noTradeAfterOpenMinutes,
        int noTradeBeforeCloseMinutes,
        double htfMinMovePoints,
        int htfSlopeBars,
        boolean htfRequireAgreement,
        double counterTrendSizeFraction,
        double counterTrendMinRewardRisk,
        boolean counterTrendBounceOnly,
        double counterTrendMaxDistancePoints,
        boolean counterTrendRequireConfirm,
        boolean eventCalendarEnabled,
        String eventCalendarFile,
        int eventBlockMinutesBefore,
        int eventBlockMinutesAfter,
        /** A-setup: arm BOUNCE only (no RETEST chase). */
        boolean aSetupBounceOnly,
        /** Fraction of full size at entry until BE (e.g. 0.4 ≈ ⅓–½). */
        double initialSizeFraction,
        /** Prefer broker tape VAP when marketdata streaming. */
        boolean preferMarketDataZones
) {
    public static TrendPlaybookSettings brDefaults() {
        TrendInstrumentSpec br = TrendInstrumentSpec.br(15, 20, 15, 25, 7.0);
        return new TrendPlaybookSettings(
                "levels-profile-br-m5",
                LimitGridStyle.MODERATE,
                1.0,
                br,
                1.0 / 3.0,
                3,
                3,
                2,
                576,
                true,
                40,
                false,
                3,
                2,
                36,
                40,
                true,
                1.5,
                2,
                12,
                10,
                "09:00",
                "23:50",
                40,
                40,
                50,
                24,
                true,
                0.6,
                2.0,
                true,
                5,
                true,
                true,
                "data/trend-event-calendar.json",
                45,
                30,
                true,
                0.4,
                true
        );
    }

    /** Research / replay override. */
    public TrendPlaybookSettings withASetupBounceOnly(boolean bounceOnly) {
        return new TrendPlaybookSettings(
                playbookId, gridStyle, maxRiskPctEquity, instrument, tp1Fraction,
                touchLookback, candlesPerTouch, confirmBarsAfterBreak, levelLookbackBars,
                oneSetupPerZone, unlockDistancePoints, allowZonePad, minTouchCount, minHvnBands,
                sessionBiasBars, sessionBiasMinPoints, requireBounceConfirm, minRewardRisk,
                maxSetupsPerDay, cooldownBarsAfterSl, retestArmMaxDistancePoints,
                tradeSessionOpen, tradeSessionClose, noTradeAfterOpenMinutes, noTradeBeforeCloseMinutes,
                htfMinMovePoints, htfSlopeBars, htfRequireAgreement,
                counterTrendSizeFraction, counterTrendMinRewardRisk, counterTrendBounceOnly,
                counterTrendMaxDistancePoints, counterTrendRequireConfirm,
                eventCalendarEnabled, eventCalendarFile, eventBlockMinutesBefore, eventBlockMinutesAfter,
                bounceOnly, initialSizeFraction, preferMarketDataZones
        );
    }
}
