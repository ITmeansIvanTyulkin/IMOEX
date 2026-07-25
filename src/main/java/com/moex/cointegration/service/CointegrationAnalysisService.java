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
import com.moex.cointegration.quant.KalmanHedgeFilter;
import com.moex.cointegration.quant.SpreadAnalytics;
import com.moex.cointegration.quant.WalkForwardAnalyzer;
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
    private final PaperTradingService paperTradingService;
    private final WalkForwardService walkForwardService;
    private final UniverseFilterService universeFilterService;
    private final ImoexProperties properties;

    public CointegrationAnalysisService(
            MarketDataService marketDataService,
            PreprocessingService preprocessingService,
            MarketDataStorage storage,
            TradingRecommendationService recommendationService,
            FinalRecommendationService finalRecommendationService,
            PaperTradingService paperTradingService,
            WalkForwardService walkForwardService,
            UniverseFilterService universeFilterService,
            ImoexProperties properties
    ) {
        this.marketDataService = marketDataService;
        this.preprocessingService = preprocessingService;
        this.storage = storage;
        this.recommendationService = recommendationService;
        this.finalRecommendationService = finalRecommendationService;
        this.paperTradingService = paperTradingService;
        this.walkForwardService = walkForwardService;
        this.universeFilterService = universeFilterService;
        this.properties = properties;
    }

    /**
     * Выполняет полный pipeline: загрузка данных → предобработка → перебор пар →
     * Engle-Granger → FDR → метрики стратегии (rolling Z + risk) → топ-N → paper sync → walk-forward.
     */
    public AnalysisReport runFullAnalysis(boolean refreshData) throws IOException {
        if (refreshData) {
            marketDataService.refreshMarketData();
        }

        Map<String, PriceSeries> loaded = marketDataService.loadAlignedPriceSeries();
        Map<String, PriceSeries> filtered = universeFilterService.filter(loaded);
        Map<String, PriceSeries> processed = preprocessingService.preprocess(filtered);
        Map<String, AdfResult> stationarity = preprocessingService.checkPriceStationarity(processed);

        long nonStationary = stationarity.values().stream().filter(r -> !r.stationary()).count();
        log.info("Price stationarity check: {} of {} series are non-stationary (expected for levels)",
                nonStationary, stationarity.size());

        List<String> tickers = processed.keySet().stream().sorted().toList();
        List<Candidate> candidates = new ArrayList<>();
        int pairsTested = 0;
        int pairsSkipped = 0;

        ImoexProperties.CointegrationProperties coint = properties.cointegration();
        ImoexProperties.RiskProperties risk = properties.risk();

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
                        coint.pValueThreshold()
                );

                // Keep all EG results for FDR; filter after BH.
                candidates.add(new Candidate(tickerY, tickerX, pairData, eg));
            }
        }

        double[] pValues = candidates.stream().mapToDouble(c -> c.eg().pValue()).toArray();
        boolean[] passFdr = WalkForwardAnalyzer.benjaminiHochberg(pValues, coint.fdrQ());

        List<PairAnalysisResult> cointegratedPairs = new ArrayList<>();
        for (int idx = 0; idx < candidates.size(); idx++) {
            if (!passFdr[idx]) {
                continue;
            }
            Candidate c = candidates.get(idx);
            if (!c.eg().cointegrated()) {
                continue;
            }

            double[] spread;
            double intercept;
            double hedgeRatio;
            if (coint.kalmanEnabled()) {
                KalmanHedgeFilter.Result kf = KalmanHedgeFilter.filter(
                        c.pairData().logY(),
                        c.pairData().logX(),
                        c.eg().intercept(),
                        c.eg().hedgeRatio(),
                        coint.kalmanDelta(),
                        coint.kalmanVe()
                );
                spread = kf.spread();
                intercept = kf.lastIntercept();
                hedgeRatio = kf.lastBeta();
            } else {
                intercept = c.eg().intercept();
                hedgeRatio = c.eg().hedgeRatio();
                spread = SpreadAnalytics.computeSpread(
                        c.pairData().logY(), c.pairData().logX(), intercept, hedgeRatio);
            }

            double[] zScores = coint.rollingZEnabled()
                    ? SpreadAnalytics.rollingZScores(spread, coint.rollingZWindow())
                    : SpreadAnalytics.zScores(spread);

            TradingMetrics metrics = SpreadAnalytics.simulateMeanReversion(
                    spread,
                    properties.commissionRate(),
                    coint.zScoreEntry(),
                    coint.zScoreExit(),
                    replaceNanZWithZeroForMetrics(zScores),
                    risk.stopZ(),
                    risk.maxHoldBars(),
                    risk.borrowRateAnnual(),
                    coint.entryReversalRequired()
            );

            cointegratedPairs.add(new PairAnalysisResult(
                    c.tickerY(),
                    c.tickerX(),
                    intercept,
                    hedgeRatio,
                    c.eg().adfStatistic(),
                    c.eg().pValue(),
                    metrics.sharpeRatio(),
                    metrics.maxDrawdown(),
                    metrics.halfLifeDays(),
                    metrics.tradeCount(),
                    metrics.totalReturn(),
                    SpreadAnalytics.toSeries(c.pairData().dates(), spread),
                    SpreadAnalytics.toSeries(c.pairData().dates(), zScores)
            ));
        }

        List<PairAnalysisResult> topPairs = cointegratedPairs.stream()
                .sorted(Comparator.comparingDouble(PairAnalysisResult::sharpeRatio).reversed())
                .limit(coint.topN())
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
        paperTradingService.sync(finals, recommendations);

        if (properties.walkForward().enabled()) {
            try {
                walkForwardService.runForTopPairs(Math.min(coint.topN(), 10));
            } catch (Exception ex) {
                log.warn("Walk-forward failed: {}", ex.getMessage());
            }
        }

        log.info("Analysis complete: {} tickers, {} pairs tested, {} skipped, {} FDR-pass cointegrated, "
                        + "top {}, {} recommendations, {} final after news",
                tickers.size(), pairsTested, pairsSkipped, cointegratedPairs.size(), topPairs.size(),
                recommendations.size(), finals.size());

        return report;
    }

    /**
     * NaN rolling warmup → 0 so simulator does not enter on incomplete window,
     * while series stored for charts keep NaN semantics via original array.
     */
    private static double[] replaceNanZWithZeroForMetrics(double[] z) {
        double[] copy = z.clone();
        for (int i = 0; i < copy.length; i++) {
            if (Double.isNaN(copy[i])) {
                copy[i] = 0.0;
            }
        }
        return copy;
    }

    private record Candidate(
            String tickerY,
            String tickerX,
            AlignedPairData pairData,
            EngleGrangerResult eg
    ) {
    }
}
