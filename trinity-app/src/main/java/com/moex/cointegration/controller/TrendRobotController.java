package com.moex.cointegration.controller;

import com.moex.cointegration.service.OperatorTradeToastService;
import com.moex.cointegration.service.TrendDeskService;
import com.moex.cointegration.service.TrendExecutionBridge;
import com.moex.cointegration.service.TrendPaperJournalService;
import com.moex.cointegration.service.TrendSettingsService;
import com.moex.trinity.trend.LimitGridPlan;
import com.moex.trinity.trend.MergedVolumeRange;
import com.moex.trinity.trend.TrendAccountContext;
import com.moex.trinity.trend.TrendBar;
import com.moex.trinity.trend.TrendBarSeries;
import com.moex.trinity.trend.TrendRegimeContext;
import com.moex.trinity.trend.TrendResearchService;
import com.moex.trinity.trend.TrendRobotEngine;
import com.moex.trinity.trend.TrendRobotPlan;
import com.moex.trinity.trend.TrendSignal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Trend robot API — evaluate playbook + sandbox journal submit.
 */
@RestController
@RequestMapping("/api/trend")
@ConditionalOnProperty(prefix = "imoex.strategies.trend", name = "enabled", havingValue = "true")
public class TrendRobotController {

    private final TrendResearchService researchService;
    private final TrendRobotEngine engine;
    private final TrendExecutionBridge bridge;
    private final TrendSettingsService trendSettings;
    private final OperatorTradeToastService tradeToasts;
    private final TrendDeskService deskService;
    private final TrendPaperJournalService paperJournal;

    public TrendRobotController(
            TrendResearchService researchService,
            TrendRobotEngine engine,
            TrendExecutionBridge bridge,
            TrendSettingsService trendSettings,
            OperatorTradeToastService tradeToasts,
            TrendDeskService deskService,
            TrendPaperJournalService paperJournal
    ) {
        this.researchService = researchService;
        this.engine = engine;
        this.bridge = bridge;
        this.trendSettings = trendSettings;
        this.tradeToasts = tradeToasts;
        this.deskService = deskService;
        this.paperJournal = paperJournal;
    }

    @GetMapping("/settings")
    public TrendSettingsService.View settings() {
        return trendSettings.view();
    }

    @PostMapping("/settings")
    public TrendSettingsService.View saveSettings(@RequestBody TrendSettingsService.UpdateRequest request) {
        return trendSettings.save(request);
    }

    /** One-click toggle: signal-only ↔ auto-execution (sandbox journal). */
    @PostMapping("/settings/auto-execution")
    public TrendSettingsService.View toggleAutoExecution(@RequestBody(required = false) ToggleBody body) {
        boolean next = body != null && body.enabled() != null
                ? body.enabled()
                : !trendSettings.autoExecution();
        return trendSettings.setAutoExecution(next);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", true);
        m.put("delivery", bridge.liveExecution() ? "LIVE_GATED"
                : (bridge.autoExecution() ? "SANDBOX_FAIR" : "SIGNAL_ONLY"));
        m.put("autoExecution", bridge.autoExecution());
        m.put("liveExecution", bridge.liveExecution());
        m.put("engineState", engine.state().name());
        m.put("kickCountToday", engine.kickCountToday());
        m.put("playbooks", researchService.playbooks().stream().map(p -> Map.of(
                "id", p.id(),
                "name", p.displayName(),
                "when", p.whenApplicable()
        )).toList());
        m.put("journalEntries", bridge.journal().entries().size());
        bridge.lastPlan().ifPresent(p -> m.put("lastJournalPlan", summarize(p, true)));
        engine.lastPlan().ifPresent(p -> {
            m.put("lastEnginePlan", summarize(p, bridge.autoExecution()));
            m.put("signal", TrendSignal.from(p));
        });
        return m;
    }

    /**
     * Operator signal desk: M5 bars (tape + ISS warmup), DOM, full plan markers.
     */
    @GetMapping("/desk")
    public Map<String, Object> desk() {
        return deskService.desk();
    }

