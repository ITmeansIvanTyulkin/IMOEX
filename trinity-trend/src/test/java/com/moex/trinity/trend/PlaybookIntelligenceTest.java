package com.moex.trinity.trend;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybookIntelligenceTest {

    private static final MergedVolumeRange BOT = new MergedVolumeRange(
            81.825, 82.025, 500, List.of(), true, null);

    @Test
    void dumpPhaseAndTouchQuality() {
        assertEquals(
                PlaybookIntelligence.SessionPhase.MEAN_REVERT_AFTER_DUMP,
                PlaybookIntelligence.resolvePhase(-180, HtfTrend.DOWN, 80));
        assertEquals(
                PlaybookIntelligence.SessionPhase.CONTINUATION,
                PlaybookIntelligence.resolvePhase(-20, HtfTrend.DOWN, 80));
        TrendBar reject = bar(81.90, 82.00, 81.84, 81.96);
        assertEquals(3, PlaybookIntelligence.touchQuality(List.of(reject), BOT, true));
        assertEquals(1, PlaybookIntelligence.domSoftBonus(true, 900, 400));
    }

    @Test
    void dumpDayBotBounceAllowedWhenHtfDownButHeldAboveMid() {
        TrendBar a = bar(81.90, 81.95, 81.84, 81.93);
        TrendBar b = bar(81.93, 82.00, 81.88, 81.97);
        assertTrue(PlaybookIntelligence.allowsDumpDayBotBounce(
                TrendTradeMode.BOUNCE, "TREND_LO", HtfTrend.DOWN,
                List.of(a, b), BOT, LocalDateTime.of(2026, 8, 7, 13, 0), 0.01));
    }

    @Test
    void dumpDayBotBounceBlockedWithoutReject() {
        TrendBar knife = bar(81.88, 81.92, 81.83, 81.86);
        assertFalse(PlaybookIntelligence.allowsDumpDayBotBounce(
                TrendTradeMode.BOUNCE, "TREND_LO", HtfTrend.FLAT,
                List.of(knife), BOT, LocalDateTime.of(2026, 8, 7, 13, 0), 0.01));
    }

    @Test
    void preferLocalShelfSwitchesFromHiToBot() {
        ChecklistLevel hi = new ChecklistLevel(
                84.18, "TREND_HI", "DAY", false,
                new MergedVolumeRange(84.08, 84.28, 100, List.of(), true, null), false);
        ChecklistLevel lo = new ChecklistLevel(
                81.92, "TREND_LO", "DAY", true, BOT, false);
        List<TrendBar> nearBot = new ArrayList<>();
        LocalDateTime t = LocalDateTime.of(2026, 8, 7, 12, 0);
        for (int i = 0; i < 24; i++) {
            nearBot.add(new TrendBar(t.plusMinutes(i * 5L), 81.95, 82.02, 81.88, 81.96, 200));
        }
        ChecklistLevel out = PlaybookIntelligence.preferLocalShelf(
                hi, List.of(hi, lo), nearBot, 81.96, 10, 0.01, 24);
        assertEquals("TREND_LO", out.role());
    }

    private static TrendBar bar(double o, double h, double l, double c) {
        return new TrendBar(LocalDateTime.of(2026, 8, 7, 12, 45), o, h, l, c, 1200);
    }
}
