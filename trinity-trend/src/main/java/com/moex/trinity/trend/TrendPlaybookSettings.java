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
        /**
         * Aggregate M5 into senior TF for HTF bias: 60=H1, 15=M15, 0=off (M5 proxy only).
         * Bias refreshes when a completed bucket closes (hourly for H1).
         */
        int htfAggregatedMinutes,
        /** Lookback of completed aggregated bars for slope (default 3 H1). */
        int htfAggregatedBars,
        double counterTrendSizeFraction,
        double counterTrendMinRewardRisk,
        boolean counterTrendBounceOnly,
        double counterTrendMaxDistancePoints,
        boolean counterTrendRequireConfirm,
        boolean eventCalendarEnabled,
        String eventCalendarFile,
        int eventBlockMinutesBefore,
        int eventBlockMinutesAfter,
        /** Full checklist default false: BOUNCE + RETEST after break+hold. true = bounce only. */
        boolean aSetupBounceOnly,
        /** Fraction of full size at entry until BE (e.g. 0.4 ≈ ⅓–½). */
        double initialSizeFraction,
        /** Prefer broker tape VAP when marketdata streaming. */
        boolean preferMarketDataZones,
        /**
         * Operator style: trade TREND_HI/LO day shelves; skip noisy ACCUM/ZERO RETEST.
         * Checklist still discovers mid levels for context.
         */
        boolean preferStructuralEntries,
        /**
         * Operator FA/macro proxy for BR: smart knife filter on dump / melt-up
         * (confirmed BOT bounce may still pass when HTF ≠ DOWN).
         */
        boolean macroBiasEnabled,
        /** Day open→now move (points) that marks directional macro bias. */
        double macroMinDayMovePoints,
        /** Min shelf volume (bar vol or tape lots) — reject thin zones. */
        double minShelfVolume,
        /** Block new setups when realized day PnL ≤ −limit (0 = off). */
        double maxDayLossRub
) {
    public static TrendPlaybookSettings brDefaults() {
        TrendInstrumentSpec br = TrendInstrumentSpec.brDefaults();
        return new TrendPlaybookSettings(
                "levels-profile-br-m5",
                LimitGridStyle.MODERATE,
                1.0,
                br,
                1.0 / 3.0,
                3,
                5,
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
                4,
                12,
                10,
                "09:00",
                "23:50",
                40,
                30,
                50,
                24,
                true,
                60,
                3,
                0.6,
                2.0,
                true,
                5,
                true,
                true,
                "data/trend-event-calendar.json",
                45,
                30,
                false,
                0.4,
                true,
                true,
                true,
                80,
                30,
                1500
        );
    }

    /** Research / replay override. */
    public TrendPlaybookSettings withASetupBounceOnly(boolean bounceOnly) {
        return copy(
                playbookId, gridStyle, maxRiskPctEquity, instrument, tp1Fraction,
                touchLookback, candlesPerTouch, confirmBarsAfterBreak, levelLookbackBars,
                oneSetupPerZone, unlockDistancePoints, allowZonePad, minTouchCount, minHvnBands,
                sessionBiasBars, sessionBiasMinPoints, requireBounceConfirm, minRewardRisk,
                maxSetupsPerDay, cooldownBarsAfterSl, retestArmMaxDistancePoints,
                tradeSessionOpen, tradeSessionClose, noTradeAfterOpenMinutes, noTradeBeforeCloseMinutes,
                htfMinMovePoints, htfSlopeBars, htfRequireAgreement, htfAggregatedMinutes, htfAggregatedBars,
                counterTrendSizeFraction, counterTrendMinRewardRisk, counterTrendBounceOnly,
                counterTrendMaxDistancePoints, counterTrendRequireConfirm,
                eventCalendarEnabled, eventCalendarFile, eventBlockMinutesBefore, eventBlockMinutesAfter,
                bounceOnly, initialSizeFraction, preferMarketDataZones,
                preferStructuralEntries, macroBiasEnabled, macroMinDayMovePoints,
                minShelfVolume, maxDayLossRub
        );
    }

    public TrendPlaybookSettings withMaxSetupsPerDay(int max) {
        return copy(
                playbookId, gridStyle, maxRiskPctEquity, instrument, tp1Fraction,
                touchLookback, candlesPerTouch, confirmBarsAfterBreak, levelLookbackBars,
                oneSetupPerZone, unlockDistancePoints, allowZonePad, minTouchCount, minHvnBands,
                sessionBiasBars, sessionBiasMinPoints, requireBounceConfirm, minRewardRisk,
                max, cooldownBarsAfterSl, retestArmMaxDistancePoints,
                tradeSessionOpen, tradeSessionClose, noTradeAfterOpenMinutes, noTradeBeforeCloseMinutes,
                htfMinMovePoints, htfSlopeBars, htfRequireAgreement, htfAggregatedMinutes, htfAggregatedBars,
                counterTrendSizeFraction, counterTrendMinRewardRisk, counterTrendBounceOnly,
                counterTrendMaxDistancePoints, counterTrendRequireConfirm,
                eventCalendarEnabled, eventCalendarFile, eventBlockMinutesBefore, eventBlockMinutesAfter,
                aSetupBounceOnly, initialSizeFraction, preferMarketDataZones,
                preferStructuralEntries, macroBiasEnabled, macroMinDayMovePoints,
                minShelfVolume, maxDayLossRub
        );
    }

    public TrendPlaybookSettings withMaxDayLossRub(double loss) {
        return copy(
                playbookId, gridStyle, maxRiskPctEquity, instrument, tp1Fraction,
                touchLookback, candlesPerTouch, confirmBarsAfterBreak, levelLookbackBars,
                oneSetupPerZone, unlockDistancePoints, allowZonePad, minTouchCount, minHvnBands,
                sessionBiasBars, sessionBiasMinPoints, requireBounceConfirm, minRewardRisk,
                maxSetupsPerDay, cooldownBarsAfterSl, retestArmMaxDistancePoints,
                tradeSessionOpen, tradeSessionClose, noTradeAfterOpenMinutes, noTradeBeforeCloseMinutes,
                htfMinMovePoints, htfSlopeBars, htfRequireAgreement, htfAggregatedMinutes, htfAggregatedBars,
                counterTrendSizeFraction, counterTrendMinRewardRisk, counterTrendBounceOnly,
                counterTrendMaxDistancePoints, counterTrendRequireConfirm,
                eventCalendarEnabled, eventCalendarFile, eventBlockMinutesBefore, eventBlockMinutesAfter,
                aSetupBounceOnly, initialSizeFraction, preferMarketDataZones,
                preferStructuralEntries, macroBiasEnabled, macroMinDayMovePoints,
                minShelfVolume, loss
        );
    }

    private static TrendPlaybookSettings copy(
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
            int htfAggregatedMinutes,
            int htfAggregatedBars,
            double counterTrendSizeFraction,
            double counterTrendMinRewardRisk,
            boolean counterTrendBounceOnly,
            double counterTrendMaxDistancePoints,
            boolean counterTrendRequireConfirm,
            boolean eventCalendarEnabled,
            String eventCalendarFile,
            int eventBlockMinutesBefore,
            int eventBlockMinutesAfter,
            boolean aSetupBounceOnly,
            double initialSizeFraction,
            boolean preferMarketDataZones,
            boolean preferStructuralEntries,
            boolean macroBiasEnabled,
            double macroMinDayMovePoints,
            double minShelfVolume,
            double maxDayLossRub
    ) {
        return new TrendPlaybookSettings(
                playbookId, gridStyle, maxRiskPctEquity, instrument, tp1Fraction,
                touchLookback, candlesPerTouch, confirmBarsAfterBreak, levelLookbackBars,
                oneSetupPerZone, unlockDistancePoints, allowZonePad, minTouchCount, minHvnBands,
                sessionBiasBars, sessionBiasMinPoints, requireBounceConfirm, minRewardRisk,
                maxSetupsPerDay, cooldownBarsAfterSl, retestArmMaxDistancePoints,
                tradeSessionOpen, tradeSessionClose, noTradeAfterOpenMinutes, noTradeBeforeCloseMinutes,
                htfMinMovePoints, htfSlopeBars, htfRequireAgreement, htfAggregatedMinutes, htfAggregatedBars,
                counterTrendSizeFraction, counterTrendMinRewardRisk, counterTrendBounceOnly,
                counterTrendMaxDistancePoints, counterTrendRequireConfirm,
                eventCalendarEnabled, eventCalendarFile, eventBlockMinutesBefore, eventBlockMinutesAfter,
                aSetupBounceOnly, initialSizeFraction, preferMarketDataZones,
                preferStructuralEntries, macroBiasEnabled, macroMinDayMovePoints,
                minShelfVolume, maxDayLossRub
        );
    }
}
