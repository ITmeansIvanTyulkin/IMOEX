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
import com.moex.trinity.trend.TrendPlaybook;
import com.moex.trinity.trend.TrendResearchService;
import com.moex.trinity.trend.TrendRobotEngine;
import com.moex.trinity.trend.TrendRobotPlan;
import com.moex.trinity.trend.TrendSignal;
import com.moex.trinity.trend.TrendStructureSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

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
        this.marketData = marketData;
        this.configuredInstrument = instrument == null || instrument.isBlank() ? "BRU6" : instrument.trim();
        this.equityRub = equityRub > 0 ? equityRub : 100_000;
        this.goLongRub = goLongRub > 0 ? goLongRub : 15_000;
        this.goShortRub = goShortRub > 0 ? goShortRub : 16_000;
    }

    public Map<String, Object> desk() {
        String instrument = configuredInstrument;
        MarketDataResearchService md = marketData.getIfAvailable();
        if (md != null && md.defaultInstrument() != null && !md.defaultInstrument().isBlank()) {
            instrument = md.defaultInstrument();
        }

        List<TrendBar> bars = loadBars(instrument, md);
        Optional<DomBook> book = md == null ? Optional.empty() : md.resolveBook(instrument);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instrument", instrument);
        body.put("timeframe", "M5");
        body.put("delivery", bridge.autoExecution() ? "AUTO_JOURNAL" : "SIGNAL_ONLY");
        body.put("autoExecution", bridge.autoExecution());
        body.put("engineState", engine.state().name());
        body.put("barCount", bars.size());
        body.put("barsSource", barsSourceHint(bars.size(), md));
        body.put("bars", bars.stream().map(this::barDto).toList());
        body.put("book", book.map(this::bookDto).orElse(null));
        body.put("profile", buildProfileDto(bars));
        body.put("footprint", buildFootprintDto(instrument, md));
        body.put("paper", paperJournal.deskDto());

        if (bars.size() < 10) {
            body.put("signal", TrendSignal.from(null));
            body.put("actionable", false);
            body.put("summary", "Недостаточно баров для evaluate (нужен warmup / лента)");
            body.put("plan", Map.of());
            body.put("structure", structureDto(TrendStructureSnapshot.empty("need more bars")));
            body.put("potentialPnlRub", null);
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
        body.put("summary", p == null ? "no plan" : (p.rationale() == null ? p.state() : p.rationale()));
        body.put("plan", p == null ? Map.of() : summarizePlan(p));
        body.put("structure", structureDto(structure));
        body.put("potentialPnlRub", p == null || tradeToasts == null ? null : tradeToasts.estimateTp1Potential(p));
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
        return body;
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
            return com.moex.trinity.trend.FootprintAggregator.toDto(fp.build(prints, 24));
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
        m.put("bids", book.bids().stream().limit(15).map(l -> Map.of("p", l.price(), "q", l.quantityLots())).toList());
        m.put("asks", book.asks().stream().limit(15).map(l -> Map.of("p", l.price(), "q", l.quantityLots())).toList());
        return m;
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
