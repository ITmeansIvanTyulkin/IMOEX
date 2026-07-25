package com.moex.cointegration.service;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.PairAnalysisResult;
import com.moex.cointegration.model.SpreadPoint;
import com.moex.cointegration.model.TradingSignal;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradingRecommendationServiceTest {

    @Test
    void generatesLongSignalWhenZBelowMinusTwo() throws Exception {
        TradingRecommendationService service = new TradingRecommendationService(testProperties());
        PairAnalysisResult pair = samplePair(-2.5);

        var recommendations = service.analyzeAndPrint(List.of(pair));

        assertEquals(1, recommendations.size());
        assertEquals(TradingSignal.LONG_SPREAD, recommendations.get(0).signal());
    }

    @Test
    void generatesShortSignalWhenZAboveTwo() throws Exception {
        TradingRecommendationService service = new TradingRecommendationService(testProperties());
        PairAnalysisResult pair = samplePair(2.3);

        var recommendations = service.analyzeAndPrint(List.of(pair));

        assertEquals(TradingSignal.SHORT_SPREAD, recommendations.get(0).signal());
    }

    private static ImoexProperties testProperties() {
        return new ImoexProperties(
                "https://iss.moex.com/iss",
                "TQBR",
                "IMOEX",
                5,
                0.0005,
                new ImoexProperties.CointegrationProperties(0.05, 2.0, 0.0, 10),
                new ImoexProperties.NewsProperties(false, 10, 10, 3),
                "data",
                "data/charts"
        );
    }

    private static PairAnalysisResult samplePair(double lastZ) {
        LocalDate date = LocalDate.of(2026, 7, 11);
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
                List.of(new SpreadPoint(date, -0.05)),
                List.of(new SpreadPoint(date, lastZ))
        );
    }
}
