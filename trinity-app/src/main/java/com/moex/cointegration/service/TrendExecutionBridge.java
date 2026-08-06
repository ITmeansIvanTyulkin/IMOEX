package com.moex.cointegration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.trinity.trend.TrendRobotEngine;
import com.moex.trinity.trend.TrendRobotPlan;
import com.moex.trinity.trend.TrendRobotState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Sandbox-first execution bridge for trend robot plans.
 * Journals intended FORTS limits/SL/TP; live T-Invest single-leg submit only when
 * {@code imoex.strategies.trend.live-execution=true} (still gated — BrokerClient is pairs-oriented).
 */
@Service
@ConditionalOnProperty(prefix = "imoex.strategies.trend", name = "enabled", havingValue = "true")
public class TrendExecutionBridge {

    private static final Logger log = LoggerFactory.getLogger(TrendExecutionBridge.class);

    private final Path journalFile;
    private final ObjectMapper mapper;
    private final ReentrantLock lock = new ReentrantLock();
    private final TrendRobotEngine engine;
    private final TrendSettingsService settings;
    private final OperatorTradeToastService tradeToasts;
    private JournalFile data = JournalFile.empty();

    public TrendExecutionBridge(
            ImoexProperties imoexProperties,
            TrendSettingsService settings,
            @Autowired(required = false) TrendRobotEngine engine,
            @Autowired(required = false) OperatorTradeToastService tradeToasts
    ) {
        this.journalFile = Path.of(imoexProperties.dataDir(), "trend-robot-journal.json");
        this.settings = settings;
        this.engine = engine;
        this.tradeToasts = tradeToasts;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        load();
    }

    public boolean autoExecution() {
        return settings.autoExecution();
    }

    public boolean liveExecution() {
        return settings.liveExecution();
    }

    public synchronized JournalFile journal() {
        return data;
    }

    public Optional<TrendRobotPlan> lastPlan() {
        return data.entries().isEmpty()
                ? Optional.empty()
                : Optional.ofNullable(data.entries().get(data.entries().size() - 1).plan());
    }

    /**
     * Accept an actionable plan into sandbox journal (and mark engine WORKING_ORDERS).
     * Blocked when {@code auto-execution=false} (signal-only mode).
     * Live broker submit is refused until single-instrument FORTS API exists — plan is still journaled.
     */
    public BridgeResult submit(TrendRobotPlan plan) {
        if (plan == null) {
            return BridgeResult.rejected("null plan");
        }
        if (!autoExecution()) {
            return BridgeResult.rejected(
                    "auto-execution=false (signal-only): use ticker+side from evaluate/signal; enable in Настройки → Trend playbook");
        }
        if (!plan.actionable()) {
            return BridgeResult.rejected("plan not actionable: " + plan.state() + " — " + plan.rationale());
        }

        String channel = liveExecution() ? "LIVE_GATED" : "SANDBOX_JOURNAL";
        List<String> messages = new ArrayList<>();
        messages.add("Journaled " + plan.grid().totalQty() + " contracts on " + plan.instrument());
        if (liveExecution()) {
            messages.add("live-execution=true but BrokerClient is pairs-only — FORTS single-leg submit deferred; journaled only");
        } else {
            messages.add("sandbox/paper journal — no live FORTS orders");
        }

        JournalEntry entry = new JournalEntry(
                LocalDateTime.now(),
                channel,
                plan.state() == null ? TrendRobotState.WORKING_ORDERS.name() : TrendRobotState.WORKING_ORDERS.name(),
                plan,
                messages
        );
        lock.lock();
        try {
            List<JournalEntry> next = new ArrayList<>(data.entries());
            next.add(entry);
            data = new JournalFile(LocalDateTime.now(), next);
            save();
        } finally {
            lock.unlock();
        }
        if (engine != null) {
            engine.markWorking();
        }
        if (tradeToasts != null) {
            tradeToasts.recordTrendEntry(plan);
        }
        log.info("Trend robot plan journaled ({}) playbook={} qty={}",
                channel, plan.playbookId(), plan.grid().totalQty());
        return new BridgeResult(true, channel, messages, plan);
    }

    private void load() {
        try {
            if (Files.isRegularFile(journalFile)) {
                data = mapper.readValue(journalFile.toFile(), JournalFile.class);
                if (data == null) {
                    data = JournalFile.empty();
                }
            }
        } catch (Exception ex) {
            log.warn("Could not load trend robot journal {}: {}", journalFile, ex.getMessage());
            data = JournalFile.empty();
        }
    }

    private void save() {
        try {
            Files.createDirectories(journalFile.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(journalFile.toFile(), data);
        } catch (Exception ex) {
            log.warn("Could not save trend robot journal {}: {}", journalFile, ex.getMessage());
        }
    }

    public record BridgeResult(boolean accepted, String channel, List<String> messages, TrendRobotPlan plan) {
        static BridgeResult rejected(String reason) {
            return new BridgeResult(false, "REJECTED", List.of(reason), null);
        }
    }

    public record JournalEntry(
            LocalDateTime at,
            String channel,
            String robotState,
            TrendRobotPlan plan,
            List<String> messages
    ) {
    }

    public record JournalFile(LocalDateTime updatedAt, List<JournalEntry> entries) {
        public JournalFile {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }

        static JournalFile empty() {
            return new JournalFile(LocalDateTime.now(), List.of());
        }
    }
}
