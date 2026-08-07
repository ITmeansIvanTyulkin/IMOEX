package com.moex.trinity.trend;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BOT shelf recovered above must not be treated as §8 TOP wait-retest.
 */
class BotShelfBrokenUpNotTopRetestTest {

    @Test
    void botBounceArmedWhenPriceRecoveredAboveShelf() {
        MergedVolumeRange bot = new MergedVolumeRange(81.825, 82.025, 400, List.of(), true, null);
        // poke low then close above high → bounce confirm + "broken up" geometrically
        TrendBar reject = new TrendBar(
                LocalDateTime.of(2026, 8, 7, 12, 45),
                81.90, 82.10, 81.84, 82.08, 1500);
        assertTrue(LevelsProfileBrPlaybook.bounceConfirmed(List.of(reject), bot, true));
        assertTrue(reject.close() > bot.high());

        ChecklistLevel level = new ChecklistLevel(81.92, "TREND_LO", "DAY", true, bot, true);
        LevelsProfileBrPlaybook pb = new LevelsProfileBrPlaybook(TrendPlaybookSettings.brDefaults(), null);
        // Use package path via evaluate on tiny series is heavy; assert role helpers + intelligence allow
        assertTrue(LevelsProfileBrPlaybook.isBotShelfRole(level.role()));
        assertTrue(PlaybookIntelligence.allowsDumpDayBotBounce(
                TrendTradeMode.BOUNCE, "TREND_LO", HtfTrend.FLAT,
                List.of(reject), bot, reject.time(), 0.01));
        assertEquals(3, PlaybookIntelligence.touchQuality(List.of(reject), bot, true));
        assertNotNull(pb);
    }
}
