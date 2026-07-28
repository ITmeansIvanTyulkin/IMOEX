package com.moex.cointegration.controller;

import com.moex.cointegration.model.BookKind;
import com.moex.cointegration.model.BrokerExecutionReport;
import com.moex.cointegration.model.BrokerReconcileReport;
import com.moex.cointegration.model.BrokerSettingsUpdateRequest;
import com.moex.cointegration.model.BrokerSettingsView;
import com.moex.cointegration.model.BrokerStatus;
import com.moex.cointegration.model.FinalTradeRecommendation;
import com.moex.cointegration.service.BrokerReconcileService;
import com.moex.cointegration.service.BrokerSettingsService;
import com.moex.cointegration.service.FinalRecommendationService;
import com.moex.cointegration.service.PairExecutionService;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Broker / execution API: preview и submit парных ордеров поверх итоговых DAILY recommendations.
 */
@RestController
@RequestMapping("/api/broker")
public class BrokerController {

    private final PairExecutionService pairExecutionService;
    private final FinalRecommendationService finalRecommendationService;
    private final BrokerReconcileService brokerReconcileService;
    private final BrokerSettingsService brokerSettingsService;

    public BrokerController(
            PairExecutionService pairExecutionService,
            FinalRecommendationService finalRecommendationService,
            BrokerReconcileService brokerReconcileService,
            BrokerSettingsService brokerSettingsService
    ) {
        this.pairExecutionService = pairExecutionService;
        this.finalRecommendationService = finalRecommendationService;
        this.brokerReconcileService = brokerReconcileService;
        this.brokerSettingsService = brokerSettingsService;
    }

    @GetMapping("/status")
    public BrokerStatus status() {
        return pairExecutionService.status();
    }

    @GetMapping("/settings")
    public BrokerSettingsView settings() {
        return brokerSettingsService.view();
    }

    @PostMapping("/settings")
    public BrokerSettingsView saveSettings(@RequestBody BrokerSettingsUpdateRequest request) {
        return brokerSettingsService.save(request);
    }

    @GetMapping("/reports")
    public List<BrokerExecutionReport> reports() {
        return pairExecutionService.recentReports();
    }

    @GetMapping("/reconcile")
    public BrokerReconcileReport reconcile() {
        return brokerReconcileService.reconcileDaily();
    }

    @PostMapping("/flatten-all")
    public BrokerExecutionReport flattenAll() {
        return pairExecutionService.flattenAll();
    }

    @PostMapping("/preview")
    public BrokerExecutionReport preview(
            @RequestParam String tickerY,
            @RequestParam String tickerX,
            @RequestParam(defaultValue = "DAILY") BookKind book
    ) {
        FinalTradeRecommendation finalRec = findFinal(tickerY, tickerX, book);
        return pairExecutionService.preview(finalRec, book);
    }

    @PostMapping("/execute")
    public BrokerExecutionReport execute(
            @RequestParam String tickerY,
            @RequestParam String tickerX,
            @RequestParam(defaultValue = "DAILY") BookKind book,
            @RequestParam(defaultValue = "false") boolean confirm
    ) {
        FinalTradeRecommendation finalRec = findFinal(tickerY, tickerX, book);
        return pairExecutionService.execute(finalRec, book, !confirm);
    }

    @PostMapping("/execute-actionable")
    public List<BrokerExecutionReport> executeActionableDaily() {
        return pairExecutionService.executeActionableDaily(finalRecommendationService.getLastFinal());
    }

    private FinalTradeRecommendation findFinal(String tickerY, String tickerX, BookKind book) {
        List<FinalTradeRecommendation> finals = book == BookKind.INTRADAY
                ? finalRecommendationService.getLastIntradayFinal()
                : finalRecommendationService.getLastFinal();
        return finals.stream()
                .filter(f -> f.tickerY().equalsIgnoreCase(tickerY))
                .filter(f -> f.tickerX().equalsIgnoreCase(tickerX))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Final recommendation not found for " + tickerY + "/" + tickerX + " (" + book + ")"));
    }
}
