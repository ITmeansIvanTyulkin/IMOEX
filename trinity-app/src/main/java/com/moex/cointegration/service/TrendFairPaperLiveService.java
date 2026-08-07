package com.moex.cointegration.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.trinity.trend.FairPaperSimulator;
import com.moex.trinity.trend.TrendAccountContext;
import com.moex.trinity.trend.TrendBar;
import com.moex.trinity.trend.TrendBarSeries;
import com.moex.trinity.trend.TrendPlaybookSettings;
import com.moex.trinity.trend.TrendRobotEngine;
import com.moex.trinity.trend.TrendRobotPlan;
import com.moex.trinity.trend.TrendRobotState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Live fair-paper loop: when auto-execution is on (and live broker off), manage fills/SL/TP
 * on new M5 bars like day-replay and append closed trades to {@code trend-paper-journal.json}
 * with tag {@code SANDBOX_FAIR} — never wipes MISSED_* / ROBOT_REPLAY.
 */
@Service
@ConditionalOnProperty(prefix = "imoex.strategies.trend", name = "enabled", havingValue = "true")
public class TrendFairPaperLiveService {

    public static final String TAG = "SANDBOX_FAIR";

    private static final Logger log = LoggerFactory.getLogger(TrendFairPaperLiveService.class);
    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");

    private final TrendDeskService desk;
    private final TrendRobotEngine engine;
    private final TrendExecutionBridge bridge;
    private final TrendSettingsService settingsService;
    private final TrendPaperJournalService paperJournal;
    private final TrendPlaybookSettings playbookSettings;
    private final Path stateFile;
    private final ObjectMapper mapper;
    private final double equityRub;
    private final double goLongRub;
    private final double goShortRub;

    private final AtomicReference<State> state = new AtomicReference<>(State.empty());
    private volatile LocalDateTime lastProcessedBar;
    private volatile Map<String, Object> lastCloseDto = Map.of();

    public TrendFairPaperLiveService(
            TrendDeskService desk,
            TrendRobotEngine engine,
            TrendExecutionBridge bridge,
            TrendSettingsService settingsService,
            TrendPaperJournalService paperJournal,
            TrendPlaybookSettings playbookSettings,
            ImoexProperties imoexProperties,
            @org.springframework.beans.factory.annotation.Value("${imoex.capital.equity-rub:100000}") double equityRub,
            @org.springframework.beans.factory.annotation.Value("${imoex.strategies.trend.desk-go-long-rub:15000}") double goLongRub,
            @org.springframework.beans.factory.annotation.Value("${imoex.strategies.trend.desk-go-short-rub:16000}") double goShortRub
    ) {
        this.desk = desk;
        this.engine = engine;
        this.bridge = bridge;
        this.settingsService = settingsService;
        this.paperJournal = paperJournal;
        this.playbookSettings = playbookSettings == null ? TrendPlaybookSettings.brDefaults() : playbookSettings;
        this.stateFile = Path.of(imoexProperties.dataDir(), "trend-fair-paper-state.json");
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.equityRub = equityRub > 0 ? equityRub : 100_000;
        this.goLongRub = goLongRub > 0 ? goLongRub : 15_000;
        this.goShortRub = goShortRub > 0 ? goShortRub : 16_000;
        loadState();
    }

    /** Desk / situation fragment. */
    public Map<String, Object> snapshot() {
        State s = state.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tag", TAG);
        m.put("enabled", settingsService.autoExecution() && !settingsService.liveExecution());
        m.put("lastProcessedBar", lastProcessedBar == null ? null : lastProcessedBar.toString());
        m.put("pending", s.pendingSummary());
        m.put("open", s.openSummary());
        m.put("lastClose", lastCloseDto == null || lastCloseDto.isEmpty() ? null : lastCloseDto);
        return m;
    }

    @Scheduled(fixedDelay = 5000L, initialDelay = 8000L)
    public void tick() {
        if (!settingsService.autoExecution() || settingsService.liveExecution()) {
            return;
        }
        try {
            tickOnce();
        } catch (Exception ex) {
            log.warn("Fair-paper live tick failed: {}", ex.toString());
        }
    }

