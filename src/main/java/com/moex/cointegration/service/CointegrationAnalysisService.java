package com.moex.cointegration.service;

import com.moex.cointegration.config.CapitalAllocator;
import com.moex.cointegration.config.CapitalProperties;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.config.SessionProperties;
import com.moex.cointegration.model.AnalysisReport;
import com.moex.cointegration.model.AdfResult;
import com.moex.cointegration.model.BookKind;
import com.moex.cointegration.model.ClusterReviewReport;
import com.moex.cointegration.model.FinalTradeRecommendation;
import com.moex.cointegration.model.PairAnalysisResult;
import com.moex.cointegration.model.PriceSeries;
import com.moex.cointegration.model.TradingRecommendation;
import com.moex.cointegration.storage.MarketDataStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Оркестрация dual-book анализа: DAILY (FA + paper) + INTRADAY research (без paper при research-only).
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
    private final PairUniverseScanService pairUniverseScanService;
    private final MarketRegimeService marketRegimeService;
    private final MonthlyClusterReviewService monthlyClusterReviewService;
    private final PairExecutionService pairExecutionService;
    private final ImoexProperties properties;
    private final SessionProperties sessionProperties;
    private final CapitalProperties capitalProperties;

    public CointegrationAnalysisService(
            MarketDataService marketDataService,
            PreprocessingService preprocessingService,
            MarketDataStorage storage,
            TradingRecommendationService recommendationService,
            FinalRecommendationService finalRecommendationService,
            PaperTradingService paperTradingService,
            WalkForwardService walkForwardService,
            UniverseFilterService universeFilterService,
            PairUniverseScanService pairUniverseScanService,
            MarketRegimeService marketRegimeService,
            MonthlyClusterReviewService monthlyClusterReviewService,
            PairExecutionService pairExecutionService,
            ImoexProperties properties,
            SessionProperties sessionProperties,
            CapitalProperties capitalProperties
    ) {
        this.marketDataService = marketDataService;
        this.preprocessingService = preprocessingService;
        this.storage = storage;
        this.recommendationService = recommendationService;
        this.finalRecommendationService = finalRecommendationService;
        this.paperTradingService = paperTradingService;
        this.walkForwardService = walkForwardService;
        this.universeFilterService = universeFilterService;
        this.pairUniverseScanService = pairUniverseScanService;
        this.marketRegimeService = marketRegimeService;
        this.monthlyClusterReviewService = monthlyClusterReviewService;
        this.pairExecutionService = pairExecutionService;
        this.properties = properties;
        this.sessionProperties = sessionProperties;
        this.capitalProperties = capitalProperties;
    }

    /**
     * Dual-book pipeline: daily tech→FA→paper, затем 1H research (paper только если не research-only).
     */
    public AnalysisReport runFullAnalysis(boolean refreshData) throws IOException {
        if (refreshData) {
            marketDataService.refreshMarketData();
        }
        marketRegimeService.refresh();

        CapitalAllocator.Allocation alloc = capitalProperties.allocation();
        log.info("Capital allocation: equity={} dailyMax={} intraMax={} dailyGross≈{} intraGross≈{}",
                String.format("%.0f", alloc.equityRub()),
                alloc.dailyMaxPairs(),
                alloc.intradayMaxPairs(),
                String.format("%.0f", alloc.dailyGrossCap()),
                String.format("%.0f", alloc.intradayGrossCap()));

        AnalysisReport dailyReport = runDailyBook(alloc);

        try {
            runIntradayBook(refreshData, alloc);
        } catch (Exception ex) {
            log.warn("INTRADAY book failed (DAILY report kept): {}", ex.getMessage(), ex);
        }

        if (properties.walkForward().enabled()) {
            try {
                walkForwardService.runForTopPairs(Math.min(properties.cointegration().topN(), 10));
            } catch (Exception ex) {
                log.warn("Walk-forward failed: {}", ex.getMessage());
            }
        }

        return dailyReport;
    }

    /**
     * Только INTRADAY: refresh 1H → EG/Z → (paper только если не research-only). Для часового cron.
     */
    public void runIntradayOnly(boolean refreshHourly) throws IOException {
        marketRegimeService.refresh();
        CapitalAllocator.Allocation alloc = capitalProperties.allocation();
        runIntradayBook(refreshHourly, alloc);
    }

    private AnalysisReport runDailyBook(CapitalAllocator.Allocation alloc) throws IOException {
        Map<String, PriceSeries> loaded = marketDataService.loadAlignedPriceSeries();
        Map<String, PriceSeries> filtered = universeFilterService.filter(loaded);
        Map<String, PriceSeries> processed = preprocessingService.preprocess(filtered);
        Map<String, AdfResult> stationarity = preprocessingService.checkPriceStationarity(processed);

        long nonStationary = stationarity.values().stream().filter(r -> !r.stationary()).count();
        log.info("DAILY price stationarity: {} of {} non-stationary", nonStationary, stationarity.size());

        PairScanParams params = PairScanParams.daily(properties);
        List<PairAnalysisResult> cointegratedPairs = scanPairs(processed, params);
        LocalDate asOf = LocalDate.now();
        ClusterReviewReport clusterReview = monthlyClusterReviewService.review(
                paperTradingService.getJournal(BookKind.DAILY), BookKind.DAILY, asOf);
        cointegratedPairs = monthlyClusterReviewService.filterPairs(
                cointegratedPairs, clusterReview,
                paperTradingService.getJournal(BookKind.DAILY), BookKind.DAILY, asOf);

        List<PairAnalysisResult> topPairs = cointegratedPairs.stream()
                .sorted(Comparator.comparingDouble(PairAnalysisResult::sharpeRatio).reversed())
                .limit(properties.cointegration().topN())
                .toList();

        AnalysisReport report = new AnalysisReport(
                asOf,
                processed.size(),
                params.lastPairsTested(),
                cointegratedPairs.size(),
                topPairs
        );
        storage.saveReport(report);

        List<TradingRecommendation> recommendations = recommendationService.analyzeAndPrint(
                cointegratedPairs, BookKind.DAILY, 1.0, null, null);
        List<FinalTradeRecommendation> finals = finalRecommendationService.rebuildFromTechnical(
                recommendations, BookKind.DAILY);
        paperTradingService.sync(finals, recommendations, BookKind.DAILY,
                alloc.dailyMaxPairs(), alloc.dailyGrossCap());
        autoExecuteDaily(finals);

        log.info("DAILY book: {} tickers, {} tested, {} cluster-pass, top {}, {} tech, {} final",
                processed.size(), params.lastPairsTested(), cointegratedPairs.size(), topPairs.size(),
                recommendations.size(), finals.size());
        return report;
    }

    private void runIntradayBook(boolean refreshData, CapitalAllocator.Allocation alloc) throws IOException {
        List<String> dailyTickers = storage.listStoredTickers();
        if (refreshData && !dailyTickers.isEmpty()) {
            marketDataService.refreshHourlyCandles(dailyTickers, sessionProperties.hourlyLookbackDays());
        }

        Map<String, PriceSeries> loaded = marketDataService.loadAlignedHourlyPriceSeries();
        if (loaded.isEmpty()) {
            log.warn("INTRADAY book skipped: no hourly candles (run with refresh=true to download)");
            return;
        }

        Map<String, PriceSeries> filtered = universeFilterService.filter(loaded, BookKind.INTRADAY);
        Map<String, PriceSeries> processed = preprocessingService.preprocess(filtered);

        PairScanParams params = PairScanParams.intraday(properties, sessionProperties);
        List<PairAnalysisResult> cointegratedPairs = scanPairs(processed, params);

        List<TradingRecommendation> recommendations = recommendationService.analyzeAndPrint(
                cointegratedPairs,
                BookKind.INTRADAY,
                sessionProperties.hoursPerSession(),
                sessionProperties.intradayMinHalfLifeDays(),
                sessionProperties.intradayTradeMaxHalfLifeDays()
        );
        // без FA
        List<FinalTradeRecommendation> finals = finalRecommendationService.rebuildFromTechnical(
                recommendations, BookKind.INTRADAY);
        if (properties.paper().intradayResearchOnlyFlag()) {
            log.info("INTRADAY research-only: {} tickers, {} tested, {} FDR-pass, {} tech — no paper trading",
                    processed.size(), params.lastPairsTested(), cointegratedPairs.size(),
                    recommendations.size());
            return;
        }
        paperTradingService.sync(finals, recommendations, BookKind.INTRADAY,
                alloc.intradayMaxPairs(), alloc.intradayGrossCap());

        log.info("INTRADAY book: {} tickers, {} tested, {} FDR-pass, {} tech, {} final",
                processed.size(), params.lastPairsTested(), cointegratedPairs.size(),
                recommendations.size(), finals.size());
    }

    private List<PairAnalysisResult> scanPairs(Map<String, PriceSeries> processed, PairScanParams params)
            throws IOException {
        Map<String, List<com.moex.cointegration.model.Candle>> candlesByTicker =
                universeFilterService.loadCandlesForTickers(processed.keySet(), params.book());
        return pairUniverseScanService.scan(processed, candlesByTicker, params);
    }

    private void autoExecuteDaily(List<FinalTradeRecommendation> finals) {
        if (finals == null || finals.isEmpty()) {
            return;
        }
        try {
            if (pairExecutionService.status().enabled() && pairExecutionService.status().autoExecuteAfterAnalysis()) {
                pairExecutionService.executeActionableDaily(finals);
            }
        } catch (Exception ex) {
            log.warn("Broker auto-execution skipped: {}", ex.getMessage());
        }
    }
}
