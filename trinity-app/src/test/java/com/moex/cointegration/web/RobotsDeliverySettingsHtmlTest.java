package com.moex.cointegration.web;

import com.moex.cointegration.upsell.UpsellAccess;
import com.moex.cointegration.upsell.UpsellService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RobotsDeliverySettingsHtmlTest {

    private static AnalysisHtmlRenderer renderer(boolean pairs, boolean trend, boolean arb) {
        return renderer(pairs, trend, arb, true);
    }

    private static AnalysisHtmlRenderer renderer(boolean pairs, boolean trend, boolean arb, boolean spread) {
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
                pairs, trend, arb, spread
        );
    }

    @Test
    void settingsShowsTrendAndArbRobotCards() {
        String html = renderer(true, true, true).renderSettings();
        assertTrue(html.contains("id=\"robots-delivery\""), html);
        assertFalse(html.contains("id=\"settings-spread-auto-execution\""));
        assertFalse(html.contains(">Торговля спредом<"), html);
        assertFalse(html.contains("id=\"settings-pairs-auto-execution\""), html);
        assertFalse(html.contains(">Коинтеграция<"), html);
        assertTrue(html.contains("id=\"settings-brm-auto-execution\""));
        assertTrue(html.contains("id=\"settings-positional-auto-execution\""));
        assertTrue(html.contains("id=\"trend-auto-execution\""));
        assertTrue(html.contains("id=\"arb-auto-execution\""));
        assertTrue(html.contains(">BRM мини<"));
        assertTrue(html.contains(">Позиционная торговля<"));
        assertTrue(html.contains(">Диапазонная торговля<"));
        assertTrue(html.contains(">Арбитраж<"));
        assertTrue(html.contains("robots-delivery-grid"));
        assertFalse(html.contains("Trend playbook"), html);
        assertFalse(html.contains("Только сигнал"), html);
        int brm = html.indexOf("id=\"brm-playbook-settings\"");
        int pos = html.indexOf("id=\"positional-playbook-settings\"");
        int range = html.indexOf("id=\"trend-playbook-settings\"");
        int arb = html.indexOf("id=\"calendar-arb-settings\"");
        assertTrue(brm > 0 && brm < pos && pos < range && range < arb, "card order");
    }
}
