package com.moex.cointegration.controller;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.AnalysisReport;
import com.moex.cointegration.model.BookKind;
import com.moex.cointegration.model.ChartPayload;
import com.moex.cointegration.model.FinalTradeRecommendation;
import com.moex.cointegration.model.HistoricalReplayReport;
import com.moex.cointegration.model.PairAnalysisResult;
import com.moex.cointegration.model.PaperJournal;
import com.moex.cointegration.model.TradingRecommendation;
import com.moex.cointegration.model.TradingSignal;
import com.moex.cointegration.model.WalkForwardReport;
import com.moex.cointegration.service.ChartDataService;
import com.moex.cointegration.service.ChartService;
import com.moex.cointegration.service.CointegrationAnalysisService;
import com.moex.cointegration.service.FinalRecommendationService;
import com.moex.cointegration.service.HistoricalReplayService;
import com.moex.cointegration.service.MarketDataService;
import com.moex.cointegration.service.PaperTradingService;
import com.moex.cointegration.service.PairExecutionService;
import com.moex.cointegration.service.RiskPolicyService;
import com.moex.cointegration.service.TradingRecommendationService;
import com.moex.cointegration.service.WalkForwardService;
import com.moex.cointegration.storage.MarketDataStorage;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST API для загрузки данных, запуска анализа и получения графиков.
 */
@RestController
@RequestMapping("/api")
public class AnalysisController {

    private final CointegrationAnalysisService analysisService;
    private final MarketDataService marketDataService;
    private final ChartService chartService;
    private final ChartDataService chartDataService;
    private final MarketDataStorage storage;
    private final TradingRecommendationService recommendationService;
    private final FinalRecommendationService finalRecommendationService;
    private final WalkForwardService walkForwardService;
    private final PaperTradingService paperTradingService;
    private final PairExecutionService pairExecutionService;
    private final RiskPolicyService riskPolicyService;
    private final HistoricalReplayService historicalReplayService;

    public AnalysisController(
            CointegrationAnalysisService analysisService,
            MarketDataService marketDataService,
            ChartService chartService,
            ChartDataService chartDataService,
            MarketDataStorage storage,
            TradingRecommendationService recommendationService,
            FinalRecommendationService finalRecommendationService,
            WalkForwardService walkForwardService,
            PaperTradingService paperTradingService,
            PairExecutionService pairExecutionService,
            RiskPolicyService riskPolicyService,
            HistoricalReplayService historicalReplayService
    ) {
        this.analysisService = analysisService;
        this.marketDataService = marketDataService;
        this.chartService = chartService;
        this.chartDataService = chartDataService;
        this.storage = storage;
        this.recommendationService = recommendationService;
        this.finalRecommendationService = finalRecommendationService;
        this.walkForwardService = walkForwardService;
        this.paperTradingService = paperTradingService;
        this.pairExecutionService = pairExecutionService;
        this.riskPolicyService = riskPolicyService;
        this.historicalReplayService = historicalReplayService;
    }

    /**
     * POST /api/data/refresh — скачивает свечи IMOEX с MOEX и сохраняет локально.
     */
    @PostMapping("/data/refresh")
    public Map<String, Object> refreshData() throws IOException {
        List<String> tickers = marketDataService.refreshMarketData();
        return Map.of(
                "status", "ok",
                "tickersLoaded", tickers.size(),
                "tickers", tickers
        );
    }

    /**
     * POST /api/analysis/run — запускает полный анализ коинтеграции.
     *
     * @param refresh {@code true} — обновить данные с MOEX перед расчётом
     */
    @PostMapping("/analysis/run")
    public AnalysisSummary runAnalysis(
            @RequestParam(defaultValue = "true") boolean refresh
    ) throws IOException {
        AnalysisReport report = analysisService.runFullAnalysis(refresh);
        return AnalysisSummary.from(report, recommendationService.getLastRecommendations());
    }

    /**
     * GET /api/analysis/report — возвращает последний сохранённый отчёт анализа.
     */
    @GetMapping("/analysis/report")
    public AnalysisSummary latestReport() throws IOException {
        return storage.loadReport()
                .map(report -> AnalysisSummary.from(report, recommendationService.getLastRecommendations()))
                .orElseThrow(() -> new IllegalStateException("No analysis report found. Run POST /api/analysis/run first."));
    }

    /**
     * GET /api/analysis/recommendations — торговые рекомендации последнего прогона анализа.
     */
    @GetMapping("/analysis/recommendations")
    public List<RecommendationSummary> recommendations() {
        return recommendationService.getLastRecommendations().stream()
                .map(RecommendationSummary::from)
                .toList();
    }

