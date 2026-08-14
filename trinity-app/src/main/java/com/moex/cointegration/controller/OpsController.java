package com.moex.cointegration.controller;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.AutoRunStatus;
import com.moex.cointegration.model.OperatorTradeToast;
import com.moex.cointegration.model.PaperTradeAlert;
import com.moex.cointegration.product.ProductEdition;
import com.moex.cointegration.product.ProductEditionService;
import com.moex.cointegration.service.OperatorTradeToastService;
import com.moex.cointegration.service.PaperAlertService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Операторский API: статус автопрогонов и алерты paper / trade toasts.
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final PaperAlertService alertService;
    private final OperatorTradeToastService tradeToasts;
    private final ImoexProperties properties;
    private final ProductEditionService productEdition;

    public OpsController(
            PaperAlertService alertService,
            OperatorTradeToastService tradeToasts,
            ImoexProperties properties,
            ProductEditionService productEdition
    ) {
        this.alertService = alertService;
        this.tradeToasts = tradeToasts;
        this.properties = properties;
        this.productEdition = productEdition;
    }

    /**
     * GET /api/ops/auto-run/status — статус cron DAILY (INTRADAY fields kept for API, cron off / UX ignores).
     */
    @GetMapping("/auto-run/status")
    public AutoRunStatus autoRunStatus() {
        var paper = properties.paper();
        return alertService.status(
                paper.autoRunIntradayEnabled(),
                paper.autoRunDailyEnabled(),
                paper.intradayCron(),
                paper.dailyCron()
        );
    }

    /**
     * GET /api/ops/paper-alerts — paper OPEN/CLOSE за последние 24ч (клиент дедуплицирует по id).
     */
    @GetMapping("/paper-alerts")
    public List<PaperTradeAlert> paperAlerts() {
        return alertService.recentAlerts();
    }

    /**
     * GET /api/ops/trade-toasts — pairs + trend toasts (OPEN/CLOSE/ENTRY/SIGNAL).
     */
    @GetMapping("/trade-toasts")
    public List<OperatorTradeToast> tradeToasts() {
        return tradeToasts.recentToasts();
    }

    /**
     * GET /api/ops/product-edition — simulated SKU + strategy unlock flags.
     */
    @GetMapping("/product-edition")
    public Map<String, Object> productEdition() {
        return productEdition.dto();
    }

    /**
     * POST /api/ops/product-edition — runtime override for demo (in-memory).
     * Body: {@code {"edition":"PAIRS"|"PAIRS_TREND"|"FULL"}} or {@code {"clear":true}}.
     */
    @PostMapping("/product-edition")
    public Map<String, Object> setProductEdition(@RequestBody(required = false) Map<String, Object> body) {
        if (body != null && Boolean.TRUE.equals(body.get("clear"))) {
            productEdition.clearOverride();
            return productEdition.dto();
        }
        Object raw = body != null ? body.get("edition") : null;
        ProductEdition next = ProductEdition.parse(raw == null ? null : String.valueOf(raw));
        productEdition.setOverride(next);
        Map<String, Object> out = new LinkedHashMap<>(productEdition.dto());
        out.put("applied", next.name());
        return out;
    }

    /**
     * GET /api/ops/strategy-lock?strategy=TREND|ARB — copy for lock modal.
     */
    @GetMapping("/strategy-lock")
    public Map<String, Object> strategyLock(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "TREND") String strategy
    ) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("strategy", strategy);
        m.put("locked", switch (strategy == null ? "" : strategy.trim().toUpperCase()) {
            case "TREND" -> !productEdition.hasTrend();
            case "ARB", "CALENDAR_ARB", "CALENDAR-ARB" -> !productEdition.hasArb();
            default -> true;
        });
        m.put("title", productEdition.lockTitle(strategy));
        m.put("body", productEdition.lockBody(strategy));
        m.put("ctaHref", productEdition.lockCtaHref(strategy));
        m.put("ctaLabel", productEdition.lockCtaLabel(strategy));
        m.putAll(productEdition.dto());
        return m;
    }
}