    /**
     * Kick stuck robot: soft = clear arm/spent/cooldown; hard (default) = also wipe day shelves
     * and re-discover levels. Does not touch paper statement.
     */
    @PostMapping("/kick")
    public Map<String, Object> kick(
            @RequestParam(defaultValue = "hard") String mode,
            @RequestParam(required = false) String reason
    ) {
        Map<String, Object> result = "soft".equalsIgnoreCase(mode)
                ? engine.kickSoft(reason == null ? "api-soft" : reason)
                : engine.kickAwake(reason == null ? "api-hard" : reason);
        // Re-evaluate desk immediately so UI sees fresh plan
        Map<String, Object> desk = deskService.desk();
        result.put("deskState", desk.get("plan") instanceof Map<?, ?> p ? p.get("state") : null);
        result.put("deskSummary", desk.get("summary"));
        result.put("actionable", desk.get("actionable"));
        return result;
    }

    /**
     * Compact operator signal: ticker + BUY/SELL (+ mode). Always available; no orders.
     */
    @GetMapping("/signal")
    public ResponseEntity<?> signal() {
        return engine.lastPlan()
                .<ResponseEntity<?>>map(p -> ResponseEntity.ok(TrendSignal.from(p)))
                .orElseGet(() -> ResponseEntity.ok(Map.of(
                        "ticker", null,
                        "side", "NONE",
                        "summary", "no evaluated plan — POST /api/trend/evaluate first"
                )));
    }

