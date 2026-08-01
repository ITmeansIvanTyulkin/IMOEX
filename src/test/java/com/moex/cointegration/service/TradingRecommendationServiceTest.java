package com.moex.cointegration.service;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.PairAnalysisResult;
import com.moex.cointegration.model.SpreadPoint;
import com.moex.cointegration.model.TradingSignal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradingRecommendationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesLongSignalWhenZBelowMinusTwo() throws Exception {
        TradingRecommendationService service = newService();
        PairAnalysisResult pair = samplePair(-2.5);

        var recommendations = service.analyzeAndPrint(List.of(pair));

        assertEquals(1, recommendations.size());
        assertEquals(TradingSignal.LONG_SPREAD, recommendations.get(0).signal());
    }

    @Test
    void generatesShortSignalWhenZAboveTwo() throws Exception {
        TradingRecommendationService service = newService();
        PairAnalysisResult pair = samplePair(2.3);

        var recommendations = service.analyzeAndPrint(List.of(pair));

        assertEquals(TradingSignal.SHORT_SPREAD, recommendations.get(0).signal());
    }

    @Test
    void watchWhenZBetweenThresholdAndEntry() throws Exception {
        TradingRecommendationService service = newService();
        // |Z| в зоне 1.5–2.0, без экстремума — WATCH
        assertEquals(TradingSignal.WATCH, service.analyzeAndPrint(List.of(samplePair(1.7, 1.6))).get(0).signal());
    }

    @Test
    void watchWhileExtremeButNoReversalYet() throws Exception {
        TradingRecommendationService service = newService();
        // Z растёт дальше за +2 — ждём разворот, не SHORT
        assertEquals(TradingSignal.WATCH, service.analyzeAndPrint(List.of(samplePair(2.8, 2.5))).get(0).signal());
    }

    @Test
    void noSignalWhenSharpeNegative() throws Exception {
        TradingRecommendationService service = newService();
        PairAnalysisResult pair = new PairAnalysisResult(
                "SBER", "LKOH", 1.0, 0.85, -4.0, 0.01,
                -0.5, 0.1, 12.0, 20, 0.15, 0.85,
                List.of(new SpreadPoint(LocalDate.of(2026, 7, 11), -0.05)),
                List.of(new SpreadPoint(LocalDate.of(2026, 7, 11), -2.5)),
                100.0, null
        );
        assertEquals(TradingSignal.NO_SIGNAL, service.analyzeAndPrint(List.of(pair)).get(0).signal());
    }

    private TradingRecommendationService newService() {
        ImoexProperties props = testProperties(tempDir.toString());
        return new TradingRecommendationService(props, new RiskPolicyService(props));
    }

    private static ImoexProperties testProperties(String dataDir) {
        return ImoexProperties.forTests(
                "https://iss.moex.com/iss",
                "TQBR",
                "IMOEX",
                5,
                0.0005,
                ImoexProperties.CointegrationProperties.of(0.05, 2.0, 0.0, 10),
                ImoexProperties.NewsProperties.withoutRss(false, 10, 10, 3),
                dataDir,
                dataDir + "/charts"
        );
    }

    private static PairAnalysisResult samplePair(double lastZ) {
        double prevZ = lastZ < 0 ? lastZ - 0.4 : lastZ + 0.4;
        return samplePair(lastZ, prevZ);
    }

    private static PairAnalysisResult samplePair(double lastZ, double prevZ) {
        LocalDate d0 = LocalDate.of(2026, 7, 10);
        LocalDate d1 = LocalDate.of(2026, 7, 11);
        return new PairAnalysisResult(
                "SBER",
                "LKOH",
                1.0,
                0.85,
                -4.0,
                0.01,
                1.2,
                0.1,
                12.0,
                20,
                0.15,
                0.85,
                List.of(new SpreadPoint(d0, -0.05), new SpreadPoint(d1, -0.04)),
                List.of(new SpreadPoint(d0, prevZ), new SpreadPoint(d1, lastZ)),
                100.0, null
        );
    }
}
