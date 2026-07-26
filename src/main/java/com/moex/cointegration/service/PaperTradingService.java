package com.moex.cointegration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moex.cointegration.config.CapitalProperties;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.config.SessionProperties;
import com.moex.cointegration.model.FinalTradeDecision;
import com.moex.cointegration.model.FinalTradeRecommendation;
import com.moex.cointegration.model.PairAnalysisResult;
import com.moex.cointegration.model.PaperJournal;
import com.moex.cointegration.model.PaperTradeEntry;
import com.moex.cointegration.model.SpreadPoint;
import com.moex.cointegration.model.TradingRecommendation;
import com.moex.cointegration.model.TradingSignal;
import com.moex.cointegration.quant.CusumDetector;
import com.moex.cointegration.quant.ExitRules;
import com.moex.cointegration.quant.MarketSession;
import com.moex.cointegration.storage.MarketDataStorage;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Автоматический paper track-record: cash PnL (qty×price) + slippage/borrow, capital cap, session flatten.
 */
@Service
public class PaperTradingService {

    private static final Logger log = LoggerFactory.getLogger(PaperTradingService.class);
    private static final double Z_TO_PCT = 0.01;

    private final ImoexProperties properties;
    private final CapitalProperties capitalProperties;
    private final SessionProperties sessionProperties;
    private final RiskPolicyService riskPolicyService;
    private final PairLookupService pairLookupService;
    private final MarketDataStorage storage;
    private final ObjectMapper objectMapper;
    private final List<PaperTradeEntry> entries = new CopyOnWriteArrayList<>();