    synchronized void tickOnce() {
        String instrument = desk.resolveInstrument();
        List<TrendBar> bars = desk.loadBarsPublic(instrument);
        if (bars == null || bars.size() < 10) {
            return;
        }
        TrendBar bar = bars.get(bars.size() - 1);
        if (bar == null || bar.time() == null) {
            return;
        }
        if (lastProcessedBar != null && !bar.time().isAfter(lastProcessedBar)) {
            return;
        }

        double point = playbookSettings.instrument().pointSize();
        double rub = playbookSettings.instrument().rubPerPoint();
        State s = state.get();
        FairPaperSimulator.OpenPaper open = s.open;
        TrendRobotPlan pending = s.pendingPlan;

        // 1) Manage open
        if (open != null) {
            FairPaperSimulator.ExitResult er = FairPaperSimulator.manage(open, bar, rub, point);
            if (er != null) {
                recordClose(open, bar, er);
                engine.registerRealizedPnl(er.pnl());
                if ("SL".equals(er.reason())) {
                    engine.registerStopLoss(bar.time());
                } else {
                    engine.registerFlatWin(bar.time());
                }
                open = null;
            }
        }

        // 2) Fill pending
        if (open == null && pending != null && pending.grid() != null) {
            if (pending.range() != null) {
                double unlockPts = playbookSettings.unlockDistancePoints() > 0
                        ? playbookSettings.unlockDistancePoints() : 40;
                double dist = Math.abs(bar.close() - pending.range().mid()) / point;
                if (dist >= unlockPts) {
                    pending = null;
                    engine.clearSetupLock();
                    log.info("Fair-paper cancel pending (unlock {} pts)", Math.round(dist));
                }
            }
            if (pending != null) {
                FairPaperSimulator.OpenPaper filled = FairPaperSimulator.tryOpen(pending, bar);
                if (filled != null) {
                    open = filled;
                    pending = null;
                    engine.registerFill(bar.time());
                    log.info("Fair-paper FILL {} {} avg={} qty={}",
                            open.buy ? "BUY" : "SELL", open.mode, open.avg, open.qty);
                }
            }
        }

        // 3) Evaluate / arm (only when flat and no pending)
        TrendBarSeries series = new TrendBarSeries(instrument, "M5", bars);
        TrendAccountContext account = new TrendAccountContext(equityRub, goLongRub, goShortRub, 1.0);
        Optional<TrendRobotPlan> opt = engine.evaluate(series, account);
        TrendRobotPlan plan = opt.orElse(null);
        if (open == null && pending == null && plan != null && plan.actionable()
                && plan.state() != null
                && (plan.state() == TrendRobotState.ARMED_BOUNCE || plan.state() == TrendRobotState.ARMED_RETEST)) {
            pending = plan;
            bridge.submit(plan);
            log.info("Fair-paper ARM {} {} {}",
                    plan.buy() ? "BUY" : "SELL",
                    plan.mode() == null ? "?" : plan.mode().name(),
                    plan.instrument());
        } else if (open == null && pending != null && plan != null && plan.state() == TrendRobotState.NO_TRADE
                && plan.rationale() != null
                && (plan.rationale().contains("cooldown")
                || plan.rationale().contains("session edge")
                || plan.rationale().contains("event edge")
                || plan.rationale().contains("max day loss")
                || plan.rationale().contains("max fills/day"))) {
            pending = null;
        }

        State next = new State(pending, open);
        state.set(next);
        lastProcessedBar = bar.time();
        saveState(next);
    }

