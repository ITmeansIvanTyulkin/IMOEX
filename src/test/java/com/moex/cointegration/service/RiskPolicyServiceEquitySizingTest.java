package com.moex.cointegration.service;

import com.moex.cointegration.config.CapitalProperties;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.TradingRecommendation;
import com.moex.cointegration.model.TradingSignal;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskPolicyServiceEquitySizingTest {

    @Test
    void suggestedNotionalScalesWithEquityPercent() {
        ImoexProperties props = ImoexProperties.forTests(
                "https://iss.moex.com/iss", "TQBR", "IMOEX", 5, 0.0005,
                ImoexProperties.CointegrationProperties.of(0.05, 2.0, 0.0, 10),
                new ImoexProperties.NewsProperties(false, 10, 10, 1),
                "data", "data/charts"
        );
        props = new ImoexProperties(
                props.baseUrl(), props.board(), props.index(), props.historyYears(), props.commissionRate(),
                props.cointegration(), props.news(), props.dataDir(), props.chartsDir(),
                props.risk(), props.walkForward(),
                new ImoexProperties.PaperProperties(
                        true, 30_000, "paper.json", false, null, false, null, 20.0, true,
                        0.25, 20.0, 40.0),
                props.universe(), props.portfolio(), props.auth()
        );
        CapitalProperties capital200k = new CapitalProperties(200_000.0, 1_000_000.0, 1.0, 0.40, 0.60);
        RiskPolicyService risk = new RiskPolicyService(props, capital200k,
                new com.moex.cointegration.config.RegimeProperties(false, 14, 20.0, 25.0, 0.5, "SNDX"), null);

        TradingRecommendation rec = new TradingRecommendation(
                "SBER", "LKOH", TradingSignal.LONG_SPREAD, -2.2,
                LocalDate.of(2026, 7, 1), -0.1, 1.1, 5, 1.0, 0.01, "s", "d", null, null
        );
        assertEquals(50_000.0, props.paper().baseNotionalPerLeg(200_000.0), 1.0);
        assertTrue(risk.suggestedNotional(rec, false) > 0);
        assertTrue(risk.suggestedNotional(rec, false) <= 50_000.0);
    }
}
