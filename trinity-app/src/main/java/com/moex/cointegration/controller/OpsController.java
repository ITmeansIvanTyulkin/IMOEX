package com.moex.cointegration.controller;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.AutoRunStatus;
import com.moex.cointegration.model.OperatorTradeToast;
import com.moex.cointegration.model.PaperTradeAlert;
import com.moex.cointegration.service.OperatorTradeToastService;
import com.moex.cointegration.service.PaperAlertService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Операторский API: статус автопрогонов и алерты paper / trade toasts.
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final PaperAlertService alertService;
    private final OperatorTradeToastService tradeToasts;
    private final ImoexProperties properties;

    public OpsController(
            PaperAlertService alertService,
            OperatorTradeToastService tradeToasts,
            ImoexProperties properties
    ) {
        this.alertService = alertService;
        this.tradeToasts = tradeToasts;
        this.properties = properties;
    }

    /**
     * GET /api/ops/auto-run/status — статус cron DAILY / INTRADAY.
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
}
