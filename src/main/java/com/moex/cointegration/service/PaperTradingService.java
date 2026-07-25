package com.moex.cointegration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.FinalTradeDecision;
import com.moex.cointegration.model.FinalTradeRecommendation;
import com.moex.cointegration.model.PairAnalysisResult;
import com.moex.cointegration.model.PaperJournal;
import com.moex.cointegration.model.PaperTradeEntry;
import com.moex.cointegration.model.SpreadPoint;
import com.moex.cointegration.model.TradingRecommendation;
import com.moex.cointegration.model.TradingSignal;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Автоматический paper track-record: вход по ENTER/REDUCE → удержание с MTM → выход с псевдо-PnL.
 * Триггер — каждый {@code sync} (полный анализ / news-refresh / daily cron).
 */
@Service
public class PaperTradingService {

    private static final Logger log = LoggerFactory.getLogger(PaperTradingService.class);

    /** Proxy: 1 единица Z ≈ 1% notional Y (журнальная метрика, не брокерский P&amp;L). */
    private static final double Z_TO_PCT = 0.01;

    private final ImoexProperties properties;
    private final RiskPolicyService riskPolicyService;
    private final PairLookupService pairLookupService;
    private final ObjectMapper objectMapper;
    private final List<PaperTradeEntry> entries = new CopyOnWriteArrayList<>();

