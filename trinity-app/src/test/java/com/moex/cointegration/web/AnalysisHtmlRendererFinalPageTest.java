package com.moex.cointegration.web;

import com.moex.cointegration.model.FinalTradeDecision;
import com.moex.cointegration.model.FinalTradeRecommendation;
import com.moex.cointegration.model.MarketRegimeSnapshot;
import com.moex.cointegration.model.NewsRiskLevel;
import com.moex.cointegration.model.PairNewsAssessment;
import com.moex.cointegration.model.RssHeadline;
import com.moex.cointegration.model.TradingRecommendation;
import com.moex.cointegration.model.TradingSignal;
import com.moex.cointegration.service.RssHeadlineService;
import com.moex.cointegration.upsell.UpsellAccess;
import com.moex.cointegration.upsell.UpsellService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalysisHtmlRendererFinalPageTest {

    @Test
    void emptyFinalRendersExplainPanelAndNewsHook() {
        UpsellService upsell = mock(UpsellService.class);
        when(upsell.access()).thenReturn(new UpsellAccess(
                false, false, "OFF", null, null, null, 5000, 7500, 15000
        ));
        AnalysisHtmlRenderer renderer = new AnalysisHtmlRenderer(
                upsell, com.moex.cointegration.config.CapitalProperties.defaults(), true, false, false
        );

        String html = renderer.renderFinalTable(
                List.of(),
                List.of(),
                MarketRegimeSnapshot.of(28.0, 20.0, 25.0),
                null,
                RssHeadlineService.Snapshot.disabled()
        );

        assertTrue(html.contains("final-explain"));
        assertTrue(html.contains("Что значит «Итог»"));
        assertTrue(html.contains("Почему сейчас 0 строк"));
        assertTrue(html.contains("TREND"));
        assertTrue(html.contains("ENTER / REDUCE / WATCH / BLOCK")
                || html.contains("Словарь: ENTER"));
        assertTrue(html.contains("final-news"));
        assertTrue(html.contains("Новости (RSS)"));
        assertFalse(html.contains("Итоговых рекомендаций нет."));
        assertFalse(html.contains("Строк: 0"));
    }

    @Test
    void nonEmptyFinalKeepsTableAndSurfacesConflictSummary() {
        UpsellService upsell = mock(UpsellService.class);
        when(upsell.access()).thenReturn(new UpsellAccess(
                false, false, "OFF", null, null, null, 5000, 7500, 15000
        ));
        AnalysisHtmlRenderer renderer = new AnalysisHtmlRenderer(
                upsell, com.moex.cointegration.config.CapitalProperties.defaults(), true, false, false
        );

        TradingRecommendation tech = new TradingRecommendation(
                "SBER", "VTBR", TradingSignal.LONG_SPREAD, -2.4,
                LocalDate.of(2026, 8, 1), 1.0, 1.2, 8.0, 1.1, 0.01,
                "КУПИТЬ спред", "details", 100.0, null
        );
        PairNewsAssessment news = new PairNewsAssessment(
                NewsRiskLevel.HIGH, true,
                "HIGH: EARNINGS_MISS — прибыль хуже ожиданий",
                List.of(), 10
        );
        FinalTradeRecommendation row = new FinalTradeRecommendation(
                tech, news, FinalTradeDecision.BLOCK,
                "CONFLICT: техника vs фундамент — вход ЗАПРЕЩЁН. EARNINGS_MISS: прибыль хуже",
                "beginner guide text",
                "Техника: Z = -2.40. Фундаментал: высокий риск."
        );

        RssHeadlineService.Snapshot rss = new RssHeadlineService.Snapshot(
                true, true, "Контекст FA",
                java.time.Instant.parse("2026-08-01T12:00:00Z"),
                List.of(new RssHeadline(
                        "Сбербанк прибыль снизилась",
                        "Interfax",
                        LocalDateTime.of(2026, 8, 1, 10, 0),
                        "https://example.com/news/1",
                        "SBER"
                ))
        );

        String html = renderer.renderFinalTable(
                List.of(row),
                List.of(tech),
                MarketRegimeSnapshot.of(15.0, 20.0, 25.0),
                null,
                rss
        );

        assertTrue(html.contains("final-explain"));
        assertTrue(html.contains("Почему именно такие"));
        assertTrue(html.contains("CONFLICT"));
        assertTrue(html.contains("SBER"));
        assertTrue(html.contains("Разбор для оператора"));
        assertTrue(html.contains("final-news-card"));
        assertTrue(html.contains("Сбербанк прибыль снизилась"));
        assertTrue(html.contains("table-wrap"));
    }
}
