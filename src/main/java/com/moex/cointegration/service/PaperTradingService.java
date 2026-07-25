package com.moex.cointegration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.FinalTradeDecision;
import com.moex.cointegration.model.FinalTradeRecommendation;
import com.moex.cointegration.model.PaperJournal;
import com.moex.cointegration.model.PaperTradeEntry;
import com.moex.cointegration.model.TradingRecommendation;
import com.moex.cointegration.model.TradingSignal;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Paper track-record: фиксирует ENTER/REDUCE сигналы в журнал без брокера.
 */
@Service
public class PaperTradingService {

    private static final Logger log = LoggerFactory.getLogger(PaperTradingService.class);

    private final ImoexProperties properties;
    private final RiskPolicyService riskPolicyService;
    private final ObjectMapper objectMapper;
    private final List<PaperTradeEntry> entries = new CopyOnWriteArrayList<>();

    public PaperTradingService(ImoexProperties properties, RiskPolicyService riskPolicyService) {
        this.properties = properties;
        this.riskPolicyService = riskPolicyService;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @PostConstruct
    void load() {
        Path file = journalFile();
        if (!Files.exists(file)) {
            return;
        }
        try {
            PaperJournal journal = objectMapper.readValue(file.toFile(), PaperJournal.class);
            entries.clear();
            if (journal.entries() != null) {
                entries.addAll(journal.entries());
            }
            log.info("Loaded {} paper trades from {}", entries.size(), file);
        } catch (Exception ex) {
            log.warn("Could not load paper journal {}: {}", file, ex.getMessage());
        }
    }

    public List<PaperTradeEntry> getJournal() {
        return List.copyOf(entries);
    }

    public List<PaperTradeEntry> getOpenTrades() {
        return entries.stream().filter(e -> "OPEN".equals(e.status())).toList();
    }

    /**
     * Открывает paper-сделки по финальным рекомендациям с учётом maxOpenPairs.
     */
    public synchronized List<PaperTradeEntry> syncFromFinals(List<FinalTradeRecommendation> finals) throws IOException {
        if (!properties.paper().enabled()) {
            return List.of();
        }

        List<PaperTradeEntry> opened = new ArrayList<>();
        int openCount = getOpenTrades().size();
        int capacity = riskPolicyService.maxOpenPairs() - openCount;
        if (capacity <= 0) {
            log.info("Paper journal at capacity ({} open pairs)", openCount);
            return List.of();
        }

        List<FinalTradeRecommendation> candidates = finals.stream()
                .filter(f -> f.decision() == FinalTradeDecision.ENTER
                        || f.decision() == FinalTradeDecision.REDUCE_SIZE)
                .filter(f -> {
                    TradingSignal s = f.technical().signal();
                    return s == TradingSignal.LONG_SPREAD || s == TradingSignal.SHORT_SPREAD;
                })
                .sorted(Comparator.comparingDouble((FinalTradeRecommendation f) ->
                        Math.abs(f.technical().currentZScore())).reversed())
                .limit(capacity)
                .toList();

        for (FinalTradeRecommendation f : candidates) {
            if (alreadyOpen(f.technical().tickerY(), f.technical().tickerX())) {
                continue;
            }
            boolean reduce = f.decision() == FinalTradeDecision.REDUCE_SIZE;
            double mult = riskPolicyService.sizeMultiplier(f.technical().signal(), reduce);
            double notional = properties.paper().notionalPerLeg() * mult;
            double beta = Math.abs(f.technical().hedgeRatio());

            PaperTradeEntry entry = new PaperTradeEntry(
                    UUID.randomUUID().toString(),
                    LocalDateTime.now(),
                    f.technical().asOfDate(),
                    f.technical().tickerY(),
                    f.technical().tickerX(),
                    f.technical().signal(),
                    f.decision(),
                    f.technical().currentZScore(),
                    f.technical().hedgeRatio(),
                    notional,
                    notional * beta,
                    mult,
                    "OPEN",
                    null,
                    null,
                    null,
                    f.decisionSummary()
            );
            entries.add(entry);
            opened.add(entry);
        }

        // Mark-to-close: if latest signal for open pair is HOLD near zero / NO_SIGNAL, close.
        List<PaperTradeEntry> updated = new ArrayList<>();
        for (PaperTradeEntry open : getOpenTrades()) {
            finals.stream()
                    .filter(f -> f.technical().tickerY().equalsIgnoreCase(open.tickerY())
                            && f.technical().tickerX().equalsIgnoreCase(open.tickerX()))
                    .findFirst()
                    .ifPresent(f -> {
                        TradingRecommendation t = f.technical();
                        if (Math.abs(t.currentZScore()) <= properties.cointegration().zScoreExit() + 0.25
                                || t.signal() == TradingSignal.HOLD
                                || t.signal() == TradingSignal.NO_SIGNAL) {
                            double pnl = approximatePnlPct(open.entryZ(), t.currentZScore(), open.signal());
                            PaperTradeEntry closed = open.withClose(
                                    LocalDateTime.now(), t.currentZScore(), pnl, "Z mean-reverted / signal flat");
                            updated.add(closed);
                        }
                    });
        }
        for (PaperTradeEntry closed : updated) {
            replace(closed);
        }

        save();
        log.info("Paper sync: opened={}, closed={}, openNow={}",
                opened.size(), updated.size(), getOpenTrades().size());
        return opened;
    }

    public PaperJournal summary() {
        return new PaperJournal(LocalDateTime.now(), getJournal());
    }

    private double approximatePnlPct(double entryZ, double exitZ, TradingSignal signal) {
        // Rough proxy: long spread profits when Z rises toward 0 from negative.
        double delta = exitZ - entryZ;
        if (signal == TradingSignal.SHORT_SPREAD) {
            delta = -delta;
        }
        return delta * 0.01; // 1% notional proxy per 1 Z — journal metric only
    }

    private boolean alreadyOpen(String y, String x) {
        return getOpenTrades().stream()
                .anyMatch(e -> e.tickerY().equalsIgnoreCase(y) && e.tickerX().equalsIgnoreCase(x));
    }

    private void replace(PaperTradeEntry closed) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(closed.id())) {
                entries.set(i, closed);
                return;
            }
        }
    }

    private void save() throws IOException {
        Path file = journalFile();
        Files.createDirectories(file.getParent());
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(file.toFile(), new PaperJournal(LocalDateTime.now(), getJournal()));
    }

    private Path journalFile() {
        String name = properties.paper().journalFile();
        if (name == null || name.isBlank()) {
            name = "paper-journal.json";
        }
        return Path.of(properties.dataDir(), name);
    }
}