    /**
     * GET /api/analysis/signals — только пары с сигналом входа LONG/SHORT.
     */
    @GetMapping("/analysis/signals")
    public List<RecommendationSummary> actionableSignals() {
        return recommendationService.getActionableSignals().stream()
                .map(RecommendationSummary::from)
                .toList();
    }

    /**
     * GET /api/analysis/final — итоговые рекомендации после новостного фильтра.
     */
    @GetMapping("/analysis/final")
    public List<FinalTradeRecommendation> finalRecommendations() {
        return finalRecommendationService.getLastFinal();
    }

    /**
     * POST /api/analysis/walk-forward — OOS walk-forward по топ-парам последнего отчёта.
     */
    @PostMapping("/analysis/walk-forward")
    public WalkForwardReport runWalkForward(
            @RequestParam(defaultValue = "10") int maxPairs
    ) throws IOException {
        return walkForwardService.runForTopPairs(maxPairs);
    }

    /**
     * GET /api/analysis/walk-forward — последний walk-forward отчёт.
     */
    @GetMapping("/analysis/walk-forward")
    public WalkForwardReport latestWalkForward() throws IOException {
        return walkForwardService.getLastReport()
                .or(() -> {
                    try {
                        return storage.loadWalkForwardReport();
                    } catch (IOException e) {
                        return Optional.empty();
                    }
                })
                .orElseThrow(() -> new IllegalStateException(
                        "No walk-forward report. Run POST /api/analysis/walk-forward or full analysis."));
    }

    /**
     * GET /api/paper/journal — paper track-record.
     */
    @GetMapping("/paper/journal")
    public PaperJournal paperJournal() {
        return paperTradingService.summary();
    }

    /**
     * GET /api/risk/policy — текущая risk policy.
     */
    @GetMapping("/risk/policy")
    public ImoexProperties.RiskProperties riskPolicy() {
        return riskPolicyService.policy();
    }

