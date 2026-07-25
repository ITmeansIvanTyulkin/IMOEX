package com.moex.cointegration.service;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.AdfResult;
import com.moex.cointegration.model.AlignedPairData;
import com.moex.cointegration.model.AnalysisReport;
import com.moex.cointegration.model.EngleGrangerResult;
import com.moex.cointegration.model.FinalTradeRecommendation;
import com.moex.cointegration.model.PairAnalysisResult;
import com.moex.cointegration.model.PriceSeries;
import com.moex.cointegration.model.TradingMetrics;
import com.moex.cointegration.model.TradingRecommendation;
import com.moex.cointegration.quant.EngleGrangerTest;
import com.moex.cointegration.quant.SpreadAnalytics;
import com.moex.cointegration.storage.MarketDataStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Оркестрация полного цикла анализа коинтеграции по всем парам IMOEX.
 */
@Service
public class CointegrationAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(CointegrationAnalysisService.class);

    private final MarketDataService marketDataService;
    private final PreprocessingService preprocessingService;
    private final MarketDataStorage storage;
    private final TradingRecommendationService recommendationService;
    private final FinalRecommendationService finalRecommendationService;
    private final ImoexProperties properties;

    public CointegrationAnalysisService(
            MarketDataService marketDataService,
            PreprocessingService preprocessingService,
            MarketDataStorage storage,
            TradingRecommendationService recommendationService,
            FinalRecommendationService finalRecommendationService,
            ImoexProperties properties
    ) {
        this.marketDataService = marketDataService;
        this.preprocessingService = preprocessingService;
        this.storage = storage;
        this.recommendationService = recommendationService;
        this.finalRecommendationService = finalRecommendationService;
        this.properties = properties;
    }

    /**
     * Выполняет полный pipeline: загрузка данных → предобработка → перебор пар →
     * Engle-Granger → метрики стратегии → топ-N по Sharpe → сохранение отчёта.
     *
     * @param refreshData если {@code true}, перед анализом обновляет свечи с MOEX
     * @return итоговый отчёт с топ-парами
     */
    public AnalysisReport runFullAnalysis(boolean refreshData) throws IOException {
        if (refreshData) {
            marketDataService.refreshMarketData();
        }

        Map<String, PriceSeries> loaded = marketDataService.loadAlignedPriceSeries();
        Map<String, PriceSeries> processed = preprocessingService.preprocess(loaded);
        Map<String, AdfResult> stationarity = preprocessingService.checkPriceStationarity(processed);

        long nonStationary = stationarity.values().stream().filter(r -> !r.stationary()).count();
        log.info("Price stationarity check: {} of {} series are non-stationary (expected for levels)",
                nonStationary, stationarity.size());

        List<String> tickers = processed.keySet().stream().sorted().toList();
        List<PairAnalysisResult> cointegratedPairs = new ArrayList<>();
        int pairsTested = 0;
        int pairsSkipped = 0;

        for (int i = 0; i < tickers.size(); i++) {
            for (int j = i + 1; j < tickers.size(); j++) {
                String tickerY = tickers.get(i);
                String tickerX = tickers.get(j);

                PriceSeries seriesY = processed.get(tickerY);
                PriceSeries seriesX = processed.get(tickerX);

                Optional<AlignedPairData> aligned = preprocessingService.alignPair(seriesY, seriesX);
                if (aligned.isEmpty()) {
                    pairsSkipped++;
                    continue;
                }

                pairsTested++;
                AlignedPairData pairData = aligned.get();

                EngleGrangerResult eg = EngleGrangerTest.test(
                        tickerY,
                        tickerX,
                        pairData.logY(),
                        pairData.logX(),
                        properties.cointegration().pValueThreshold()
                );

                if (!eg.cointegrated()) {
                    continue;
                }

                double[] spread = SpreadAnalytics.computeSpread(
                        pairData.logY(), pairData.logX(), eg.intercept(), eg.hedgeRatio());
                double[] zScores = SpreadAnalytics.zScores(spread);

                TradingMetrics metrics = SpreadAnalytics.simulateMeanReversion(
                        spread,
                        properties.commissionRate(),
                        properties.cointegration().zScoreEntry(),
                        properties.cointegration().zScoreExit()
                );

                cointegratedPairs.add(new PairAnalysisResult(
                        tickerY,
                        tickerX,
                        eg.intercept(),
                        eg.hedgeRatio(),
                        eg.adfStatistic(),
                        eg.pValue(),
                        metrics.sharpeRatio(),
                        metrics.maxDrawdown(),
                        metrics.halfLifeDays(),
                        metrics.tradeCount(),
                        metrics.totalReturn(),
                        SpreadAnalytics.toSeries(pairData.dates(), spread),
                        SpreadAnalytics.toSeries(pairData.dates(), zScores)
                ));
            }
        }

        List<PairAnalysisResult> topPairs = cointegratedPairs.stream()
                .sorted(Comparator.comparingDouble(PairAnalysisResult::sharpeRatio).reversed())
                .limit(properties.cointegration().topN())
                .toList();

        AnalysisReport report = new AnalysisReport(
                LocalDate.now(),
                tickers.size(),
                pairsTested,
                cointegratedPairs.size(),
                topPairs
        );

        storage.saveReport(report);

        List<TradingRecommendation> recommendations = recommendationService.analyzeAndPrint(cointegratedPairs);
        List<FinalTradeRecommendation> finals = finalRecommendationService.rebuildFromTechnical(recommendations);

        log.info("Analysis complete: {} tickers, {} pairs tested, {} skipped (short overlap), "
                        + "{} cointegrated, top {} selected, {} trading recommendations, {} final after news",
                tickers.size(), pairsTested, pairsSkipped, cointegratedPairs.size(), topPairs.size(),
                recommendations.size(), finals.size());

        return report;
    }
}
