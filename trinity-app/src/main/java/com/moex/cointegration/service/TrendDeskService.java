package com.moex.cointegration.service;

import com.moex.trinity.marketdata.DomBook;
import com.moex.trinity.marketdata.MarketDataResearchService;
import com.moex.trinity.trend.IssFuturesM1Client;
import com.moex.trinity.trend.LimitGridPlan;
import com.moex.trinity.trend.MergedVolumeRange;
import com.moex.trinity.trend.TapeToM5Aggregator;
import com.moex.trinity.trend.TrendAccountContext;
import com.moex.trinity.trend.TrendBar;
import com.moex.trinity.trend.TrendBarSeries;
import com.moex.trinity.trend.TrendEventCalendar;
import com.moex.trinity.trend.TrendPlaybook;
import com.moex.trinity.trend.TrendPlaybookSettings;
import com.moex.trinity.trend.TrendResearchService;
import com.moex.trinity.trend.TrendRobotEngine;
import com.moex.trinity.trend.TrendRobotPlan;
import com.moex.trinity.trend.TrendRobotState;
import com.moex.trinity.trend.TrendSignal;
import com.moex.trinity.trend.TrendStructureSnapshot;
import com.moex.trinity.trend.BrMacroBias;
import com.moex.trinity.trend.TrendSessionEdge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Operator signal desk: tape/ISS M5 bars + DOM + full playbook plan for markers.
 */
@Service
@ConditionalOnProperty(prefix = "imoex.strategies.trend", name = "enabled", havingValue = "true")
public class TrendDeskService {

    private static final Logger log = LoggerFactory.getLogger(TrendDeskService.class);
    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final int MIN_BARS_FOR_TAPE_ONLY = 40;
    /** Always keep at least this many calendar days of M5 on the desk (≥2 prior + today). */
    private static final int DESK_HISTORY_DAYS = 2;

    private final TrendRobotEngine engine;
    private final TrendExecutionBridge bridge;
    private final OperatorTradeToastService tradeToasts;
    private final TrendResearchService researchService;
    private final TrendPaperJournalService paperJournal;
    private final TrendEventCalendar eventCalendar;
    private final TrendPlaybookSettings settings;
    private final ObjectProvider<TrendFairPaperLiveService> fairPaper;
    private final ObjectProvider<MarketDataResearchService> marketData;
    private final String configuredInstrument;
    private final double equityRub;
    private final double goLongRub;
    private final double goShortRub;

    public TrendDeskService(
            TrendRobotEngine engine,
            TrendExecutionBridge bridge,
            OperatorTradeToastService tradeToasts,
            TrendResearchService researchService,
            TrendPaperJournalService paperJournal,
            TrendEventCalendar eventCalendar,
            TrendPlaybookSettings settings,
            ObjectProvider<TrendFairPaperLiveService> fairPaper,
            ObjectProvider<MarketDataResearchService> marketData,
            @Value("${imoex.marketdata.auto-resolve-instrument:BRU6}") String instrument,
            @Value("${imoex.capital.equity-rub:100000}") double equityRub,
            @Value("${imoex.strategies.trend.desk-go-long-rub:15000}") double goLongRub,
            @Value("${imoex.strategies.trend.desk-go-short-rub:16000}") double goShortRub
    ) {
        this.engine = engine;
        this.bridge = bridge;
        this.tradeToasts = tradeToasts;
        this.researchService = researchService;
        this.paperJournal = paperJournal;
        this.eventCalendar = eventCalendar == null ? TrendEventCalendar.empty() : eventCalendar;
        this.settings = settings == null ? TrendPlaybookSettings.brDefaults() : settings;
        this.fairPaper = fairPaper;
        this.marketData = marketData;
        this.configuredInstrument = instrument == null || instrument.isBlank() ? "BRU6" : instrument.trim();
        this.equityRub = equityRub > 0 ? equityRub : 100_000;
        this.goLongRub = goLongRub > 0 ? goLongRub : 15_000;
        this.goShortRub = goShortRub > 0 ? goShortRub : 16_000;
    }

