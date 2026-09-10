package com.moex.cointegration.web;

import com.moex.cointegration.upsell.UpsellAccess;
import com.moex.cointegration.upsell.UpsellService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PairsStrategyHtmlTest {

    @Test
    void strategyPageMatchesCurrentPairsRobot() {
        UpsellService upsell = mock(UpsellService.class);
        when(upsell.access()).thenReturn(new UpsellAccess(
                false, false, "OFF", null, null, null, 5000, 7500, 15000
        ));
        AnalysisHtmlRenderer renderer = new AnalysisHtmlRenderer(
                upsell,
                com.moex.cointegration.config.CapitalProperties.defaults(),
                new com.moex.cointegration.product.ProductEditionService(
                        com.moex.cointegration.config.ProductProperties.defaults()),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                true, false, false
        );

        String html = renderer.renderStrategy();
        assertTrue(html.contains(">Пульт пар<"), html);
        assertTrue(html.contains("Парная стратегия сейчас"), html);
        assertTrue(html.contains("Фаворит отрасли"), html);
        assertTrue(html.contains("закрытия реестра"), html);
        assertTrue(html.contains("Наблюдение / Авто"), html);
        assertTrue(html.contains("id=\"core-roadmap\""), html);
        assertFalse(html.contains("Итог + новости"), html);
        assertFalse(html.contains("фокус — металлы"), html);
        assertFalse(html.contains("Функционал ATAS"), html);
        assertFalse(html.contains("Tiger"), html);
        assertFalse(html.contains("mean-reversion"), html);
        assertFalse(html.toLowerCase().contains("z-score"), html);
        assertFalse(html.contains("Kalman") || html.contains("kalman"), html);
        assertFalse(html.contains("CUSUM"), html);
        assertFalse(html.contains("§"), html);
    }
}
