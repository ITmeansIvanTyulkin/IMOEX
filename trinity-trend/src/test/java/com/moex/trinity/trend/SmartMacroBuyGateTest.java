package com.moex.trinity.trend;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmartMacroBuyGateTest {

    private static final MergedVolumeRange BOT = new MergedVolumeRange(
            81.825, 82.025, 500, List.of(), true, null);

    @Test
    void confirmedBotBounceAllowedWhenHtfNotDown() {
        TrendBar reject = bar(81.90, 82.00, 81.84, 81.96);
        assertTrue(LevelsProfileBrPlaybook.macroAllowsConfirmedBotBounce(
                TrendTradeMode.BOUNCE, "TREND_LO", HtfTrend.FLAT, List.of(reject), BOT));
        assertTrue(LevelsProfileBrPlaybook.macroAllowsConfirmedBotBounce(
                TrendTradeMode.BOUNCE, "TREND_LO", HtfTrend.UP, List.of(reject), BOT));
    }

    @Test
    void dumpDayStillBlocksRetestBuyAndHtfDownBounce() {
        TrendBar reject = bar(81.90, 82.00, 81.84, 81.96);
        assertFalse(LevelsProfileBrPlaybook.macroAllowsConfirmedBotBounce(
                TrendTradeMode.RETEST, "TREND_LO", HtfTrend.FLAT, List.of(reject), BOT));
        assertFalse(LevelsProfileBrPlaybook.macroAllowsConfirmedBotBounce(
                TrendTradeMode.BOUNCE, "TREND_LO", HtfTrend.DOWN, List.of(reject), BOT));
        assertFalse(LevelsProfileBrPlaybook.macroAllowsConfirmedBotBounce(
                TrendTradeMode.BOUNCE, "TREND_HI", HtfTrend.FLAT, List.of(reject), BOT));
    }

    @Test
    void withoutClosedRejectBounceNotAllowed() {
        // Close still below mid → no reject confirm
        TrendBar knife = bar(81.88, 81.92, 81.83, 81.86);
        assertFalse(LevelsProfileBrPlaybook.macroAllowsConfirmedBotBounce(
                TrendTradeMode.BOUNCE, "TREND_LO", HtfTrend.FLAT, List.of(knife), BOT));
        assertTrue(LevelsProfileBrPlaybook.isBotShelfRole("BOTTOM"));
        assertTrue(LevelsProfileBrPlaybook.isBotShelfRole("BOT"));
    }

    private static TrendBar bar(double o, double h, double l, double c) {
        return new TrendBar(LocalDateTime.of(2026, 8, 7, 15, 55), o, h, l, c, 1200);
    }
}