    @PostMapping("/evaluate")
    public ResponseEntity<?> evaluate(
            @RequestBody EvaluateRequest req,
            @RequestParam(name = "full", defaultValue = "false") boolean full
    ) {
        if (req == null || req.bars() == null || req.bars().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "bars required"));
        }
        List<TrendBar> bars = new ArrayList<>();
        for (BarDto b : req.bars()) {
            bars.add(new TrendBar(
                    b.time() == null ? LocalDateTime.now() : b.time(),
                    b.open(), b.high(), b.low(), b.close(), b.volume()
            ));
        }
        TrendBarSeries series = new TrendBarSeries(
                req.instrument() == null ? "BR" : req.instrument(),
                req.timeframe() == null ? "M5" : req.timeframe(),
                bars
        );
        TrendAccountContext account = new TrendAccountContext(
                req.equityRub() > 0 ? req.equityRub() : 100_000,
                req.goLongRub() > 0 ? req.goLongRub() : 15_000,
                req.goShortRub() > 0 ? req.goShortRub() : 16_000,
                req.maxRiskPct() > 0 ? req.maxRiskPct() : 1.0
        );
        TrendRegimeContext regime = new TrendRegimeContext(
                req.regime() == null ? "TREND" : req.regime(),
                req.adx(),
                true
        );
        Optional<TrendRobotPlan> plan = engine.evaluate(series, account);
        if (plan.isEmpty()) {
            plan = researchService.evaluate(regime, series, account);
        }
        if (plan.isEmpty()) {
            return ResponseEntity.ok(Map.of("state", "NO_TRADE", "rationale", "no plan",
                    "signal", TrendSignal.from(null)));
        }
        TrendRobotPlan p = plan.get();
        if (!bridge.autoExecution() && p.actionable() && tradeToasts != null) {
            tradeToasts.recordTrendSignal(p);
        }
        boolean includeFull = full || bridge.autoExecution();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("signal", TrendSignal.from(p));
        body.put("delivery", bridge.liveExecution() ? "LIVE_GATED"
                : (bridge.autoExecution() ? "SANDBOX_FAIR" : "SIGNAL_ONLY"));
        if (includeFull) {
            body.putAll(summarize(p, true));
        } else {
            // signal-only: ticker + side (+ light state), no grid/qty
            body.put("playbookId", p.playbookId());
            body.put("ticker", p.instrument());
            body.put("side", TrendSignal.from(p).side());
            body.put("mode", p.mode() == null ? null : p.mode().name());
            body.put("state", p.state() == null ? null : p.state().name());
            body.put("actionable", p.actionable());
            body.put("summary", TrendSignal.from(p).summary());
        }
        return ResponseEntity.ok(body);
    }

    @PostMapping("/submit")
    public ResponseEntity<?> submit(@RequestBody(required = false) SubmitRequest req) {
        Optional<TrendRobotPlan> plan = engine.lastPlan();
        if (plan.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "no engine plan — POST /api/trend/evaluate first"));
        }
        TrendExecutionBridge.BridgeResult result = bridge.submit(plan.get());
        return ResponseEntity.ok(Map.of(
                "accepted", result.accepted(),
                "channel", result.channel(),
                "messages", result.messages(),
                "signal", TrendSignal.from(plan.get()),
                "plan", result.plan() == null ? Map.of() : summarize(result.plan(), true)
        ));
    }

    @GetMapping("/journal")
    public TrendExecutionBridge.JournalFile journal() {
        return bridge.journal();
    }

    /** Closed paper trades + statement (research PnL track-record). */
    @GetMapping("/paper")
    public Map<String, Object> paper() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("statement", paperJournal.statement());
        m.put("trades", paperJournal.journal().trades());
        return m;
    }

    @PostMapping("/paper/reload")
    public Map<String, Object> paperReload() {
        paperJournal.reload();
        return paper();
    }

    @PostMapping("/paper/trade")
    public ResponseEntity<?> recordPaperTrade(@RequestBody TrendPaperJournalService.Trade trade) {
        if (trade == null || trade.id() == null || trade.id().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "id required"));
        }
        return ResponseEntity.ok(Map.of(
                "trade", paperJournal.record(trade),
                "statement", paperJournal.statement()
        ));
    }

    private static Map<String, Object> summarize(TrendRobotPlan p, boolean full) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("playbookId", p.playbookId());
        m.put("instrument", p.instrument());
        m.put("ticker", p.instrument());
        m.put("timeframe", p.timeframe());
        m.put("state", p.state() == null ? null : p.state().name());
        m.put("mode", p.mode() == null ? null : p.mode().name());
        m.put("side", p.actionable() ? (p.buy() ? "BUY" : "SELL") : "NONE");
        m.put("buy", p.buy());
        m.put("rationale", p.rationale());
        m.put("actionable", p.actionable());
        m.put("signal", TrendSignal.from(p));
        if (!full) {
            return m;
        }
        m.put("stopLoss", p.stopLossPrice());
        m.put("tp1", p.tp1Price());
        m.put("tp2", p.tp2Price());
        m.put("tp1Fraction", p.tp1Fraction());
        MergedVolumeRange r = p.range();
        if (r != null) {
            m.put("range", Map.of(
                    "low", r.low(),
                    "high", r.high(),
                    "valid", r.validForEntry(),
                    "reason", r.invalidReason() == null ? "" : r.invalidReason()
            ));
        }
        LimitGridPlan g = p.grid();
        if (g != null) {
            m.put("grid", Map.of(
                    "style", g.style().name(),
                    "near", Map.of("price", g.nearPrice(), "qty", g.nearQty()),
                    "mid", Map.of("price", g.midPrice(), "qty", g.midQty()),
                    "far", Map.of("price", g.farPrice(), "qty", g.farQty()),
                    "totalQty", g.totalQty(),
                    "avg", g.averagePrice()
            ));
        }
        m.put("notes", p.notes());
        return m;
    }

    public record BarDto(LocalDateTime time, double open, double high, double low, double close, double volume) {
    }

    public record EvaluateRequest(
            String instrument,
            String timeframe,
            String regime,
            double adx,
            double equityRub,
            double goLongRub,
            double goShortRub,
            double maxRiskPct,
            List<BarDto> bars
    ) {
    }

    public record SubmitRequest(boolean confirm) {
    }

    public record ToggleBody(Boolean enabled) {
    }
}