    @Autowired
    public PaperTradingService(
            ImoexProperties properties,
            CapitalProperties capitalProperties,
            SessionProperties sessionProperties,
            RiskPolicyService riskPolicyService,
            PairLookupService pairLookupService,
            MarketDataStorage storage
    ) {
        this.properties = properties;
        this.capitalProperties = capitalProperties;
        this.sessionProperties = sessionProperties;
        this.riskPolicyService = riskPolicyService;
        this.pairLookupService = pairLookupService;
        this.storage = storage;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /** Тесты / упрощённый конструктор. */
    public PaperTradingService(
            ImoexProperties properties,
            RiskPolicyService riskPolicyService,
            PairLookupService pairLookupService
    ) {
        this(properties, CapitalProperties.defaults(), SessionProperties.defaults(),
                riskPolicyService, pairLookupService, null);
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
        String book = currentBook();
        return entries.stream()
                .filter(e -> "OPEN".equals(e.status()))
                .filter(e -> book.equalsIgnoreCase(e.book() == null ? "DAILY" : e.book()))
                .toList();
    }

    public synchronized List<PaperTradeEntry> syncFromFinals(List<FinalTradeRecommendation> finals)
            throws IOException {
        List<TradingRecommendation> quotes = finals == null ? List.of()
                : finals.stream().map(FinalTradeRecommendation::technical).toList();
        return sync(finals, quotes);
    }

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

        if (sessionProperties.intradayMode()) {
            MarketSession.Phase phase = MarketSession.current(LocalDateTime.now(), sessionProperties);
            if (phase == MarketSession.Phase.PRE_CLOSE) {
                int flat = flattenAll(quotes, "PRE_CLOSE flatten — без овернайта в INTRADAY");
                save();
                log.info("Paper PRE_CLOSE flattened {}", flat);
                return List.of();
            }
            if (phase == MarketSession.Phase.CLOSED || phase == MarketSession.Phase.OVERNIGHT) {
                log.info("Paper skip opens: session {}", phase);
                int closed = markAndCloseOpen(quotes);
                save();
                return List.of();
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

    private int flattenAll(Map<String, TradingRecommendation> quotes, String reason) {
        List<PaperTradeEntry> updated = new ArrayList<>();
        for (PaperTradeEntry open : getOpenTrades()) {
            Quote q = resolveQuote(open, quotes);
            double z = q == null ? (open.markZ() == null ? open.entryZ() : open.markZ()) : q.z();
            LocalDate asOf = q == null ? open.asOfDate() : q.asOf();
            double[] pnl = computePnl(open, z, asOf, q == null ? null : q.priceY(), q == null ? null : q.priceX());
            updated.add(open.withClose(LocalDateTime.now(), z, pnl[0], pnl[1], reason));
        }
        for (PaperTradeEntry e : updated) {
            replace(e);
        }
        return updated.size();
    }

    private List<PaperTradeEntry> openNew(List<FinalTradeRecommendation> finals) {
        List<PaperTradeEntry> opened = new ArrayList<>();
        int openCount = getOpenTrades().size();
        int capacity = riskPolicyService.maxOpenPairs() - openCount;
        if (capacity <= 0 || finals.isEmpty()) {
            return opened;
        }

        if (sessionProperties.intradayMode()
                && Boolean.TRUE.equals(sessionProperties.preventWeekendHold())
                && MarketSession.isWeekend(LocalDate.now())) {
            return opened;
        }

        Map<com.moex.cointegration.universe.SectorCatalog.Sector, Integer> openBySector = countOpenBySector();
        double openGross = getOpenTrades().stream()
                .mapToDouble(e -> e.notionalY() + e.notionalX())
                .sum();
        double maxGross = capitalProperties.maxGrossNotional();

        List<FinalTradeRecommendation> candidates = finals.stream()
                .filter(f -> f.decision() == FinalTradeDecision.ENTER
                        || f.decision() == FinalTradeDecision.REDUCE_SIZE)
                .filter(f -> {
                    TradingSignal s = f.technical().signal();
                    return s == TradingSignal.LONG_SPREAD || s == TradingSignal.SHORT_SPREAD;
                })
                .sorted(Comparator
                        .comparingInt((FinalTradeRecommendation f) ->
                                sectorOpenCount(f.technical().tickerY(), openBySector))
                        .thenComparing(Comparator.comparingDouble((FinalTradeRecommendation f) ->
                                Math.abs(f.technical().currentZScore())).reversed()))
                .toList();

        for (FinalTradeRecommendation f : candidates) {
            if (opened.size() >= capacity) {
                break;
            }
            if (alreadyOpen(f.technical().tickerY(), f.technical().tickerX())) {
                continue;
            }
            if (!allowSectorSlot(f.technical().tickerY(), f.technical().tickerX(), openBySector)) {
                continue;
            }
            double z = f.technical().currentZScore();
            double stopZ = properties.risk().stopZ();
            if (Math.abs(z) >= stopZ - 0.5) {
                log.info("Paper skip {}/{}: |Z| too close to stop", f.technical().tickerY(), f.technical().tickerX());
                continue;
            }
            boolean reduce = f.decision() == FinalTradeDecision.REDUCE_SIZE;
            double mult = riskPolicyService.sizeMultiplier(f.technical(), reduce);
            double notionalY = properties.paper().notionalPerLeg() * mult;
            double beta = Math.abs(f.technical().hedgeRatio());
            double notionalX = notionalY * beta;
            if (openGross + notionalY + notionalX > maxGross + 1e-6) {
                log.info("Paper skip {}/{}: capital gross cap {} (equity={})",
                        f.technical().tickerY(), f.technical().tickerX(),
                        String.format(Locale.ROOT, "%.0f", maxGross),
                        String.format(Locale.ROOT, "%.0f", capitalProperties.equityRub()));
                continue;
            }

            Double priceY = lastPrice(f.technical().tickerY());
            Double priceX = lastPrice(f.technical().tickerX());
            Double qtyY = priceY != null && priceY > 0 ? notionalY / priceY : null;
            Double qtyX = priceX != null && priceX > 0 ? notionalX / priceX : null;

            double slipCost = (notionalY + notionalX) * properties.paper().slippageFraction();
            String notes = "AUTO OPEN: " + f.decisionSummary()
                    + (capitalProperties.leverageAllowed() ? "" : " [no-leverage]");
            if (slipCost > 0) {
                notes += String.format(Locale.ROOT, "; slip≈%.0f₽", slipCost);
            }

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
                    notionalY,
                    notionalX,
                    mult,
                    "OPEN",
                    null, null, null, null,
                    f.technical().currentZScore(),
                    0.0,
                    -slipCost,
                    f.technical().asOfDate(),
                    notes,
                    f.technical().currentZScore(),
                    false,
                    1.0,
                    0.0,
                    qtyY, qtyX, priceY, priceX,
                    currentBook()
            );
            entries.add(entry);
            opened.add(entry);
            openGross += notionalY + notionalX;
            bumpSector(f.technical().tickerY(), openBySector);
        }
        return opened;
    }

    private Map<com.moex.cointegration.universe.SectorCatalog.Sector, Integer> countOpenBySector() {
        Map<com.moex.cointegration.universe.SectorCatalog.Sector, Integer> map = new HashMap<>();
        for (PaperTradeEntry e : getOpenTrades()) {
            bumpSector(e.tickerY(), map);
        }
        return map;
    }

    private static int sectorOpenCount(
            String tickerY,
            Map<com.moex.cointegration.universe.SectorCatalog.Sector, Integer> openBySector
    ) {
        return com.moex.cointegration.universe.SectorCatalog.sectorOf(tickerY)
                .map(s -> openBySector.getOrDefault(s, 0))
                .orElse(0);
    }

    private boolean allowSectorSlot(
            String tickerY,
            String tickerX,
            Map<com.moex.cointegration.universe.SectorCatalog.Sector, Integer> openBySector
    ) {
        if (!properties.portfolio().diversifyBySectorEnabled()) {
            return true;
        }
        var sector = com.moex.cointegration.universe.SectorCatalog.sectorOf(tickerY)
                .or(() -> com.moex.cointegration.universe.SectorCatalog.sectorOf(tickerX));
        if (sector.isEmpty()) {
            return true;
        }
        return openBySector.getOrDefault(sector.get(), 0) < properties.portfolio().maxPairsPerSector();
    }

    private static void bumpSector(
            String tickerY,
            Map<com.moex.cointegration.universe.SectorCatalog.Sector, Integer> openBySector
    ) {
        com.moex.cointegration.universe.SectorCatalog.sectorOf(tickerY).ifPresent(s ->
                openBySector.merge(s, 1, Integer::sum));
    }

    private int markAndCloseOpen(Map<String, TradingRecommendation> quotes) {
        double zExit = properties.cointegration().zScoreExit();
        var risk = properties.risk();
        int maxHold = risk.maxHoldBars();
        List<PaperTradeEntry> updated = new ArrayList<>();

        for (PaperTradeEntry open : getOpenTrades()) {
            Quote q = resolveQuote(open, quotes);
            if (q == null) {
                log.warn("Paper: no Z quote for open {}/{}", open.tickerY(), open.tickerX());
                continue;
            }

            boolean longSpread = open.signal() == TradingSignal.LONG_SPREAD;
            double bestZ = ExitRules.updateBestZ(longSpread,
                    open.bestZ() == null ? open.entryZ() : open.bestZ(), q.z());

            double[] pnl = computePnl(open, q.z(), q.asOf(), q.priceY(), q.priceX());
            double pnlPct = pnl[0];
            double pnlRub = pnl[1];
            int barsHeld = (int) ChronoUnit.DAYS.between(open.asOfDate(), q.asOf());
            if (barsHeld < 0) {
                barsHeld = 0;
            }

            double stopZ = q.stopZ() > 0 ? q.stopZ() : risk.stopZ();
            String closeReason = null;
            if (barsHeld >= 1) {
                closeReason = closeReason(open, q, zExit, stopZ, maxHold, barsHeld, bestZ, risk);
            }

            if (closeReason != null) {
                double slip = (open.notionalY() + open.notionalX()) * properties.paper().slippageFraction();
                updated.add(open.withClose(LocalDateTime.now(), q.z(), pnlPct, pnlRub - slip, closeReason));
            } else if (barsHeld >= 1
                    && !open.partialDone()
                    && ExitRules.halfwayToZero(open.entryZ(), q.z(), risk.partialTpFraction())) {
                double halfRub = pnlRub * 0.5;
                updated.add(open.withPartialTp(
                        q.asOf(), q.z(), halfRub,
                        String.format(Locale.ROOT, "PARTIAL TP @ Z=%.2f (≈%.0f ₽)", q.z(), halfRub)
                ).withMark(q.asOf(), q.z(), pnlPct * 0.5, pnlRub * 0.5, bestZ));
            } else {
                updated.add(open.withMark(q.asOf(), q.z(), pnlPct, pnlRub, bestZ));
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
            int barsHeld,
            double bestZ,
            ImoexProperties.RiskProperties risk
    ) {
        boolean longSpread = open.signal() == TradingSignal.LONG_SPREAD;

        if (Math.abs(q.z()) <= zExit + 0.25) {
            return String.format(Locale.ROOT, "AUTO CLOSE: mean-reversion Z=%.2f", q.z());
        }
        if (Math.abs(q.z()) >= stopZ) {
            return String.format(Locale.ROOT, "AUTO CLOSE: stop |Z|=%.2f ≥ %.1f", q.z(), stopZ);
        }
        if (ExitRules.trailStopHit(longSpread, bestZ, q.z(), risk.trailZ())) {
            return String.format(Locale.ROOT, "AUTO CLOSE: trailing from bestZ=%.2f", bestZ);
        }
        if (ExitRules.betaBreak(open.hedgeRatio(), q.hedgeRatio(), risk.betaBreakPct())) {
            return "AUTO CLOSE: β-break";
        }
        if (ExitRules.cointegrationBroken(q.pValue(), risk.cointPBreak())) {
            return String.format(Locale.ROOT, "AUTO CLOSE: cointegration break p=%.3f", q.pValue());
        }
        if (q.cusumBreak()) {
            return "AUTO CLOSE: STRUCTURAL_BREAK (CUSUM)";
        }
        if (barsHeld >= maxHold) {
            return String.format(Locale.ROOT, "AUTO CLOSE: time-stop %d ≥ %d", barsHeld, maxHold);
        }
        if ((q.signal() == TradingSignal.HOLD || q.signal() == TradingSignal.NO_SIGNAL)
                && Math.abs(q.z()) <= 1.0) {
            return "AUTO CLOSE: HOLD/NO_SIGNAL при |Z|≤1";
        }
        if (open.signal() == TradingSignal.LONG_SPREAD && q.signal() == TradingSignal.SHORT_SPREAD) {
            return "AUTO CLOSE: LONG→SHORT";
        }
        if (open.signal() == TradingSignal.SHORT_SPREAD && q.signal() == TradingSignal.LONG_SPREAD) {
            return "AUTO CLOSE: SHORT→LONG";
        }
        return null;
    }

    /**
     * @return [pnlPct, pnlRub]
     */
    double[] computePnl(PaperTradeEntry open, double markZ, LocalDate asOf, Double priceY, Double priceX) {
        double zPct = approximatePnlPct(open.entryZ(), markZ, open.signal());
        if (open.hasCashLegs() && priceY != null && priceX != null && priceY > 0 && priceX > 0) {
            boolean longSpread = open.signal() == TradingSignal.LONG_SPREAD;
            double signY = longSpread ? 1.0 : -1.0;
            double signX = longSpread ? -1.0 : 1.0;
            double rub = (priceY - open.entryPriceY()) * open.qtyY() * signY
                    + (priceX - open.entryPriceX()) * open.qtyX() * signX;
            if (properties.paper().applyBorrowEnabled()) {
                int days = Math.max(0, (int) ChronoUnit.DAYS.between(open.asOfDate(), asOf));
                rub -= properties.risk().borrowRateAnnual() * days * open.notionalX() / 365.0
                        * open.remainingFracOrOne();
            }
            double pct = open.notionalY() > 0 ? rub / open.notionalY() : zPct;
            return new double[]{pct, rub};
        }
        double rub = open.notionalY() * zPct * open.remainingFracOrOne();
        if (properties.paper().applyBorrowEnabled()) {
            int days = Math.max(0, (int) ChronoUnit.DAYS.between(open.asOfDate(), asOf));
            rub -= properties.risk().borrowRateAnnual() * days * open.notionalX() / 365.0
                    * open.remainingFracOrOne();
        }
        return new double[]{zPct, rub};
    }

    private Quote resolveQuote(PaperTradeEntry open, Map<String, TradingRecommendation> quotes) {
        TradingRecommendation fromMap = quotes.get(key(open.tickerY(), open.tickerX()));
        Double py = lastPrice(open.tickerY());
        Double px = lastPrice(open.tickerX());
        if (fromMap != null && !Double.isNaN(fromMap.currentZScore())) {
            boolean cusum = false;
            double stop = properties.risk().stopZ();
            try {
                PairAnalysisResult pair = pairLookupService.requirePair(open.tickerY(), open.tickerX());
                double[] spread = pair.spreadSeries().stream().mapToDouble(SpreadPoint::value).toArray();
                stop = riskPolicyService.effectiveStopZ(spread);
                double[] zArr = pair.zScoreSeries().stream().mapToDouble(SpreadPoint::value).toArray();
                var risk = properties.risk();
                cusum = risk.cusumEnabledFlag()
                        && CusumDetector.detectTail(zArr, risk.cusumLookback(), risk.cusumThreshold(), risk.cusumDrift());
            } catch (Exception ignored) {
                // keep defaults
            }
            return new Quote(
                    fromMap.currentZScore(), fromMap.asOfDate(), fromMap.signal(),
                    fromMap.hedgeRatio(), fromMap.pValue(), py, px, stop, cusum
            );
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
            double[] spread = pair.spreadSeries().stream().mapToDouble(SpreadPoint::value).toArray();
            double stop = riskPolicyService.effectiveStopZ(spread);
            double[] zArr = z.stream().mapToDouble(SpreadPoint::value).toArray();
            var risk = properties.risk();
            boolean cusum = risk.cusumEnabledFlag()
                    && CusumDetector.detectTail(zArr, risk.cusumLookback(), risk.cusumThreshold(), risk.cusumDrift());
            return new Quote(last.value(), last.date(), guess, pair.hedgeRatio(), pair.pValue(),
                    py, px, stop, cusum);
        } catch (Exception ex) {
            return null;
        }
    }

    private Double lastPrice(String ticker) {
        if (storage == null) {
            return null;
        }
        return storage.lastClose(ticker).orElse(null);
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
        double closed = entries.stream()
                .filter(e -> "CLOSED".equals(e.status()) && e.pnlRub() != null)
                .mapToDouble(PaperTradeEntry::pnlRub)
                .sum();
        double partialOpen = getOpenTrades().stream()
                .filter(e -> e.realizedPartialRub() != null)
                .mapToDouble(PaperTradeEntry::realizedPartialRub)
                .sum();
        return closed + partialOpen;
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
        String name = sessionProperties.intradayMode()
                ? sessionProperties.intradayJournalFile()
                : properties.paper().journalFile();
        if (name == null || name.isBlank()) {
            name = sessionProperties.intradayMode() ? "paper-journal-intraday.json" : "paper-journal.json";
        }
        return Path.of(properties.dataDir(), name);
    }

    private String currentBook() {
        return sessionProperties.intradayMode() ? "INTRADAY" : "DAILY";
    }

    private record Quote(
            double z,
            LocalDate asOf,
            TradingSignal signal,
            double hedgeRatio,
            double pValue,
            Double priceY,
            Double priceX,
            double stopZ,
            boolean cusumBreak
    ) {
    }
}