    private void recordClose(FairPaperSimulator.OpenPaper open, TrendBar bar, FairPaperSimulator.ExitResult er) {
        String opened = open.entryTime.toString();
        String id = String.format(Locale.ROOT, "sandbox-fair-%s-%s",
                open.instrument == null ? "BR" : open.instrument,
                opened.replace(':', '-'));
        double exitPx = Double.isFinite(er.exitPrice()) ? er.exitPrice() : bar.close();
        TrendPaperJournalService.Trade trade = new TrendPaperJournalService.Trade(
                id,
                open.instrument == null ? desk.resolveInstrument() : open.instrument,
                open.buy ? "BUY" : "SELL",
                open.mode,
                TAG,
                open.qty,
                open.avg,
                exitPx,
                open.sl,
                open.tp1,
                open.tp2,
                opened.contains("+") ? opened : opened + "+03:00",
                bar.time().toString() + "+03:00",
                er.reason(),
                Math.round(er.pnl() * 100.0) / 100.0,
                "Fair-paper live (M5). Append-only; no broker orders."
        );
        paperJournal.record(trade);
        Map<String, Object> close = new LinkedHashMap<>();
        close.put("id", id);
        close.put("side", trade.side());
        close.put("mode", trade.mode());
        close.put("pnlRub", trade.pnlRub());
        close.put("exitReason", trade.exitReason());
        close.put("closedAt", trade.closedAt());
        lastCloseDto = close;
        log.info("Fair-paper CLOSE {} {} pnl={}+ ₽ → statement {}",
                trade.side(), er.reason(), trade.pnlRub(), id);
    }

    private void loadState() {
        try {
            if (!Files.isRegularFile(stateFile)) {
                return;
            }
            Persisted p = mapper.readValue(stateFile.toFile(), Persisted.class);
            if (p == null) {
                return;
            }
            lastProcessedBar = p.lastProcessedBar;
            lastCloseDto = p.lastClose == null ? Map.of() : p.lastClose;
            // pending plan not restored (re-arm on next signal); open position is restored
            state.set(new State(null, p.open));
            if (p.open != null) {
                log.info("Fair-paper restored open {} avg={} qty={}",
                        p.open.buy ? "BUY" : "SELL", p.open.avg, p.open.qty);
            }
        } catch (Exception ex) {
            log.warn("Could not load fair-paper state {}: {}", stateFile, ex.getMessage());
        }
    }

    private void saveState(State s) {
        try {
            Files.createDirectories(stateFile.getParent());
            Persisted p = new Persisted(
                    LocalDateTime.now(MSK),
                    lastProcessedBar,
                    s.open,
                    lastCloseDto
            );
            mapper.writerWithDefaultPrettyPrinter().writeValue(stateFile.toFile(), p);
        } catch (Exception ex) {
            log.warn("Could not save fair-paper state {}: {}", stateFile, ex.getMessage());
        }
    }

    private static final class State {
        final TrendRobotPlan pendingPlan;
        final FairPaperSimulator.OpenPaper open;

        State(TrendRobotPlan pendingPlan, FairPaperSimulator.OpenPaper open) {
            this.pendingPlan = pendingPlan;
            this.open = open;
        }

        static State empty() {
            return new State(null, null);
        }

        Map<String, Object> pendingSummary() {
            if (pendingPlan == null) {
                return null;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("side", pendingPlan.buy() ? "BUY" : "SELL");
            m.put("mode", pendingPlan.mode() == null ? null : pendingPlan.mode().name());
            m.put("instrument", pendingPlan.instrument());
            if (pendingPlan.grid() != null) {
                m.put("qty", pendingPlan.grid().totalQty());
                m.put("avg", pendingPlan.grid().averagePrice());
            }
            return m;
        }

        Map<String, Object> openSummary() {
            if (open == null) {
                return null;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("side", open.buy ? "BUY" : "SELL");
            m.put("mode", open.mode);
            m.put("avg", open.avg);
            m.put("qty", open.qty);
            m.put("sl", open.sl);
            m.put("tp1", open.tp1);
            m.put("tp2", open.tp2);
            m.put("tp1Done", open.tp1Done);
            m.put("entryTime", open.entryTime == null ? null : open.entryTime.toString());
            return m;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Persisted(
            LocalDateTime savedAt,
            LocalDateTime lastProcessedBar,
            FairPaperSimulator.OpenPaper open,
            Map<String, Object> lastClose
    ) {
    }
}