    public PaperTradingService(
            ImoexProperties properties,
            RiskPolicyService riskPolicyService,
            PairLookupService pairLookupService
    ) {
        this.properties = properties;
        this.riskPolicyService = riskPolicyService;
        this.pairLookupService = pairLookupService;
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
     * Совместимость: sync только по finals (котировки берутся из technical внутри finals + lookup).
     */
    public synchronized List<PaperTradeEntry> syncFromFinals(List<FinalTradeRecommendation> finals)
            throws IOException {
        List<TradingRecommendation> quotes = finals == null ? List.of()
                : finals.stream().map(FinalTradeRecommendation::technical).toList();
        return sync(finals, quotes);
    }

    /**
     * Полный цикл: mark/close открытых → открыть новые ENTER/REDUCE (в пределах maxOpenPairs).
     */
    public synchronized List<PaperTradeEntry> sync(
            List<FinalTradeRecommendation> finals,
            List<TradingRecommendation> technicalQuotes
    ) throws IOException {
        if (!properties.paper().enabled()) {
            return List.of();
        }

        Map<String, TradingRecommendation> quotes = indexQuotes(technicalQuotes);
        if (finals != null) {
            for (FinalTradeRecommendation f : finals) {
                quotes.putIfAbsent(key(f.technical().tickerY(), f.technical().tickerX()), f.technical());
            }
        }

        int closed = markAndCloseOpen(quotes);
        List<PaperTradeEntry> opened = openNew(finals == null ? List.of() : finals);
        save();
        log.info("Paper sync: opened={}, closed={}, openNow={}, realized≈{} ₽, unrealized≈{} ₽",
                opened.size(), closed, getOpenTrades().size(),
                round(sumRealizedRub()), round(sumUnrealizedRub()));
        return opened;
    }

    private List<PaperTradeEntry> openNew(List<FinalTradeRecommendation> finals) {
        List<PaperTradeEntry> opened = new ArrayList<>();
        int openCount = getOpenTrades().size();
        int capacity = riskPolicyService.maxOpenPairs() - openCount;
        if (capacity <= 0 || finals.isEmpty()) {
            return opened;
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
            double z = f.technical().currentZScore();
            double stopZ = properties.risk().stopZ();
            // Не входить почти у стопа: при entry 3.3 и stop 3.5 запас ~0.2 Z — лотерея.
            if (Math.abs(z) >= stopZ - 0.5) {
                log.info("Paper skip {}/{}: |Z|={} слишком близко к stop {}",
                        f.technical().tickerY(), f.technical().tickerX(),
                        String.format(Locale.ROOT, "%.2f", Math.abs(z)), stopZ);
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
                    null,
                    f.technical().currentZScore(),
                    0.0,
                    0.0,
                    f.technical().asOfDate(),
                    "AUTO OPEN: " + f.decisionSummary()
            );
            entries.add(entry);
            opened.add(entry);
        }
        return opened;
    }

    private int markAndCloseOpen(Map<String, TradingRecommendation> quotes) {
        double zExit = properties.cointegration().zScoreExit();
        double stopZ = properties.risk().stopZ();
        int maxHold = properties.risk().maxHoldBars();
        List<PaperTradeEntry> updated = new ArrayList<>();

        for (PaperTradeEntry open : getOpenTrades()) {
            Quote q = resolveQuote(open, quotes);
            if (q == null) {
                log.warn("Paper: no Z quote for open {}/{} — skip mark this run",
                        open.tickerY(), open.tickerX());
                continue;
            }

            double pnlPct = approximatePnlPct(open.entryZ(), q.z(), open.signal());
            double pnlRub = open.notionalY() * pnlPct;
            int barsHeld = (int) ChronoUnit.DAYS.between(open.asOfDate(), q.asOf());
            if (barsHeld < 0) {
                barsHeld = 0;
            }

            // Пока нет новой дневной свечи — только MTM. Иначе повторный run в тот же день
            // пересчитывает rolling/Kalman Z и ложно бьёт по стопу (как FEES/SNGS −719 ₽).
            String closeReason = null;
            if (barsHeld >= 1) {
                closeReason = closeReason(open, q, zExit, stopZ, maxHold, barsHeld);
            }
            if (closeReason != null) {
                updated.add(open.withClose(LocalDateTime.now(), q.z(), pnlPct, pnlRub, closeReason));
            } else {
                updated.add(open.withMark(q.asOf(), q.z(), pnlPct, pnlRub));
            }
        }

        for (PaperTradeEntry e : updated) {
            replace(e);
        }
        return (int) updated.stream().filter(e -> "CLOSED".equals(e.status())).count();
    }

    private String closeReason(
            PaperTradeEntry open,
            Quote q,
            double zExit,
            double stopZ,
            int maxHold,
            int barsHeld
    ) {
        if (Math.abs(q.z()) <= zExit + 0.25) {
            return String.format(Locale.ROOT,
                    "AUTO CLOSE: mean-reversion Z=%.2f → exit (псевдо PnL)", q.z());
        }
        if (Math.abs(q.z()) >= stopZ) {
            return String.format(Locale.ROOT,
                    "AUTO CLOSE: stop |Z|=%.2f ≥ %.1f", q.z(), stopZ);
        }
        if (barsHeld >= maxHold) {
            return String.format(Locale.ROOT,
                    "AUTO CLOSE: time-stop %d ≥ %d дней", barsHeld, maxHold);
        }
        if (q.signal() == TradingSignal.HOLD || q.signal() == TradingSignal.NO_SIGNAL) {
            if (Math.abs(q.z()) <= 1.0) {
                return "AUTO CLOSE: сигнал HOLD/NO_SIGNAL при |Z|≤1";
            }
        }
        if (open.signal() == TradingSignal.LONG_SPREAD && q.signal() == TradingSignal.SHORT_SPREAD) {
            return "AUTO CLOSE: разворот сигнала LONG→SHORT";
        }
        if (open.signal() == TradingSignal.SHORT_SPREAD && q.signal() == TradingSignal.LONG_SPREAD) {
            return "AUTO CLOSE: разворот сигнала SHORT→LONG";
        }
        return null;
    }

    private Quote resolveQuote(PaperTradeEntry open, Map<String, TradingRecommendation> quotes) {
        TradingRecommendation fromMap = quotes.get(key(open.tickerY(), open.tickerX()));
        if (fromMap != null && !Double.isNaN(fromMap.currentZScore())) {
            return new Quote(fromMap.currentZScore(), fromMap.asOfDate(), fromMap.signal());
        }
        try {
            PairAnalysisResult pair = pairLookupService.requirePair(open.tickerY(), open.tickerX());
            List<SpreadPoint> z = pair.zScoreSeries();
            if (z == null || z.isEmpty()) {
                return null;
            }
            SpreadPoint last = z.get(z.size() - 1);
            TradingSignal guess = TradingSignal.HOLD;
            if (last.value() <= -properties.cointegration().zScoreEntry()) {
                guess = TradingSignal.LONG_SPREAD;
            } else if (last.value() >= properties.cointegration().zScoreEntry()) {
                guess = TradingSignal.SHORT_SPREAD;
            }
            return new Quote(last.value(), last.date(), guess);
        } catch (Exception ex) {
            log.debug("Paper lookup failed for {}/{}: {}", open.tickerY(), open.tickerX(), ex.getMessage());
            return null;
        }
    }

    public PaperJournal summary() {
        return new PaperJournal(
                LocalDateTime.now(),
                getJournal(),
                round(sumRealizedRub()),
                round(sumUnrealizedRub()),
                getOpenTrades().size(),
                (int) entries.stream().filter(e -> "CLOSED".equals(e.status())).count()
        );
    }

    double approximatePnlPct(double entryZ, double exitZ, TradingSignal signal) {
        double delta = exitZ - entryZ;
        if (signal == TradingSignal.SHORT_SPREAD) {
            delta = -delta;
        }
        return delta * Z_TO_PCT;
    }

    private double sumRealizedRub() {
        return entries.stream()
                .filter(e -> "CLOSED".equals(e.status()) && e.pnlRub() != null)
                .mapToDouble(PaperTradeEntry::pnlRub)
                .sum();
    }

    private double sumUnrealizedRub() {
        return getOpenTrades().stream()
                .filter(e -> e.unrealizedPnlRub() != null)
                .mapToDouble(PaperTradeEntry::unrealizedPnlRub)
                .sum();
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static Map<String, TradingRecommendation> indexQuotes(List<TradingRecommendation> technicalQuotes) {
        Map<String, TradingRecommendation> map = new HashMap<>();
        if (technicalQuotes == null) {
            return map;
        }
        for (TradingRecommendation t : technicalQuotes) {
            map.put(key(t.tickerY(), t.tickerX()), t);
        }
        return map;
    }

    private static String key(String y, String x) {
        return y.toUpperCase(Locale.ROOT) + "/" + x.toUpperCase(Locale.ROOT);
    }

    private boolean alreadyOpen(String y, String x) {
        return getOpenTrades().stream()
                .anyMatch(e -> e.tickerY().equalsIgnoreCase(y) && e.tickerX().equalsIgnoreCase(x));
    }

    private void replace(PaperTradeEntry next) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(next.id())) {
                entries.set(i, next);
                return;
            }
        }
    }

    private void save() throws IOException {
        Path file = journalFile();
        Files.createDirectories(file.getParent() == null ? Path.of(".") : file.getParent());
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(file.toFile(), summary());
    }

    private Path journalFile() {
        String name = properties.paper().journalFile();
        if (name == null || name.isBlank()) {
            name = "paper-journal.json";
        }
        return Path.of(properties.dataDir(), name);
    }

    private record Quote(double z, LocalDate asOf, TradingSignal signal) {
    }
}
