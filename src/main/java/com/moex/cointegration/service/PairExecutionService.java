package com.moex.cointegration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.BookKind;
import com.moex.cointegration.model.BrokerExecutionReport;
import com.moex.cointegration.model.BrokerExecutionStatus;
import com.moex.cointegration.model.BrokerMode;
import com.moex.cointegration.model.BrokerOrderIntent;
import com.moex.cointegration.model.BrokerOrderSide;
import com.moex.cointegration.model.BrokerOrderType;
import com.moex.cointegration.model.BrokerStatus;
import com.moex.cointegration.model.FinalTradeDecision;
import com.moex.cointegration.model.FinalTradeRecommendation;
import com.moex.cointegration.model.PairExecutionPlan;
import com.moex.cointegration.model.TradingSignal;
import com.moex.cointegration.storage.MarketDataStorage;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Мост между DAILY recommendations и broker adapter.
 * Готовит парный execution plan и решает, preview это, manual confirm или auto submit.
 */
@Service
public class PairExecutionService {

    private static final Logger log = LoggerFactory.getLogger(PairExecutionService.class);

    private final BrokerSettingsService brokerSettingsService;
    private final BrokerClient brokerClient;
    private final RiskPolicyService riskPolicyService;
    private final MarketDataStorage storage;
    private final Path journalFile;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final List<BrokerExecutionReport> reports = new CopyOnWriteArrayList<>();

    public PairExecutionService(
            BrokerSettingsService brokerSettingsService,
            BrokerClient brokerClient,
            RiskPolicyService riskPolicyService,
            MarketDataStorage storage,
            ImoexProperties imoexProperties
    ) {
        this.brokerSettingsService = brokerSettingsService;
        this.brokerClient = brokerClient;
        this.riskPolicyService = riskPolicyService;
        this.storage = storage;
        this.journalFile = Path.of(imoexProperties.dataDir(), "broker-execution-journal.json");
    }

    @PostConstruct
    void loadJournal() {
        reports.clear();
        if (!Files.exists(journalFile)) {
            return;
        }
        try {
            BrokerExecutionReport[] loaded = objectMapper.readValue(journalFile.toFile(), BrokerExecutionReport[].class);
            reports.addAll(List.of(loaded));
        } catch (Exception ex) {
            log.warn("Could not load broker execution journal {}: {}", journalFile, ex.getMessage());
        }
    }

    public BrokerStatus status() {
        return brokerClient.status();
    }

    public List<BrokerExecutionReport> recentReports() {
        return List.copyOf(reports);
    }

    public List<BrokerExecutionReport> executeActionableDaily(List<FinalTradeRecommendation> finals) {
        if (finals == null || finals.isEmpty()) {
            return List.of();
        }
        BrokerMode mode = BrokerMode.from(brokerSettingsService.effective().mode());
        return finals.stream()
                .filter(this::actionable)
                .sorted(Comparator.comparingDouble((FinalTradeRecommendation f) ->
                        Math.abs(f.technical().currentZScore())).reversed())
                .map(f -> execute(f, BookKind.DAILY, mode == BrokerMode.MANUAL_CONFIRM))
                .toList();
    }

    public BrokerExecutionReport preview(FinalTradeRecommendation finalRec, BookKind book) {
        PairExecutionPlan plan = buildPlan(finalRec, book);
        BrokerExecutionReport report = brokerClient.preview(plan);
        remember(report);
        return report;
    }

    public BrokerExecutionReport execute(FinalTradeRecommendation finalRec, BookKind book, boolean requireConfirm) {
        if (!actionable(finalRec)) {
            BrokerExecutionReport report = skipped(finalRec, book, "Final decision is not ENTER/REDUCE_SIZE");
            remember(report);
            return report;
        }

        PairExecutionPlan plan = buildPlan(finalRec, book);
        BrokerMode mode = plan.mode();
        BrokerExecutionReport report;
        if (mode == BrokerMode.PAPER) {
            report = brokerClient.preview(plan);
        } else if (mode == BrokerMode.MANUAL_CONFIRM || requireConfirm) {
            report = new BrokerExecutionReport(
                    plan.pairKey(),
                    BrokerExecutionStatus.BLOCKED_MANUAL_CONFIRM,
                    plan.provider(),
                    BrokerMode.MANUAL_CONFIRM,
                    LocalDateTime.now(),
                    "Manual confirmation required before broker submit",
                    List.of("Use POST /api/broker/execute?confirm=true for explicit submit."),
                    List.of(),
                    plan
            );
        } else {
            report = brokerClient.execute(plan);
        }
        remember(report);
        return report;
    }