    public String resolveInstrument() {
        MarketDataResearchService md = marketData.getIfAvailable();
        if (md != null && md.defaultInstrument() != null && !md.defaultInstrument().isBlank()) {
            return md.defaultInstrument();
        }
        return configuredInstrument;
    }

    /** Bars for desk / fair-paper live (tape preferred over ISS on overlap). */
    public List<TrendBar> loadBarsPublic(String instrument) {
        return loadBars(instrument, marketData.getIfAvailable());
    }

    private String deliveryLabel() {
        if (bridge.liveExecution()) {
            return "LIVE_GATED";
        }
        if (bridge.autoExecution()) {
            return "SANDBOX_FAIR";
        }
        return "SIGNAL_ONLY";
    }

    public Map<String, Object> desk() {
        String instrument = resolveInstrument();
        MarketDataResearchService md = marketData.getIfAvailable();

        List<TrendBar> bars = loadBars(instrument, md);
        Optional<DomBook> book = md == null ? Optional.empty() : md.resolveBook(instrument);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instrument", instrument);
        body.put("timeframe", "M5");
        body.put("delivery", deliveryLabel());
        body.put("autoExecution", bridge.autoExecution());
        body.put("engineState", engine.state().name());
        body.put("barCount", bars.size());
        body.put("barsSource", barsSourceHint(bars.size(), md));
        body.put("bars", bars.stream().map(this::barDto).toList());
        body.put("book", book.map(this::bookDto).orElse(null));
        body.put("profile", buildProfileDto(bars));
        body.put("footprint", buildFootprintDto(instrument, md));
        body.put("paper", paperJournal.deskDto());
        body.put("checklistCompliance", com.moex.trinity.trend.ChecklistCompliance.deskDto());
        body.put("blockReason", null);
        TrendFairPaperLiveService fp = fairPaper.getIfAvailable();
        if (fp != null) {
            body.put("fairPaper", fp.snapshot());
        }

        if (bars.size() < 10) {
            body.put("signal", TrendSignal.from(null));
            body.put("actionable", false);
            body.put("summary", "Недостаточно баров для evaluate (нужен warmup / лента)");
            body.put("blockReason", "Недостаточно баров для evaluate (нужен warmup / лента)");
            body.put("plan", Map.of());
            body.put("structure", structureDto(TrendStructureSnapshot.empty("need more bars")));
            body.put("potentialPnlRub", null);
            body.put("situation", Map.of(
                    "posture", "SCANNING",
                    "inTrade", false,
                    "why", "Недостаточно баров для evaluate (нужен warmup / лента)",
                    "newsDisclaimer", "Живой RSS/EIA surprise в trend desk нет — только календарь событий."
            ));
            body.put("events", List.of());
            body.put("liveExecution", bridge.liveExecution());
            return body;
        }

        TrendBarSeries series = new TrendBarSeries(instrument, "M5", bars);
        TrendAccountContext account = new TrendAccountContext(equityRub, goLongRub, goShortRub, 1.0);
        Optional<TrendRobotPlan> plan = engine.evaluate(series, account);
        TrendRobotPlan p = plan.orElse(null);
        if (p != null && !bridge.autoExecution() && p.actionable() && tradeToasts != null) {
            tradeToasts.recordTrendSignal(p);
        }
        TrendStructureSnapshot structure = resolveStructure(series);
        body.put("signal", TrendSignal.from(p));
        body.put("actionable", p != null && p.actionable());
        String summary = p == null
                ? "no plan"
                : (p.rationale() != null && !p.rationale().isBlank()
                ? p.rationale()
                : (p.state() == null ? "no plan" : p.state().name()));
        body.put("summary", summary);
        if (p != null && !p.actionable()) {
            body.put("blockReason", summary);
        } else if (p == null) {
            body.put("blockReason", "no plan");
        } else {
            body.put("blockReason", null);
        }
        body.put("plan", p == null ? Map.of() : summarizePlan(p));
        body.put("structure", structureDto(structure));
        body.put("potentialPnlRub", p == null || tradeToasts == null ? null : tradeToasts.estimateTp1Potential(p));
        body.put("dayLossBlocks", engine.dayLossBlockCount());
        body.put("realizedDayPnlRub", engine.realizedDayPnlRub());
        body.put("setupsToday", engine.setupsTodayCount());
        Map<String, Object> manage = new LinkedHashMap<>();
        engine.lastManageAdvice().ifPresentOrElse(a -> {
            manage.put("stop", finiteOrNull(a.stop()));
            manage.put("movedToBe", a.movedToBe());
            manage.put("trailing", a.trailing());
            manage.put("tp1Touched", a.tp1Touched());
            manage.put("stopQty", a.stopQty());
            manage.put("note", a.note());
        }, () -> {
            // Preview §12 advice from last close if we have an actionable armed plan with levels
            if (p != null && p.grid() != null && !bars.isEmpty()
                    && Double.isFinite(p.stopLossPrice()) && Double.isFinite(p.tp1Price())) {
                var a = com.moex.trinity.trend.TrendPositionManager.update(
                        p.buy(),
                        p.grid().averagePrice(),
                        p.stopLossPrice(),
                        p.tp1Price(),
                        bars.get(bars.size() - 1).close(),
                        0.01,
                        20,
                        p.grid().totalQty(),
                        1.0 / 3.0,
                        false
                );
                manage.put("stop", finiteOrNull(a.stop()));
                manage.put("movedToBe", a.movedToBe());
                manage.put("trailing", a.trailing());
                manage.put("tp1Touched", a.tp1Touched());
                manage.put("stopQty", a.stopQty());
                manage.put("note", a.note() + " (preview)");
            }
        });
        body.put("manage", manage);
        try {
            body.put("situation", buildSituation(instrument, bars, book.orElse(null), p, structure, summary));
            body.put("events", eventCalendar.deskEvents(
                    bars.isEmpty() ? java.time.LocalDateTime.now(MSK) : bars.get(bars.size() - 1).time(),
                    instrument, 36, 7 * 24));
        } catch (Exception ex) {
            log.warn("desk situation/events failed: {}", ex.toString());
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("posture", "SCANNING");
            fallback.put("inTrade", false);
            fallback.put("why", summary);
            fallback.put("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
            fallback.put("newsDisclaimer",
                    "Живой RSS/EIA surprise в trend desk нет — только календарь событий + реакция цены (прокси).");
            body.put("situation", fallback);
            body.put("events", List.of());
        }
        body.put("liveExecution", bridge.liveExecution());
        return body;
    }

    private Map<String, Object> buildSituation(
            String instrument,
            List<TrendBar> bars,
            DomBook book,
            TrendRobotPlan p,
            TrendStructureSnapshot structure,
            String summary
    ) {
        Map<String, Object> s = new LinkedHashMap<>();
        TrendRobotState eng = engine.state();
        String engName = eng == null ? "SCAN" : eng.name();
        String planState = p == null || p.state() == null ? engName : p.state().name();
        boolean inPosition = eng == TrendRobotState.IN_POSITION || eng == TrendRobotState.MANAGE;
        boolean workingOrders = eng == TrendRobotState.WORKING_ORDERS
                || "WORKING_ORDERS".equals(planState);
        boolean armed = p != null && p.actionable()
                && (planState.startsWith("ARMED") || workingOrders);
        boolean blocked = "NO_TRADE".equals(planState) || eng == TrendRobotState.NO_TRADE;

        String posture;
        if (inPosition) {
            posture = "IN_TRADE";
        } else if (workingOrders || armed) {
            posture = "WAITING_FILL";
        } else if ("ZONE_READY".equals(planState)) {
            posture = "WATCHING_ZONE";
        } else if (blocked) {
            posture = "NOT_IN_TRADE";
        } else {
            posture = "SCANNING";
        }

        s.put("posture", posture);
        s.put("inTrade", inPosition);
        s.put("waitingFill", "WAITING_FILL".equals(posture));
        s.put("engineState", engName);
        s.put("planState", planState);
        s.put("delivery", deliveryLabel());
        s.put("liveExecution", bridge.liveExecution());
        s.put("autoExecution", bridge.autoExecution());
        TrendFairPaperLiveService fp = fairPaper.getIfAvailable();
        if (fp != null) {
            s.put("fairPaper", fp.snapshot());
        }
        s.put("setupsToday", engine.setupsTodayCount());
        s.put("kickCountToday", engine.kickCountToday());
        s.put("dayLossBlocks", engine.dayLossBlockCount());
        s.put("realizedDayPnlRub", engine.realizedDayPnlRub());
        s.put("maxSetupsPerDay", settings.maxSetupsPerDay());
        s.put("maxDayLossRub", settings.maxDayLossRub());

        String why;
        if (inPosition) {
            why = "Робот в позиции (manage §12). Следите за BE/trail и TP1.";
            if (p != null && p.rationale() != null) {
                why = "В сделке по сетапу: " + p.rationale();
            }
        } else if ("WAITING_FILL".equals(posture)) {
            why = summary == null || summary.isBlank()
                    ? "Fair-paper: лимитки / сетап в работе — ждём касание сетки."
                    : summary;
            if (bridge.autoExecution() && !bridge.liveExecution()) {
                why = "Fair-paper live · " + why;
            }
        } else if (blocked) {
            why = summary == null || summary.isBlank() ? "Новых входов нет." : summary;
        } else if ("WATCHING_ZONE".equals(posture)) {
            why = summary == null || summary.isBlank()
                    ? "Зона готова — ждём bounce/retest подтверждение."
                    : summary;
        } else {
            why = summary == null || summary.isBlank() ? "Сканирование структуры." : summary;
        }
        s.put("why", why);
        s.put("blockReason", blocked ? why : null);

        java.time.LocalDateTime now = bars.isEmpty()
                ? java.time.LocalDateTime.now(MSK)
                : bars.get(bars.size() - 1).time();
        String sessionBlock = TrendSessionEdge.blockReason(now, settings);
        s.put("sessionOpen", settings.tradeSessionOpen());
        s.put("sessionClose", settings.tradeSessionClose());
        s.put("sessionTradable", sessionBlock == null);
        s.put("sessionBlock", sessionBlock);

        String eventBlock = eventCalendar.blockReason(now, instrument);
        s.put("eventBlackout", eventBlock != null);
        s.put("eventBlock", eventBlock);

        double dayMove = BrMacroBias.dayMovePoints(
                bars, now, settings.tradeSessionOpen(), settings.instrument().pointSize());
        s.put("dayMovePoints", Double.isFinite(dayMove) ? Math.round(dayMove) : null);
        s.put("htf", structure == null ? "FLAT" : structure.htf());
        s.put("bias", structure == null ? null : structure.bias());
        s.put("structureNote", structure == null ? null : structure.note());
        s.put("marketState", structure == null ? null : structure.marketState());

        if (book != null) {
            List<DomBook.DomLevel> bids = book.bids() == null ? List.of() : book.bids();
            List<DomBook.DomLevel> asks = book.asks() == null ? List.of() : book.asks();
            if (!bids.isEmpty() && !asks.isEmpty()) {
                double bidQ = bids.stream().limit(5).mapToDouble(l -> l.quantityLots()).sum();
                double askQ = asks.stream().limit(5).mapToDouble(l -> l.quantityLots()).sum();
                s.put("domBidLots5", bidQ);
                s.put("domAskLots5", askQ);
                s.put("domSkew", bidQ - askQ);
                s.put("domAsOf", book.asOf() == null ? null : book.asOf().toString());
            }
        }

        engine.activeLock().ifPresent(lock -> {
            Map<String, Object> lk = new LinkedHashMap<>();
            lk.put("low", lock.zoneLow());
            lk.put("high", lock.zoneHigh());
            lk.put("buy", lock.buy());
            lk.put("mode", lock.mode() == null ? null : lock.mode().name());
            lk.put("mid", lock.mid());
            s.put("activeLock", lk);
        });

        if (p != null && p.grid() != null) {
            Map<String, Object> levels = new LinkedHashMap<>();
            levels.put("entry", finiteOrNull(p.grid().averagePrice()));
            levels.put("stop", finiteOrNull(p.stopLossPrice()));
            levels.put("tp1", finiteOrNull(p.tp1Price()));
            levels.put("tp2", finiteOrNull(p.tp2Price()));
            levels.put("qty", p.grid().totalQty());
            levels.put("side", p.buy() ? "BUY" : "SELL");
            levels.put("mode", p.mode() == null ? null : p.mode().name());
            s.put("setupLevels", levels);
        }

        s.put("asOf", now.toString());
        s.put("newsDisclaimer",
                "Живой RSS/EIA surprise в trend desk нет — только календарь событий + реакция цены (прокси).");
        return s;
    }

    private TrendStructureSnapshot resolveStructure(TrendBarSeries series) {
        if (researchService == null || researchService.playbooks().isEmpty()) {
            return TrendStructureSnapshot.empty("no playbook");
        }
        TrendPlaybook pb = researchService.playbooks().get(0);
        try {
            return pb.structure(series);
        } catch (Exception ex) {
            log.debug("structure failed: {}", ex.getMessage());
            return TrendStructureSnapshot.empty("structure error: " + ex.getMessage());
        }
    }

    private Map<String, Object> structureDto(TrendStructureSnapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (s == null) {
            return m;
        }
        m.put("lookbackBars", s.lookbackBars());
        m.put("lookbackHigh", finiteOrNull(s.lookbackHigh()));
        m.put("lookbackLow", finiteOrNull(s.lookbackLow()));
        m.put("historicalHigh", finiteOrNull(s.historicalHigh()));
        m.put("historicalLow", finiteOrNull(s.historicalLow()));
        m.put("previousZeroPoint", finiteOrNull(s.previousZeroPoint()));
        m.put("zeroPointBroken", s.zeroPointBroken());
        m.put("topBrokenHeld", s.topBrokenHeld());
        m.put("bottomBrokenHeld", s.bottomBrokenHeld());
        m.put("swingHighs", s.swingHighs());
        m.put("swingLows", s.swingLows());
        m.put("htf", s.htf());
        m.put("bias", s.bias());
        m.put("note", s.note());
        m.put("zoneTop", zoneMap(s.zoneTop()));
        m.put("zoneBottom", zoneMap(s.zoneBottom()));
        // legacy single zone for older UI snippets
        m.put("zone", zoneMap(s.zone()));
        m.put("marketState", s.marketState());
        List<Map<String, Object>> levels = new ArrayList<>();
        if (s.checklistLevels() != null) {
            for (var l : s.checklistLevels()) {
                Map<String, Object> lm = new LinkedHashMap<>();
                lm.put("price", l.price());
                lm.put("role", l.role());
                lm.put("source", l.source());
                lm.put("preferBuy", l.preferBuy());
                lm.put("rangeLow", l.rangeLow());
                lm.put("rangeHigh", l.rangeHigh());
                lm.put("brokenHeld", l.brokenHeld());
                levels.add(lm);
            }
        }
        m.put("checklistLevels", levels);
        return m;
    }

    private static Map<String, Object> zoneMap(TrendStructureSnapshot.Zone z) {
        if (z == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("low", z.low());
        m.put("high", z.high());
        m.put("mid", z.mid());
        m.put("source", z.source());
        m.put("widthPoints", z.widthPoints());
        m.put("validForEntry", z.validForEntry());
        m.put("role", z.role());
        return m;
    }

    private static Double finiteOrNull(double v) {
        return Double.isFinite(v) ? v : null;
    }

    private List<TrendBar> loadBars(String instrument, MarketDataResearchService md) {
        LocalDate today = LocalDate.now(MSK);
        List<TrendBar> tapeBars = List.of();
        try {
            TapeToM5Aggregator agg = new TapeToM5Aggregator(md == null ? null : md.archive());
            tapeBars = agg.loadRecentM5(instrument, today);
        } catch (Exception ex) {
            log.debug("Tape M5 load failed: {}", ex.getMessage());
        }

        // Always pull ISS warmup for ≥2 prior days — never return tape-only "today" chart
        List<TrendBar> issBars = List.of();
        try {
            issBars = IssFuturesM1Client.fetchM5Warmup(
                    instrument, today.minusDays(DESK_HISTORY_DAYS), today);
        } catch (Exception ex) {
            log.debug("ISS M5 warmup failed: {}", ex.getMessage());
        }

        if (issBars.isEmpty()) {
            return tapeBars;
        }
        if (tapeBars.isEmpty()) {
            return issBars;
        }
        return mergePreferTape(issBars, tapeBars);
    }

    private List<Map<String, Object>> buildProfileDto(List<TrendBar> bars) {
        if (bars == null || bars.isEmpty()) {
            return List.of();
        }
        // Horizontal VAP over ~current-trend window (~6h M5) for desk readability
        var vap = new com.moex.trinity.trend.VolumeAtPriceBuilder(
                com.moex.trinity.trend.TrendInstrumentSpec.brDefaults());
        int maxBars = Math.min(bars.size(), 72);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var lvl : vap.profileLevels(bars, maxBars)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("price", lvl.price());
            m.put("volume", Math.round(lvl.volume()));
            m.put("strength", Math.round(lvl.strength() * 1000) / 1000.0);
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> buildFootprintDto(String instrument, MarketDataResearchService md) {
        try {
            TapeToM5Aggregator agg = new TapeToM5Aggregator(md == null ? null : md.archive());
            var prints = agg.loadRecentPrints(instrument, LocalDate.now(MSK));
            if (prints.isEmpty() && md != null && md.liveReady() && md.feed() != null) {
                // live buffer fallback
                var live = md.feed().recentTrades(instrument);
                if (live != null && !live.isEmpty()) {
                    prints = live;
                }
            }
            var fp = new com.moex.trinity.trend.FootprintAggregator(0.01);
            // Full session depth so desk can pin footprint on any visible M5 (not only last 8)
            return com.moex.trinity.trend.FootprintAggregator.toDto(fp.build(prints, 288));
        } catch (Exception ex) {
            log.debug("footprint build failed: {}", ex.getMessage());
            return List.of();
        }
    }

    /** ISS base, overwrite overlapping buckets with tape (broker prints win). */
    static List<TrendBar> mergePreferTape(List<TrendBar> iss, List<TrendBar> tape) {
        TreeMap<java.time.LocalDateTime, TrendBar> map = new TreeMap<>();
        for (TrendBar b : iss) {
            if (b != null && b.time() != null) {
                map.put(b.time(), b);
            }
        }
        for (TrendBar b : tape) {
            if (b != null && b.time() != null) {
                map.put(b.time(), b);
            }
        }
        return new ArrayList<>(map.values());
    }

    private String barsSourceHint(int n, MarketDataResearchService md) {
        if (n <= 0) {
            return "empty";
        }
        if (md != null && md.liveReady()) {
            return "tape+iss";
        }
        return "archive/iss";
    }

    private Map<String, Object> barDto(TrendBar b) {
        Map<String, Object> m = new LinkedHashMap<>();
        // Moscow wall clock with explicit offset for chart JS
        m.put("time", b.time() == null ? null : b.time().toString() + "+03:00");
        m.put("open", b.open());
        m.put("high", b.high());
        m.put("low", b.low());
        m.put("close", b.close());
        m.put("volume", b.volume());
        return m;
    }

    private Map<String, Object> bookDto(DomBook book) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instrumentId", book.instrumentId());
        m.put("depth", book.depth());
        m.put("asOf", book.asOf() == null ? null : book.asOf().toString());
        m.put("consistent", book.consistent());
        m.put("bids", book.bids() == null ? List.of() : book.bids().stream().limit(50)
                .map(l -> Map.of("p", l.price(), "q", l.quantityLots())).toList());
        m.put("asks", book.asks() == null ? List.of() : book.asks().stream().limit(50)
                .map(l -> Map.of("p", l.price(), "q", l.quantityLots())).toList());
        m.put("tapeByPrice", tapeByPrice(instrumentFromBook(book), mdFromContext()));
        return m;
    }

    private String instrumentFromBook(DomBook book) {
        return book == null || book.instrumentId() == null || book.instrumentId().isBlank()
                ? configuredInstrument
                : book.instrumentId();
    }

    /** Best-effort marketdata handle for tape aggregation (may be null). */
    private MarketDataResearchService mdFromContext() {
        return marketData.getIfAvailable();
    }

    /**
     * Session / recent tape rolled up by price — buy/sell aggressor lots for DOM footprint column.
     */
    public static Map<String, Map<String, Long>> aggregateTapeByPrice(List<com.moex.trinity.marketdata.TradePrint> prints, double pointSize) {
        Map<String, long[]> raw = new LinkedHashMap<>();
        if (prints == null || prints.isEmpty()) {
            return Map.of();
        }
        double ps = pointSize > 0 ? pointSize : 0.01;
        for (com.moex.trinity.marketdata.TradePrint p : prints) {
            if (p == null || !(p.price() > 0) || p.quantityLots() <= 0) {
                continue;
            }
            long bucket = Math.round(p.price() / ps);
            String key = String.format(java.util.Locale.ROOT, "%.2f", bucket * ps);
            long[] bs = raw.computeIfAbsent(key, k -> new long[2]);
            if (p.side() == com.moex.trinity.marketdata.TradePrint.TradeSide.SELL) {
                bs[1] += p.quantityLots();
            } else {
                bs[0] += p.quantityLots();
            }
        }
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> e : raw.entrySet()) {
            long buy = e.getValue()[0];
            long sell = e.getValue()[1];
            Map<String, Long> row = new LinkedHashMap<>();
            row.put("buy", buy);
            row.put("sell", sell);
            row.put("total", buy + sell);
            row.put("delta", buy - sell);
            out.put(e.getKey(), row);
        }
        return out;
    }

    private Map<String, Map<String, Long>> tapeByPrice(String instrument, MarketDataResearchService md) {
        try {
            List<com.moex.trinity.marketdata.TradePrint> prints = List.of();
            if (md != null && md.feed() != null) {
                var live = md.feed().recentTrades(instrument);
                if (live != null && !live.isEmpty()) {
                    prints = live;
                }
            }
            if (prints.isEmpty()) {
                TapeToM5Aggregator agg = new TapeToM5Aggregator(md == null ? null : md.archive());
                prints = agg.loadRecentPrints(instrument, LocalDate.now(MSK));
            }
            // Prefer last ~90 minutes for DOM reactivity
            Instant cutoff = Instant.now().minusSeconds(90 * 60L);
            List<com.moex.trinity.marketdata.TradePrint> recent = new ArrayList<>();
            for (com.moex.trinity.marketdata.TradePrint p : prints) {
                if (p != null && p.time() != null && !p.time().isBefore(cutoff)) {
                    recent.add(p);
                }
            }
            if (recent.isEmpty()) {
                recent = prints;
            }
            return aggregateTapeByPrice(recent, 0.01);
        } catch (Exception ex) {
            log.debug("tapeByPrice failed: {}", ex.getMessage());
            return Map.of();
        }
    }

    private static Map<String, Object> summarizePlan(TrendRobotPlan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("playbookId", p.playbookId());
        m.put("instrument", p.instrument());
        m.put("state", p.state() == null ? null : p.state().name());
        m.put("mode", p.mode() == null ? null : p.mode().name());
        m.put("side", p.actionable() ? (p.buy() ? "BUY" : "SELL") : "NONE");
        m.put("buy", p.buy());
        m.put("actionable", p.actionable());
        m.put("rationale", p.rationale());
        m.put("stopLoss", finiteOrNull(p.stopLossPrice()));
        m.put("tp1", finiteOrNull(p.tp1Price()));
        m.put("tp2", finiteOrNull(p.tp2Price()));
        m.put("tp1Fraction", p.tp1Fraction());
        MergedVolumeRange r = p.range();
        if (r != null) {
            m.put("range", Map.of(
                    "low", r.low(),
                    "high", r.high(),
                    "valid", r.validForEntry()
            ));
        }
        LimitGridPlan g = p.grid();
        if (g != null) {
            m.put("entry", g.averagePrice());
            m.put("grid", Map.of(
                    "avg", g.averagePrice(),
                    "totalQty", g.totalQty(),
                    "near", g.nearPrice(),
                    "mid", g.midPrice(),
                    "far", g.farPrice()
            ));
        }
        return m;
    }
}
