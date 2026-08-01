package com.moex.cointegration.controller;

import com.moex.cointegration.model.AnalysisReport;
import com.moex.cointegration.model.FinalTradeRecommendation;
import com.moex.cointegration.model.PaperJournal;
import com.moex.cointegration.model.TradingRecommendation;
import com.moex.cointegration.model.WalkForwardReport;
import com.moex.cointegration.service.FinalRecommendationService;
import com.moex.cointegration.service.MarketRegimeService;
import com.moex.cointegration.service.PaperTradingService;
import com.moex.cointegration.service.TradingRecommendationService;
import com.moex.cointegration.service.WalkForwardService;
import com.moex.cointegration.storage.MarketDataStorage;
import com.moex.cointegration.web.AnalysisHtmlRenderer;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * HTML-представления для просмотра результатов анализа в браузере.
 */
@RestController
@RequestMapping("/view")
public class AnalysisViewController {

    private final MarketDataStorage storage;
    private final TradingRecommendationService recommendationService;
    private final FinalRecommendationService finalRecommendationService;
    private final PaperTradingService paperTradingService;
    private final WalkForwardService walkForwardService;
    private final MarketRegimeService marketRegimeService;
    private final AnalysisHtmlRenderer htmlRenderer;

    public AnalysisViewController(
            MarketDataStorage storage,
            TradingRecommendationService recommendationService,
            FinalRecommendationService finalRecommendationService,
            PaperTradingService paperTradingService,
            WalkForwardService walkForwardService,
            MarketRegimeService marketRegimeService,
            AnalysisHtmlRenderer htmlRenderer
    ) {
        this.storage = storage;
        this.recommendationService = recommendationService;
        this.finalRecommendationService = finalRecommendationService;
        this.paperTradingService = paperTradingService;
        this.walkForwardService = walkForwardService;
        this.marketRegimeService = marketRegimeService;
        this.htmlRenderer = htmlRenderer;
    }

    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public String dashboard() throws IOException {
        Optional<AnalysisReport> report = storage.loadReport();
        if (report.isEmpty()) {
            return htmlRenderer.renderEmpty();
        }
        List<TradingRecommendation> recommendations = recommendationService.getLastRecommendations();
        return htmlRenderer.renderDashboard(report.get(), recommendations, marketRegimeService.current());
    }

    @GetMapping(value = "/recommendations", produces = MediaType.TEXT_HTML_VALUE)
    public String allRecommendations() throws IOException {
        if (storage.loadReport().isEmpty()) {
            return htmlRenderer.renderEmpty();
        }
        return htmlRenderer.renderAllRecommendations(recommendationService.getLastRecommendations());
    }

    @GetMapping(value = "/signals", produces = MediaType.TEXT_HTML_VALUE)
    public String signals() throws IOException {
        if (storage.loadReport().isEmpty()) {
            return htmlRenderer.renderEmpty();
        }
        return htmlRenderer.renderSignals(recommendationService.getActionableSignals());
    }

    @GetMapping(value = "/final", produces = MediaType.TEXT_HTML_VALUE)
    public String finalTable() throws IOException {
        if (storage.loadReport().isEmpty()) {
            return htmlRenderer.renderEmpty();
        }
        List<FinalTradeRecommendation> rows = finalRecommendationService.getLastFinal();
        return htmlRenderer.renderFinalTable(rows);
    }

    @GetMapping(value = "/paper", produces = MediaType.TEXT_HTML_VALUE)
    public String paperJournal() {
        PaperJournal journal = paperTradingService.summary();
        return htmlRenderer.renderPaperJournal(
                journal,
                recommendationService.getLastRecommendations(),
                recommendationService.getLastIntradayRecommendations()
        );
    }

    @GetMapping(value = "/walk-forward", produces = MediaType.TEXT_HTML_VALUE)
    public String walkForward() throws IOException {
        Optional<WalkForwardReport> report = walkForwardService.getLastReport()
                .or(() -> {
                    try {
                        return storage.loadWalkForwardReport();
                    } catch (IOException e) {
                        return Optional.empty();
                    }
                });
        return htmlRenderer.renderWalkForward(report.orElse(null));
    }

    @GetMapping(value = "/settings", produces = MediaType.TEXT_HTML_VALUE)
    public String settings() {
        return htmlRenderer.renderSettings();
    }

    @GetMapping(value = "/strategy", produces = MediaType.TEXT_HTML_VALUE)
    public String strategy() {
        return htmlRenderer.renderStrategy();
    }

    @GetMapping(value = "/guide", produces = MediaType.TEXT_HTML_VALUE)
    public String guide() {
        return htmlRenderer.renderGuide();
    }

    @GetMapping(value = "/charts/{tickerY}/{tickerX}", produces = MediaType.TEXT_HTML_VALUE)
    public String pairChart(
            @PathVariable String tickerY,
            @PathVariable String tickerX
    ) {
        return htmlRenderer.renderChartPage(tickerY.toUpperCase(), tickerX.toUpperCase());
    }
}