    public BrokerExecutionReport flattenAll() {
        BrokerExecutionReport report = brokerClient.flattenAll();
        remember(report);
        return report;
    }

    public PairExecutionPlan buildPlan(FinalTradeRecommendation finalRec, BookKind book) {
        var brokerProperties = brokerSettingsService.effective();
        TradingSignal signal = finalRec.technical().signal();
        boolean reduce = finalRec.decision() == FinalTradeDecision.REDUCE_SIZE;
        double notionalY = riskPolicyService.suggestedNotional(finalRec.technical(), reduce);
        double notionalX = notionalY * Math.abs(finalRec.technical().hedgeRatio());

        Double refY = storage.lastClose(finalRec.tickerY()).orElse(null);
        Double refX = storage.lastClose(finalRec.tickerX()).orElse(null);
        BrokerOrderSide sideY = signal == TradingSignal.LONG_SPREAD ? BrokerOrderSide.BUY : BrokerOrderSide.SELL;
        BrokerOrderSide sideX = signal == TradingSignal.LONG_SPREAD ? BrokerOrderSide.SELL : BrokerOrderSide.BUY;
        BrokerOrderType orderType = brokerProperties.preferLimitOrdersFlag()
                ? BrokerOrderType.LIMIT
                : BrokerOrderType.MARKET;

        BrokerOrderIntent legY = new BrokerOrderIntent(
                finalRec.tickerY(),
                sideY,
                orderType,
                refY,
                limitPrice(refY, sideY),
                quantity(notionalY, refY),
                notionalY
        );
        BrokerOrderIntent legX = new BrokerOrderIntent(
                finalRec.tickerX(),
                sideX,
                orderType,
                refX,
                limitPrice(refX, sideX),
                quantity(notionalX, refX),
                notionalX
        );

        return new PairExecutionPlan(
                pairKey(finalRec.tickerY(), finalRec.tickerX()),
                book,
                BrokerMode.from(brokerProperties.mode()),
                brokerProperties.provider(),
                LocalDateTime.now(),
                finalRec.decision(),
                signal,
                finalRec.technical().currentZScore(),
                finalRec.technical().hedgeRatio(),
                finalRec.rationale(),
                legY,
                legX,
                brokerProperties.allowMarketFallbackFlag(),
                brokerProperties.secondLegTimeoutSeconds(),
                brokerProperties.maxLegDriftBps()
        );
    }

    private BrokerExecutionReport skipped(FinalTradeRecommendation finalRec, BookKind book, String reason) {
        PairExecutionPlan plan = buildPlan(finalRec, book);
        return new BrokerExecutionReport(
                plan.pairKey(),
                BrokerExecutionStatus.SKIPPED,
                plan.provider(),
                plan.mode(),
                LocalDateTime.now(),
                reason,
                List.of(reason),
                List.of(),
                plan
        );
    }

    private boolean actionable(FinalTradeRecommendation f) {
        if (f == null) {
            return false;
        }
        if (f.decision() != FinalTradeDecision.ENTER && f.decision() != FinalTradeDecision.REDUCE_SIZE) {
            return false;
        }
        return f.technical().signal() == TradingSignal.LONG_SPREAD
                || f.technical().signal() == TradingSignal.SHORT_SPREAD;
    }

    private void remember(BrokerExecutionReport report) {
        reports.add(0, report);
        while (reports.size() > 100) {
            reports.remove(reports.size() - 1);
        }
        saveJournal();
        log.info("Broker execution [{} {}]: {}", report.mode(), report.status(), report.summary());
    }

    private void saveJournal() {
        try {
            Files.createDirectories(journalFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(journalFile.toFile(), reports);
        } catch (IOException ex) {
            log.warn("Could not save broker execution journal {}: {}", journalFile, ex.getMessage());
        }
    }

    private Double quantity(double notional, Double price) {
        if (price == null || price <= 0 || notional <= 0) {
            return null;
        }
        return notional / price;
    }

    private Double limitPrice(Double reference, BrokerOrderSide side) {
        var brokerProperties = brokerSettingsService.effective();
        if (reference == null || reference <= 0) {
            return null;
        }
        double bps = brokerProperties.passivePriceOffsetBps() / 10_000.0;
        double mult = side == BrokerOrderSide.BUY ? (1.0 - bps) : (1.0 + bps);
        return round(reference * mult);
    }

    private static String pairKey(String y, String x) {
        return y.toUpperCase(Locale.ROOT) + "/" + x.toUpperCase(Locale.ROOT);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
