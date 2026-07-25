package com.moex.cointegration.news;

import com.moex.cointegration.model.NewsItem;
import com.moex.cointegration.model.NewsRiskLevel;
import com.moex.cointegration.model.NewsTriggerHit;
import com.moex.cointegration.model.NewsTriggerType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsTriggerMatcherTest {

    private final NewsTriggerMatcher matcher = new NewsTriggerMatcher();

    @Test
    void detectsTradingHaltForTicker() {
        List<NewsItem> news = List.of(new NewsItem(
                1L,
                "О приостановке торгов ценными бумагами SBER",
                LocalDateTime.now(),
                "TEST"
        ));

        List<NewsTriggerHit> hits = matcher.match("SBER", "Сбербанк", "Сбербанк России", news);

        assertEquals(1, hits.size());
        assertEquals(NewsTriggerType.TRADING_HALT, hits.get(0).type());
        assertEquals(NewsRiskLevel.BLOCK, hits.get(0).severity());
    }

    @Test
    void ignoresUnrelatedNews() {
        List<NewsItem> news = List.of(new NewsItem(
                2L,
                "О приостановке торгов ценными бумагами GAZP",
                LocalDateTime.now(),
                "TEST"
        ));

        List<NewsTriggerHit> hits = matcher.match("SBER", "Сбербанк", "Сбербанк России", news);
        assertTrue(hits.isEmpty());
    }

    @Test
    void mentionsTickerByShortName() {
        assertTrue(matcher.mentionsTicker("Дивиденды Сбербанк за 2025 год", "SBER", "Сбербанк", null));
        assertFalse(matcher.mentionsTicker("Дивиденды Газпром", "SBER", "Сбербанк", null));
    }
}
