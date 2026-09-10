package com.moex.cointegration.web;

import com.moex.cointegration.upsell.UpsellAccess;
import com.moex.cointegration.upsell.UpsellService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrendArbStrategyHtmlTest {

    private static AnalysisHtmlRenderer renderer(boolean calendarArbEnabled) {
        UpsellService upsell = mock(UpsellService.class);
        when(upsell.access()).thenReturn(new UpsellAccess(
                false, false, "OFF", null, null, null, 5000, 7500, 15000
        ));
        return new AnalysisHtmlRenderer(
                upsell,
                com.moex.cointegration.config.CapitalProperties.defaults(),
                new com.moex.cointegration.product.ProductEditionService(
                        com.moex.cointegration.config.ProductProperties.defaults()),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                true, true, calendarArbEnabled
        );
    }

    @Test
    void trendStrategyPageCoversBothRobotsWithoutKitchenJargon() {
        String html = renderer(true).renderTrendStrategyPage();
        assertTrue(html.contains("href=\"/view/trend-strategy\""), html);
        assertTrue(html.contains(">Описание<"), html);
        assertTrue(html.contains("class=\"active\" data-requires=\"trend\">Описание<")
                || html.contains("class=\"active\" data-requires=\"trend\">Описание</a>"), html);
        assertTrue(html.contains("Тренд сейчас: два разных робота"), html);
        assertTrue(html.contains("Диапазонная: только нефть"), html);
        assertTrue(html.contains("Позиционная: час и сетка"), html);
        assertTrue(html.contains("Тумблеры Наблюдение / Авто"), html);
        assertTrue(html.contains("Живые заявки на срочном рынке у диапазонной нефти выключены"), html);
        assertTrue(html.contains("хвост ведём трейлом"), html);
        assertFalse(html.contains("только стоп в безубыток"), html);
        assertFalse(html.contains("Z-score") || html.toLowerCase().contains("z-score"), html);
        assertFalse(html.contains("Kalman") || html.contains("kalman"), html);
        assertFalse(html.contains("CUSUM"), html);
        assertFalse(html.contains("liveExecution"), html);
        assertFalse(html.contains("§"), html);
        assertFalse(html.contains("/view/trend-strategy</"), html);
    }

    @Test
    void calendarArbStrategyPageIsCalendarSpreadNotDirectional() {
        String html = renderer(true).renderCalendarArbStrategyPage();
        assertTrue(html.contains("href=\"/view/calendar-arb-strategy\""), html);
        assertTrue(html.contains("Календарный арбитраж сейчас"), html);
        assertTrue(html.contains("разницу дальнего и ближнего месяца"), html);
        assertTrue(html.contains("Нефть: только «скучный» рынок"), html);
        assertTrue(html.contains("Газ: свой сезон"), html);
        assertTrue(html.contains("две ноги вместе"), html);
        assertFalse(html.contains("mean-reversion"), html);
        assertFalse(html.toLowerCase().contains("z-score"), html);
        assertFalse(html.contains("§"), html);
        assertFalse(html.contains("Kalman") || html.contains("kalman"), html);
    }
}
