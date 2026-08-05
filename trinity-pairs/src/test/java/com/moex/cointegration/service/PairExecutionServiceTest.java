package com.moex.cointegration.service;

import com.moex.cointegration.config.BrokerProperties;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.BookKind;
import com.moex.cointegration.model.BrokerExecutionStatus;
import com.moex.cointegration.model.BrokerMode;
import com.moex.cointegration.model.BrokerOrderSide;
import com.moex.cointegration.model.BrokerOrderType;
import com.moex.cointegration.model.FinalTradeDecision;
import com.moex.cointegration.model.FinalTradeRecommendation;
import com.moex.cointegration.model.NewsRiskLevel;
import com.moex.cointegration.model.PairExecutionPlan;
import com.moex.cointegration.model.PairNewsAssessment;
import com.moex.cointegration.model.TradingRecommendation;
import com.moex.cointegration.model.TradingSignal;
import com.moex.cointegration.storage.MarketDataStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PairExecutionServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void buildsLimitPlanForLongSpread() throws Exception {
        ImoexProperties props = baseProps();
        MarketDataStorage storage = new MarketDataStorage(props);
        storage.saveCandles("SBER", List.of(new com.moex.cointegration.model.Candle(
                LocalDate.of(2026, 7, 28), 100, 101, 99, 100, 1_000
        )));
        storage.saveCandles("LKOH", List.of(new com.moex.cointegration.model.Candle(
                LocalDate.of(2026, 7, 28), 7000, 7050, 6950, 7000, 1_000
        )));

        BrokerProperties broker = new BrokerProperties(
                true, "T_INVEST", "AUTO", true, "", "",
                true, true, false, false, 15.0, 60, 35.0, false, true
        );
        BrokerSettingsService settings = new BrokerSettingsService(broker, props);
        settings.load();
        PairExecutionService service = new PairExecutionService(
                settings, new NoopBrokerClient(settings), new RiskPolicyService(props), storage, props
        );

        PairExecutionPlan plan = service.buildPlan(finalRec(TradingSignal.LONG_SPREAD), BookKind.DAILY);
        assertEquals(BrokerMode.AUTO, plan.mode());
        assertEquals(BrokerOrderSide.BUY, plan.legY().side());
        assertEquals(BrokerOrderSide.SELL, plan.legX().side());
        assertEquals(BrokerOrderType.LIMIT, plan.legY().orderType());
        assertNotNull(plan.legY().limitPrice());
        assertNotNull(plan.legX().limitPrice());
    }

    @Test
    void blocksSubmitWhenManualConfirmRequested() throws Exception {
        ImoexProperties props = baseProps();
        MarketDataStorage storage = new MarketDataStorage(props);
        BrokerProperties broker = new BrokerProperties(
                true, "T_INVEST", "MANUAL_CONFIRM", true, "", "",
                true, true, false, false, 15.0, 60, 35.0, false, true
        );
        BrokerSettingsService settings = new BrokerSettingsService(broker, props);
        settings.load();
        PairExecutionService service = new PairExecutionService(
                settings, new NoopBrokerClient(settings), new RiskPolicyService(props), storage, props
        );

        var report = service.execute(finalRec(TradingSignal.SHORT_SPREAD), BookKind.DAILY, true);
        assertEquals(BrokerExecutionStatus.BLOCKED_MANUAL_CONFIRM, report.status());
        assertEquals(BrokerMode.MANUAL_CONFIRM, report.mode());
    }

    private ImoexProperties baseProps() {
        return new ImoexProperties(
                "https://iss.moex.com/iss", "TQBR", "IMOEX", 5, 0.0005,
                ImoexProperties.CointegrationProperties.of(0.05, 2.0, 0.0, 10),
                ImoexProperties.NewsProperties.withoutRss(false, 10, 10, 1),
                tempDir.toString(),
                tempDir.resolve("charts").toString(),
                ImoexProperties.RiskProperties.defaults(),
                ImoexProperties.WalkForwardProperties.defaults(),
                new ImoexProperties.PaperProperties(true, 50_000, "paper.json", false, null, false, null,
                        0.0, false, 0.30, 20.0, 40.0, false),
                ImoexProperties.UniverseProperties.defaults(),
                ImoexProperties.PortfolioProperties.defaults(),
                ImoexProperties.AuthProperties.defaults()
        );
    }

    private static FinalTradeRecommendation finalRec(TradingSignal signal) {
        TradingRecommendation tech = new TradingRecommendation(
                "SBER", "LKOH", signal, signal == TradingSignal.LONG_SPREAD ? -2.2 : 2.2,
                LocalDate.of(2026, 7, 28), -0.1, 1.1, 8, 1.3, 0.01,
                "summary", "details", null, null
        );
        return new FinalTradeRecommendation(
                tech,
                new PairNewsAssessment(NewsRiskLevel.LOW, false, "ok", List.of(), 10),
                FinalTradeDecision.ENTER,
                "enter",
                "guide",
                "rationale"
        );
    }
}