    /**
     * POST /api/analysis/historical-replay — bar-by-bar прогон paper на сохранённых свечах пары.
     */
    @PostMapping("/analysis/historical-replay")
    public HistoricalReplayReport historicalReplay(
            @RequestParam String tickerY,
            @RequestParam String tickerX,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "DAILY") BookKind book
    ) throws IOException {
        LocalDate end = to != null ? to : LocalDate.now().minusDays(1);
        LocalDate start = from != null ? from : end.minusYears(2);
        return historicalReplayService.replayFromStorage(
                tickerY.toUpperCase(),
                tickerX.toUpperCase(),
                start,
                end,
                book
        );
    }

    /**
     * POST /api/analysis/news-refresh — пересчитать новости по уже готовым техническим сигналам
     * (без полного Engle–Granger). Удобно для дневного обновления safety-layer.
     */
    @PostMapping("/analysis/news-refresh")
    public List<FinalTradeRecommendation> refreshNewsOnly() throws IOException {
        List<TradingRecommendation> technical = recommendationService.getLastRecommendations();
        if (technical.isEmpty()) {
            throw new IllegalStateException("No technical recommendations. Run POST /api/analysis/run first.");
        }
        List<FinalTradeRecommendation> finals = finalRecommendationService.reanalyzeExisting(technical);
        paperTradingService.sync(finals, technical);
        if (pairExecutionService.status().enabled() && pairExecutionService.status().autoExecuteAfterAnalysis()) {
            pairExecutionService.executeActionableDaily(finals);
        }
        return finals;
    }

    /**
     * GET /api/analysis/top-pairs — возвращает топ-N пар с метриками без полных временных рядов.
     */
    @GetMapping("/analysis/top-pairs")
    public List<PairSummary> topPairs() throws IOException {
        AnalysisReport report = storage.loadReport()
                .orElseThrow(() -> new IllegalStateException("No analysis report found. Run POST /api/analysis/run first."));
        return report.topPairs().stream().map(PairSummary::from).toList();
    }

    /**
     * GET /api/charts/{Y}/{X}/data — JSON для интерактивного графика (свечи, KAMA, маркеры).
     */
    @GetMapping("/charts/{tickerY}/{tickerX}/data")
    public ChartPayload chartData(
            @PathVariable String tickerY,
            @PathVariable String tickerX
    ) throws IOException {
        return chartDataService.build(tickerY.toUpperCase(), tickerX.toUpperCase());
    }

    /**
     * GET /api/charts/{Y}/{X}/spread — PNG-график спреда пары (также пересоздаёт Z-score).
     */
    @GetMapping("/charts/{tickerY}/{tickerX}/spread")
    public ResponseEntity<FileSystemResource> spreadChart(
            @PathVariable String tickerY,
            @PathVariable String tickerX
    ) throws IOException {
        Path chart = chartService.renderSpreadChart(tickerY.toUpperCase(), tickerX.toUpperCase());
        return pngResponse(chart);
    }

    /**
     * GET /api/charts/{Y}/{X}/zscore — PNG-график Z-score пары.
     */
    @GetMapping("/charts/{tickerY}/{tickerX}/zscore")
    public ResponseEntity<FileSystemResource> zScoreChart(
            @PathVariable String tickerY,
            @PathVariable String tickerX
    ) throws IOException {
        Path chart = chartService.renderZScoreChart(tickerY.toUpperCase(), tickerX.toUpperCase());
        return pngResponse(chart);
    }

    /** Формирует HTTP-ответ с PNG-картинкой для inline-отображения в браузере. */
    private ResponseEntity<FileSystemResource> pngResponse(Path chart) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + chart.getFileName() + "\"")
                .contentType(MediaType.IMAGE_PNG)
                .body(new FileSystemResource(chart));
    }

    /**
     * DTO отчёта анализа для REST-ответа (без тяжёлых временных рядов в каждой паре).
     *
     * @param analysisDate      дата расчёта
     * @param tickersAnalyzed   число акций
     * @param pairsTested       число протестированных пар
     * @param cointegratedPairs число коинтегрированных пар
     * @param topPairs          топ-пары с метриками и URL графиков
     */
    public record AnalysisSummary(
            String analysisDate,
            int tickersAnalyzed,
            int pairsTested,
            int cointegratedPairs,
            List<PairSummary> topPairs,
            int recommendationsCount,
            int actionableSignalsCount,
            List<RecommendationSummary> recommendations
    ) {
        static AnalysisSummary from(AnalysisReport report, List<TradingRecommendation> recommendations) {
            List<RecommendationSummary> recs = recommendations.stream().map(RecommendationSummary::from).toList();
            long actionable = recommendations.stream()
                    .filter(r -> r.signal() == TradingSignal.LONG_SPREAD || r.signal() == TradingSignal.SHORT_SPREAD)
                    .count();
            return new AnalysisSummary(
                    report.analysisDate().toString(),
                    report.tickersAnalyzed(),
                    report.pairsTested(),
                    report.cointegratedPairs(),
                    report.topPairs().stream().map(PairSummary::from).toList(),
                    recs.size(),
                    (int) actionable,
                    recs
            );
        }
    }

    /**
     * DTO торговой рекомендации для REST и JSON-ответа после анализа.
     */
    public record RecommendationSummary(
            String tickerY,
            String tickerX,
            String signal,
            double currentZScore,
            String asOfDate,
            double hedgeRatio,
            double halfLifeDays,
            double sharpeRatio,
            String summary,
            String details
    ) {
        static RecommendationSummary from(TradingRecommendation r) {
            return new RecommendationSummary(
                    r.tickerY(),
                    r.tickerX(),
                    r.signal().name(),
                    r.currentZScore(),
                    r.asOfDate().toString(),
                    r.hedgeRatio(),
                    r.halfLifeDays(),
                    r.sharpeRatio(),
                    r.summary(),
                    r.details()
            );
        }
    }

    /**
     * DTO одной пары для REST-ответа с ключевыми метриками и ссылками на графики.
     */
    public record PairSummary(
            String tickerY,
            String tickerX,
            double hedgeRatio,
            double intercept,
            double adfStatistic,
            double pValue,
            double sharpeRatio,
            double maxDrawdown,
            double halfLifeDays,
            int tradeCount,
            double totalReturn,
            String spreadChartUrl,
            String zScoreChartUrl
    ) {
        /** Преобразует {@link PairAnalysisResult} в компактный REST DTO. */
        static PairSummary from(PairAnalysisResult result) {
            return new PairSummary(
                    result.tickerY(),
                    result.tickerX(),
                    result.hedgeRatio(),
                    result.intercept(),
                    result.adfStatistic(),
                    result.pValue(),
                    result.sharpeRatio(),
                    result.maxDrawdown(),
                    result.halfLifeDays(),
                    result.tradeCount(),
                    result.totalReturn(),
                    "/view/charts/" + result.tickerY() + "/" + result.tickerX(),
                    "/view/charts/" + result.tickerY() + "/" + result.tickerX()
            );
        }
    }
}
