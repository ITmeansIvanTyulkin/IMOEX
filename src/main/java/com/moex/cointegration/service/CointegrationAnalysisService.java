package com.moex.cointegration.service;

import com.moex.cointegration.config.CapitalAllocator;
import com.moex.cointegration.config.CapitalProperties;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.config.SessionProperties;
import com.moex.cointegration.model.AdfResult;
import com.moex.cointegration.model.AlignedPairData;
import com.moex.cointegration.model.AnalysisReport;
import com.moex.cointegration.model.BookKind;
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
 * Оркестрация dual-book анализа: DAILY (FA) + INTRADAY (1H, без FA) в одном цикле.
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
    private final MarketRegimeService marketRegimeService;
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
            MarketRegimeService marketRegimeService,
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
        this.marketRegimeService = marketRegimeService;
        this.properties = properties;
        this.sessionProperties = sessionProperties;
        this.capitalProperties = capitalProperties;
    }

    /**
     * Dual-book pipeline: daily tech→FA→paper, затем 1H tech→(skip FA)→paper.
     * Капитал режется {@link CapitalAllocator}.
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
     * Только INTRADAY-книга: refresh 1H → EG/Z → paper (без FA). Для часового cron.
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

        BookParams params = BookParams.daily(properties);
        List<PairAnalysisResult> cointegratedPairs = scanPairs(processed, params);

        List<PairAnalysisResult> topPairs = cointegratedPairs.stream()
                .sorted(Comparator.comparingDouble(PairAnalysisResult::sharpeRatio).reversed())
                .limit(properties.cointegration().topN())
                .toList();

        AnalysisReport report = new AnalysisReport(
                LocalDate.now(),
                processed.size(),
                params.lastPairsTested,
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

        log.info("DAILY book: {} tickers, {} tested, {} FDR-pass, top {}, {} tech, {} final",
                processed.size(), params.lastPairsTested, cointegratedPairs.size(), topPairs.size(),
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

        BookParams params = BookParams.intraday(properties, sessionProperties);
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
        paperTradingService.sync(finals, recommendations, BookKind.INTRADAY,
                alloc.intradayMaxPairs(), alloc.intradayGrossCap());

        log.info("INTRADAY book: {} tickers, {} tested, {} FDR-pass, {} tech, {} final",
                processed.size(), params.lastPairsTested, cointegratedPairs.size(),
                recommendations.size(), finals.size());
    }

    private List<PairAnalysisResult> scanPairs(Map<String, PriceSeries> processed, BookParams params)
            throws IOException {
        List<String> tickers = processed.keySet().stream().sorted().toList();
        List<Candidate> candidates = new ArrayList<>();
        int pairsTested = 0;
        int pairsSkipped = 0;

        ImoexProperties.CointegrationProperties coint = properties.cointegration();

        for (int i = 0; i < tickers.size(); i++) {
            for (int j = i + 1; j < tickers.size(); j++) {
                String tickerY = tickers.get(i);
                String tickerX = tickers.get(j);

                Optional<AlignedPairData> aligned = preprocessingService.alignPair(
                        processed.get(tickerY), processed.get(tickerX));
                if (aligned.isEmpty()) {
                    pairsSkipped++;
                    continue;
                }
                if (!universeFilterService.allowPair(tickerY, tickerX, params.book)) {
                    pairsSkipped++;
                    continue;
                }

                pairsTested++;
                EngleGrangerResult eg = EngleGrangerTest.test(
                        tickerY,
                        tickerX,
                        aligned.get().logY(),
                        aligned.get().logX(),
                        coint.pValueThreshold()
                );
                candidates.add(new Candidate(tickerY, tickerX, aligned.get(), eg));
            }
        }

        params.lastPairsTested = pairsTested;
        params.lastPairsSkipped = pairsSkipped;

        double[] pValues = candidates.stream().mapToDouble(c -> c.eg().pValue()).toArray();
        boolean[] passFdr = WalkForwardAnalyzer.benjaminiHochberg(pValues, coint.fdrQ());

        List<PairAnalysisResult> cointegratedPairs = new ArrayList<>();
        ImoexProperties.RiskProperties risk = properties.risk();

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

            int rollingWindow = params.rollingZWindow;
            double[] zScores = coint.rollingZEnabled()
                    ? SpreadAnalytics.rollingZScores(spread, rollingWindow)
                    : SpreadAnalytics.zScores(spread);

            double stopZ = risk.adaptiveStopEnabled()
                    ? com.moex.cointegration.quant.AdaptiveStop.stopZ(
                    spread, risk.adaptiveStopBase(), risk.adaptiveStopCap(), 20, params.adaptiveLongWin)
                    : risk.stopZ();

            TradingMetrics metrics = SpreadAnalytics.simulateMeanReversion(
                    spread,
                    properties.commissionRate(),
                    coint.zScoreEntry(),
                    coint.zScoreExit(),
                    replaceNanZWithZeroForMetrics(zScores),
                    stopZ,
                    params.maxHoldBars,
                    risk.borrowRateAnnual(),
                    coint.entryReversalRequired(),
                    risk.trailZ(),
                    risk.partialTpFraction(),
                    params.barsPerYear
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
                    c.eg().rSquared(),
                    SpreadAnalytics.toSeries(c.pairData().begins(), spread),
                    SpreadAnalytics.toSeries(c.pairData().begins(), zScores)
            ));
        }
        return cointegratedPairs;
    }

    private static double[] replaceNanZWithZeroForMetrics(double[] z) {
        double[] copy = z.clone();
        for (int i = 0; i < copy.length; i++) {
            if (Double.isNaN(copy[i])) {
                copy[i] = 0.0;
            }
        }
        return copy;
    }

    private static final class BookParams {
        final BookKind book;
        final int rollingZWindow;
        final int maxHoldBars;
        final double barsPerYear;
        final int adaptiveLongWin;
        int lastPairsTested;
        int lastPairsSkipped;

        private BookParams(BookKind book, int rollingZWindow, int maxHoldBars, double barsPerYear, int adaptiveLongWin) {
            this.book = book;
            this.rollingZWindow = rollingZWindow;
            this.maxHoldBars = maxHoldBars;
            this.barsPerYear = barsPerYear;
            this.adaptiveLongWin = adaptiveLongWin;
        }

        static BookParams daily(ImoexProperties properties) {
            return new BookParams(
                    BookKind.DAILY,
                    properties.cointegration().rollingZWindow(),
                    properties.risk().maxHoldBars(),
                    252.0,
                    252
            );
        }

        static BookParams intraday(ImoexProperties properties, SessionProperties session) {
            int hours = session.hoursPerSession();
            return new BookParams(
                    BookKind.INTRADAY,
                    session.intradayRollingZWindow(),
                    session.intradayMaxHoldBars(),
                    session.barsPerYearIntraday(),
                    Math.max(60, hours * 20)
            );
        }
    }

    private record Candidate(
            String tickerY,
            String tickerX,
            AlignedPairData pairData,
            EngleGrangerResult eg
    ) {
    }
}
