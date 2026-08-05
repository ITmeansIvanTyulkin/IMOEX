package com.moex.cointegration.service;

import com.moex.cointegration.config.BrokerProperties;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.AnalysisReport;
import com.moex.cointegration.model.Candle;
import com.moex.cointegration.model.PricePoint;
import com.moex.cointegration.model.PriceSeries;
import com.moex.cointegration.storage.MarketDataStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CointegrationAnalysisServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void runFullAnalysisFindsCointegratedSyntheticPairAndPersists() throws Exception {
        ImoexProperties props = new ImoexProperties(
                "https://iss.moex.com/iss", "TQBR", "IMOEX", 5, 0.0005,
                new ImoexProperties.CointegrationProperties(0.05, 2.0, 0.0, 5, true, 40, 0.20, true, 1e-5, 1e-3, true, true),
                ImoexProperties.NewsProperties.withoutRss(false, 10, 10, 1),
                tempDir.toString(),
                tempDir.resolve("charts").toString(),
                ImoexProperties.RiskProperties.defaults(),
                new ImoexProperties.WalkForwardProperties(false, 200, 40, 40),
                new ImoexProperties.PaperProperties(false, 100_000, "paper.json", false, null, false, null, null, null, 0.30, 20.0, 40.0, true),
                new ImoexProperties.UniverseProperties(false, 60, 0, 0, 1.0, false, false, false, 0.0, 100.0, false, false),
                ImoexProperties.PortfolioProperties.defaults(),
                ImoexProperties.AuthProperties.defaults()
        );

        MarketDataService marketDataService = mock(MarketDataService.class);
        when(marketDataService.loadAlignedPriceSeries()).thenReturn(syntheticUniverse());

        PreprocessingService preprocessingService = new PreprocessingService();
        MarketDataStorage storage = new MarketDataStorage(props);
        RiskPolicyService riskPolicyService = new RiskPolicyService(props);
        TradingRecommendationService recommendationService =
                new TradingRecommendationService(props, riskPolicyService);
        FinalRecommendationService finalRecommendationService = mock(FinalRecommendationService.class);
        when(finalRecommendationService.rebuildFromTechnical(anyList())).thenReturn(List.of());
        when(finalRecommendationService.rebuildFromTechnical(anyList(), any())).thenReturn(List.of());
        when(marketDataService.loadAlignedHourlyPriceSeries()).thenReturn(Map.of());
        PaperTradingService paperTradingService = mock(PaperTradingService.class);
        WalkForwardService walkForwardService = mock(WalkForwardService.class);
        UniverseFilterService universeFilterService = new UniverseFilterService(storage, props);
        PairUniverseScanService pairUniverseScanService =
                new PairUniverseScanService(props, preprocessingService, universeFilterService);
        MarketRegimeService regimeService = mock(MarketRegimeService.class);
        when(regimeService.refresh()).thenReturn(com.moex.cointegration.model.MarketRegimeSnapshot.unknown());

        BrokerSettingsService brokerSettingsService = new BrokerSettingsService(BrokerProperties.defaults(), props);
        brokerSettingsService.load();

        CointegrationAnalysisService service = new CointegrationAnalysisService(
                marketDataService,
                preprocessingService,
                storage,
                recommendationService,
                finalRecommendationService,
                paperTradingService,
                walkForwardService,
                universeFilterService,
                pairUniverseScanService,
                regimeService,
                new MonthlyClusterReviewService(),
                new PairExecutionService(
                        brokerSettingsService,
                        new NoopBrokerClient(brokerSettingsService),
                        riskPolicyService,
                        storage,
                        props
                ),
                props,
                com.moex.cointegration.config.SessionProperties.defaults(),
                com.moex.cointegration.config.CapitalProperties.defaults()
        );

        AnalysisReport report = service.runFullAnalysis(false);

        assertTrue(report.pairsTested() >= 1);
        assertTrue(report.tickersAnalyzed() >= 2);
        verify(finalRecommendationService).rebuildFromTechnical(anyList(), any());
        assertTrue(storage.loadReport().isPresent());
    }

    private static Map<String, PriceSeries> syntheticUniverse() {
        Random random = new Random(42);
        int n = 260;
        LocalDate start = LocalDate.of(2024, 1, 2);
        double[] logX = new double[n];
        double[] logY = new double[n];
        double[] logZ = new double[n];
        double eps = 0.0;
        logX[0] = 5.0;
        logY[0] = 1.0 + 1.2 * logX[0];
        logZ[0] = 4.0;
        List<PricePoint> xPoints = new ArrayList<>();
        List<PricePoint> yPoints = new ArrayList<>();
        List<PricePoint> zPoints = new ArrayList<>();
        for (int t = 0; t < n; t++) {
            if (t > 0) {
                logX[t] = logX[t - 1] + random.nextGaussian() * 0.015;
                eps = 0.45 * eps + random.nextGaussian() * 0.02;
                logY[t] = 1.0 + 1.2 * logX[t] + eps;
                logZ[t] = logZ[t - 1] + random.nextGaussian() * 0.02; // independent RW
            }
            LocalDate d = start.plusDays(t);
            // skip weekends roughly
            if (d.getDayOfWeek().getValue() > 5) {
                continue;
            }
            xPoints.add(new PricePoint(d, Math.exp(logX[t])));
            yPoints.add(new PricePoint(d, Math.exp(logY[t])));
            zPoints.add(new PricePoint(d, Math.exp(logZ[t])));
        }
        Map<String, PriceSeries> map = new LinkedHashMap<>();
        map.put("AAA", new PriceSeries("AAA", yPoints));
        map.put("BBB", new PriceSeries("BBB", xPoints));
        map.put("CCC", new PriceSeries("CCC", zPoints));
        return map;
    }
}
