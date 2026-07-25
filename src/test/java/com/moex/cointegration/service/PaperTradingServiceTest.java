package com.moex.cointegration.service;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.FinalTradeDecision;
import com.moex.cointegration.model.FinalTradeRecommendation;
import com.moex.cointegration.model.NewsRiskLevel;
import com.moex.cointegration.model.PairNewsAssessment;
import com.moex.cointegration.model.PaperTradeEntry;
import com.moex.cointegration.model.TradingRecommendation;
import com.moex.cointegration.model.TradingSignal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PaperTradingServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void opensPaperTradeOnEnterSignal() throws Exception {
        ImoexProperties props = new ImoexProperties(
                "https://iss.moex.com/iss", "TQBR", "IMOEX", 5, 0.0005,
                ImoexProperties.CointegrationProperties.of(0.05, 2.0, 0.0, 10),
                new ImoexProperties.NewsProperties(false, 10, 10, 1),
                tempDir.toString(),
                tempDir.resolve("charts").toString(),
                ImoexProperties.RiskProperties.defaults(),
                ImoexProperties.WalkForwardProperties.defaults(),
                new ImoexProperties.PaperProperties(true, 50_000, "paper.json"),
                ImoexProperties.AuthProperties.defaults()
        );
        PaperTradingService service = new PaperTradingService(props, new RiskPolicyService(props));

        TradingRecommendation tech = new TradingRecommendation(
                "SBER", "LKOH", TradingSignal.LONG_SPREAD, -2.4, LocalDate.now(),
                -0.1, 0.8, 10, 1.1, 0.01, "long", "details"
        );
        FinalTradeRecommendation fin = new FinalTradeRecommendation(
                tech,
                new PairNewsAssessment(NewsRiskLevel.LOW, false, "ok", List.of(), 10),
                FinalTradeDecision.ENTER,
                "enter",
                "guide"
        );

        List<PaperTradeEntry> opened = service.syncFromFinals(List.of(fin));
        assertEquals(1, opened.size());
        assertEquals("OPEN", opened.get(0).status());
        assertFalse(service.getJournal().isEmpty());
    }
}
