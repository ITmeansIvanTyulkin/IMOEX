package com.moex.trinity.trend;

import com.moex.trinity.marketdata.DomBook;
import com.moex.trinity.marketdata.MarketDataFeed;
import com.moex.trinity.marketdata.TradePrint;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Playbook #1 — «Уровни + профиль рынка» for BR M5 (Exclusive checklist + hardenings).
 * Zones prefer broker tape VAP when marketdata is streaming; else bar OHLC proxy.
 */
public class LevelsProfileBrPlaybook implements TrendPlaybook {

    public static final String ID = "levels-profile-br-m5";

    private final TrendPlaybookSettings settings;
    private final VolumeAtPriceBuilder vap;
    private final MarketDataFeed marketData;

    public LevelsProfileBrPlaybook() {
        this(TrendPlaybookSettings.brDefaults(), null);
    }

    public LevelsProfileBrPlaybook(TrendPlaybookSettings settings) {
        this(settings, null);
    }

    public LevelsProfileBrPlaybook(TrendPlaybookSettings settings, MarketDataFeed marketData) {
        this.settings = settings == null ? TrendPlaybookSettings.brDefaults() : settings;
        this.vap = new VolumeAtPriceBuilder(
                this.settings.instrument(),
                this.settings.allowZonePad(),
                this.settings.minHvnBands()
        );
        this.marketData = marketData;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Уровни + профиль рынка (BR M5)";
    }

    @Override
    public String whenApplicable() {
        return "INTRADAY BR M5: A-setup bounce + HTF asymmetry; tape zones when marketdata live";
    }

    @Override
    public Optional<TrendRobotPlan> evaluate(TrendBarSeries series, TrendAccountContext account) {
        if (series == null || series.isEmpty() || series.size() < 30) {
            return Optional.of(noTrade(series, "need ≥30 M5 bars"));
        }
        TrendInstrumentSpec spec = settings.instrument();
        List<TrendBar> bars = series.bars();
        int lookback = Math.min(bars.size(), Math.max(60, settings.levelLookbackBars()));
        List<TrendBar> window = bars.subList(bars.size() - lookback, bars.size());

        HtfTrend htf = HtfTrend.resolve(window, series.last().time(), settings);
        if (htf.isFlat()) {
            return Optional.of(noTrade(series, "HTF FLAT — no trade until clear UP/DOWN"));
        }

        TrendBias bias = detectBias(window);
        if (bias == TrendBias.NONE) {
            return Optional.of(noTrade(series, "no clear trend line / HH-HL or LH-LL structure"));
        }

        SessionBias session = SessionBias.fromBars(
                window, settings.sessionBiasBars(), settings.sessionBiasMinPoints(), spec.pointSize());

        List<Double> levels = findSwingLevels(window);
        if (levels.isEmpty()) {
            return Optional.of(noTrade(series, "no swing levels in 1–2 day lookback"));
        }

        double lastClose = series.last().close();
        Optional<ZoneCandidate> zone = pickBestZone(series.instrument(), window, levels, lastClose, bias);
        if (zone.isEmpty()) {
            return Optional.of(noTrade(series, "no A-quality volume zone (≥"
                    + settings.minTouchCount() + " bounces / HVN, no pad)"));
        }

        ZoneCandidate z = zone.get();
        ModeDecision md = decideMode(window, z.range(), bias, lastClose);
        if (md.mode() == null) {
            return Optional.of(plan(
                    series,
                    TrendRobotState.ZONE_READY,
                    null,
                    bias == TrendBias.UP,
                    z.range(),
                    null,
                    Double.NaN, Double.NaN, Double.NaN,
                    "Zone ready at " + fmt(z.range()) + " — waiting: " + md.reason(),
                    List.of(md.reason(), "htf=" + htf, "session=" + session, "zoneSrc=" + z.source())
            ));
        }

        if (settings.aSetupBounceOnly() && md.mode() == TrendTradeMode.RETEST) {
            return Optional.of(plan(
                    series, TrendRobotState.ZONE_READY, null, md.buy(), z.range(), null,
                    Double.NaN, Double.NaN, Double.NaN,
                    "A-setup: RETEST skipped — waiting bounce confirm",
                    List.of("htf=" + htf, "aSetupBounceOnly")
            ));
        }

        boolean buy = md.buy();
        boolean against = htf.againstTrend(buy);

        if (against && settings.counterTrendBounceOnly() && md.mode() == TrendTradeMode.RETEST) {
            return Optional.of(noTrade(series,
                    "HTF " + htf + " counter — RETEST blocked (bounce only)"));
        }
        if ((settings.requireBounceConfirm() || (against && settings.counterTrendRequireConfirm()))
                && md.mode() == TrendTradeMode.BOUNCE
                && !bounceConfirmed(window, z.range(), buy)) {
            return Optional.of(plan(
                    series, TrendRobotState.ZONE_READY, null, buy, z.range(), null,
                    Double.NaN, Double.NaN, Double.NaN,
                    "A-setup bounce — waiting rejection confirm",
                    List.of("htf=" + htf)
            ));
        }
        if (against && md.mode() == TrendTradeMode.RETEST) {
            double maxDist = settings.counterTrendMaxDistancePoints();
            if (!retestEntryAllowed(window, z.range(), buy, maxDist, spec.pointSize())) {
                return Optional.of(plan(
                        series, TrendRobotState.ZONE_READY, null, buy, z.range(), null,
                        Double.NaN, Double.NaN, Double.NaN,
                        "HTF counter retest — need touch / within " + (int) maxDist + " pts",
                        List.of("htf=" + htf)
                ));
            }
        }

        TrendAccountContext acct = account == null
                ? TrendAccountContext.of(100_000, 15_000, 16_000, settings.maxRiskPctEquity())
                : new TrendAccountContext(
                account.equityRub(),
                account.goLongRub(),
                account.goShortRub(),
                account.maxRiskPctEquity() > 0 ? account.maxRiskPctEquity() : settings.maxRiskPctEquity()
        );

        int contracts = TrendPositionSizer.sizeContracts(acct, spec, buy, spec.stopPoints());
        double initFrac = settings.initialSizeFraction() > 0 ? settings.initialSizeFraction() : 0.4;
        contracts = Math.max(1, (int) Math.round(contracts * initFrac));
        if (against) {
            double frac = settings.counterTrendSizeFraction() > 0 ? settings.counterTrendSizeFraction() : 0.6;
            contracts = Math.max(1, (int) Math.round(contracts * frac));
        }
        if (contracts < 1) {
            return Optional.of(noTrade(series, "size=0 after GO/risk / initial fraction"));
        }

        LimitGridPlan grid = LimitGridBuilder.build(z.range(), buy, contracts, settings.gridStyle());
        double avg = grid.averagePrice();
        double stopPtsPrice = spec.stopPoints() * spec.pointSize();
        double rrFloor = against
                ? Math.max(settings.minRewardRisk(), settings.counterTrendMinRewardRisk())
                : Math.max(1.0, settings.minRewardRisk());
        double minTp1 = stopPtsPrice * rrFloor;
        double tp1PtsPrice = Math.max(spec.tp1Points() * spec.pointSize(), minTp1);
        double stop;
        double tp1;
        double tp2;
        if (buy) {
            stop = Math.max(avg - stopPtsPrice, z.range().low() - stopPtsPrice);
            tp1 = avg + tp1PtsPrice;
            double runnerMult = md.mode() == TrendTradeMode.BOUNCE ? 2.0 : 1.5;
            tp2 = avg + stopPtsPrice * runnerMult;
            if (tp2 < tp1) {
                tp2 = tp1 + stopPtsPrice * 0.5;
            }
        } else {
            stop = Math.min(avg + stopPtsPrice, z.range().high() + stopPtsPrice);
            tp1 = avg - tp1PtsPrice;
            double runnerMult = md.mode() == TrendTradeMode.BOUNCE ? 2.0 : 1.5;
            tp2 = avg - stopPtsPrice * runnerMult;
            if (tp2 > tp1) {
                tp2 = tp1 - stopPtsPrice * 0.5;
            }
        }

        TrendRobotState state = md.mode() == TrendTradeMode.BOUNCE
                ? TrendRobotState.ARMED_BOUNCE
                : TrendRobotState.ARMED_RETEST;

        String tilt = against ? "COUNTER×" + settings.counterTrendSizeFraction() : "WITH";
        String domNote = domSoftNote(series.instrument(), z.range());
        String rationale = String.format(Locale.ROOT,
                "%s %s | htf=%s %s | zone=%s [%s] (%.0f pts) | size×%.2f until BE | grid=%s %d | SL=%.2f TP1=%.2f TP2=%.2f R≥%.1f%s",
                md.mode(), buy ? "BUY" : "SELL", htf, tilt,
                z.source(), fmt(z.range()), z.range().widthPoints(spec.pointSize()),
                initFrac, settings.gridStyle(), grid.totalQty(), stop, tp1, tp2, rrFloor,
                domNote.isEmpty() ? "" : " | " + domNote);

        return Optional.of(plan(
                series, state, md.mode(), buy, z.range(), grid, stop, tp1, tp2, rationale,
                List.of(md.reason(), "htf=" + htf, tilt, "zoneSrc=" + z.source(),
                        "session=" + session, "initialSize=" + initFrac)
        ));
    }

    private Optional<ZoneCandidate> pickBestZone(
            String instrument,
            List<TrendBar> window,
            List<Double> levels,
            double lastClose,
            TrendBias bias
    ) {
        List<ZoneCandidate> ok = new ArrayList<>();
        List<double[]> tape = tapePrints(instrument);
        boolean useTape = settings.preferMarketDataZones() && tape != null && !tape.isEmpty();
        for (double level : levels) {
            MergedVolumeRange range;
            String src;
            if (useTape) {
                range = vap.buildAroundLevelFromPrints(tape, level, settings.minTouchCount());
                src = "TAPE";
                if (!range.validForEntry()) {
                    range = vap.buildAroundLevel(
                            window, level, settings.touchLookback(),
                            settings.candlesPerTouch(), settings.minTouchCount());
                    src = "BARS";
                }
            } else {
                range = vap.buildAroundLevel(
                        window, level, settings.touchLookback(),
                        settings.candlesPerTouch(), settings.minTouchCount());
                src = "BARS";
            }
            if (range.validForEntry()) {
                ok.add(new ZoneCandidate(level, range, src));
            }
        }
        if (ok.isEmpty()) {
            return Optional.empty();
        }
        Comparator<ZoneCandidate> byDist = Comparator.comparingDouble(c -> Math.abs(c.range().mid() - lastClose));
        if (bias == TrendBias.UP) {
            return ok.stream()
                    .filter(c -> c.range().high() <= lastClose + settings.instrument().pointSize() * 5
                            || lastClose >= c.range().low())
                    .min(byDist)
                    .or(() -> ok.stream().min(byDist));
        }
        return ok.stream()
                .filter(c -> c.range().low() >= lastClose - settings.instrument().pointSize() * 5
                        || lastClose <= c.range().high())
                .min(byDist)
                .or(() -> ok.stream().min(byDist));
    }

    private List<double[]> tapePrints(String instrument) {
        if (marketData == null || !settings.preferMarketDataZones()) {
            return List.of();
        }
        if (!marketData.streaming()) {
            return List.of();
        }
        List<TradePrint> trades = marketData.recentTrades(instrument);
        if (trades == null || trades.isEmpty()) {
            return List.of();
        }
        List<double[]> out = new ArrayList<>(trades.size());
        for (TradePrint t : trades) {
            if (t != null && t.quantityLots() > 0) {
                out.add(new double[]{t.price(), t.quantityLots()});
            }
        }
        return out;
    }

    private String domSoftNote(String instrument, MergedVolumeRange range) {
        if (marketData == null || range == null) {
            return "";
        }
        Optional<DomBook> book = marketData.latestBook(instrument);
        if (book.isEmpty() || !book.get().consistent()) {
            return "";
        }
        DomBook b = book.get();
        long bidLots = b.bids().stream()
                .filter(l -> l.price() >= range.low() && l.price() <= range.high())
                .mapToLong(DomBook.DomLevel::quantityLots).sum();
        long askLots = b.asks().stream()
                .filter(l -> l.price() >= range.low() && l.price() <= range.high())
                .mapToLong(DomBook.DomLevel::quantityLots).sum();
        if (bidLots + askLots <= 0) {
            return "DOM@zone empty";
        }
        return String.format(Locale.ROOT, "DOM@zone bid=%d ask=%d", bidLots, askLots);
    }

    private ModeDecision decideMode(List<TrendBar> window, MergedVolumeRange range, TrendBias bias, double lastClose) {
        boolean above = lastClose > range.high();
        boolean below = lastClose < range.low();
        boolean inside = !above && !below;

        if (bias == TrendBias.UP) {
            if (below || (inside && lastClose <= range.mid())) {
                if (settings.requireBounceConfirm() && !bounceConfirmed(window, range, true)) {
                    return new ModeDecision(null, true, "UP bounce: waiting closed rejection candle in zone");
                }
                return new ModeDecision(TrendTradeMode.BOUNCE, true, "UP bias: bounce long (confirmed)");
            }
            if (breakHoldSatisfied(window, range, true, Math.max(1, settings.confirmBarsAfterBreak()))) {
                if (!retestEntryAllowed(window, range, true,
                        settings.retestArmMaxDistancePoints(), settings.instrument().pointSize())) {
                    return new ModeDecision(null, true,
                            "UP break held — waiting retest touch / within "
                                    + (int) settings.retestArmMaxDistancePoints() + " pts of zone");
                }
                return new ModeDecision(TrendTradeMode.RETEST, true, "UP bias: break+hold+retest long");
            }
            if (above) {
                return new ModeDecision(null, true, "broke above range — waiting confirm bars");
            }
            return new ModeDecision(null, true, "price mid-range — no bounce/retest yet");
        }

        if (above || (inside && lastClose >= range.mid())) {
            if (settings.requireBounceConfirm() && !bounceConfirmed(window, range, false)) {
                return new ModeDecision(null, false, "DOWN bounce: waiting closed rejection candle in zone");
            }
            return new ModeDecision(TrendTradeMode.BOUNCE, false, "DOWN bias: bounce short (confirmed)");
        }
        if (breakHoldSatisfied(window, range, false, Math.max(1, settings.confirmBarsAfterBreak()))) {
            if (!retestEntryAllowed(window, range, false,
                    settings.retestArmMaxDistancePoints(), settings.instrument().pointSize())) {
                return new ModeDecision(null, false,
                        "DOWN break held — waiting retest touch / within "
                                + (int) settings.retestArmMaxDistancePoints() + " pts of zone");
            }
            return new ModeDecision(TrendTradeMode.RETEST, false, "DOWN bias: break+hold+retest short");
        }
        if (below) {
            return new ModeDecision(null, false, "broke below range — waiting confirm bars");
        }
        return new ModeDecision(null, false, "price mid-range — no bounce/retest yet");
    }

    /** Closed candle wicked into zone and closed back in trade direction. */
    static boolean bounceConfirmed(List<TrendBar> window, MergedVolumeRange range, boolean buy) {
        if (window == null || window.isEmpty() || range == null) {
            return false;
        }
        TrendBar last = window.get(window.size() - 1);
        if (buy) {
            boolean poked = last.low() <= range.high() && last.low() >= range.low() - (range.width() * 0.5);
            boolean rejected = last.close() >= range.mid();
            return poked && rejected;
        }
        boolean poked = last.high() >= range.low() && last.high() <= range.high() + (range.width() * 0.5);
        boolean rejected = last.close() <= range.mid();
        return poked && rejected;
    }

    /**
     * Break+hold already happened on bars before the last one
     * ({@code need} consecutive fully-outside bars).
     */
    static boolean breakHoldSatisfied(List<TrendBar> window, MergedVolumeRange range, boolean breakUp, int need) {
        if (window == null || range == null || window.size() < need + 1) {
            return false;
        }
        int outsideRun = 0;
        int limit = window.size() - 1; // hold must be established before current bar
        for (int i = 0; i < limit; i++) {
            TrendBar b = window.get(i);
            boolean outside = breakUp ? b.low() > range.high() : b.high() < range.low();
            if (outside) {
                outsideRun++;
                if (outsideRun >= need) {
                    return true;
                }
            } else {
                outsideRun = 0;
            }
        }
        return false;
    }

    /**
     * RETEST arm only when last bar touches the zone, or close is within {@code maxDistPoints}
     * of the near edge (approach). No more "arm while still far outside".
     */
    static boolean retestEntryAllowed(
            List<TrendBar> window,
            MergedVolumeRange range,
            boolean breakUp,
            double maxDistPoints,
            double pointSize
    ) {
        if (window == null || window.isEmpty() || range == null || pointSize <= 0) {
            return false;
        }
        TrendBar last = window.get(window.size() - 1);
        boolean touches = last.low() <= range.high() && last.high() >= range.low();
        if (touches) {
            return true;
        }
        if (maxDistPoints <= 0) {
            return false;
        }
        if (breakUp) {
            // approaching from above
            if (last.close() <= range.high()) {
                return false;
            }
            return (last.close() - range.high()) / pointSize <= maxDistPoints;
        }
        // approaching from below
        if (last.close() >= range.low()) {
            return false;
        }
        return (range.low() - last.close()) / pointSize <= maxDistPoints;
    }

    /** @deprecated use {@link #breakHoldSatisfied} + {@link #retestEntryAllowed} */
    static boolean retestArmed(List<TrendBar> window, MergedVolumeRange range, boolean breakUp) {
        return breakHoldSatisfied(window, range, breakUp, 2)
                && retestEntryAllowed(window, range, breakUp, 10, 0.01);
    }

    private boolean confirmedBreak(List<TrendBar> window, MergedVolumeRange range, boolean breakUp) {
        return confirmedBreakStatic(window, range, breakUp, Math.max(1, settings.confirmBarsAfterBreak()));
    }

    static boolean confirmedBreakStatic(List<TrendBar> window, MergedVolumeRange range, boolean breakUp, int need) {
        if (window == null || window.size() < need + 1) {
            return false;
        }
        int lastTouch = -1;
        for (int i = 0; i < window.size(); i++) {
            TrendBar b = window.get(i);
            if (b.low() <= range.high() && b.high() >= range.low()) {
                lastTouch = i;
            }
        }
        if (lastTouch < 0 || window.size() - 1 - lastTouch < need) {
            return false;
        }
        for (int i = lastTouch + 1; i < window.size(); i++) {
            TrendBar b = window.get(i);
            if (breakUp) {
                if (b.low() <= range.high()) {
                    return false;
                }
            } else if (b.high() >= range.low()) {
                return false;
            }
        }
        return true;
    }

    static TrendBias detectBias(List<TrendBar> window) {
        if (window == null || window.size() < 20) {
            return TrendBias.NONE;
        }
        List<Swing> swings = swings(window, 3);
        if (swings.size() < 4) {
            // fallback: linear slope of closes
            double first = window.get(0).close();
            double last = window.get(window.size() - 1).close();
            double movePts = Math.abs(last - first) / 0.01;
            if (movePts < 30) {
                return TrendBias.NONE;
            }
            return last > first ? TrendBias.UP : TrendBias.DOWN;
        }
        List<Swing> highs = swings.stream().filter(s -> s.high).toList();
        List<Swing> lows = swings.stream().filter(s -> !s.high).toList();
        if (highs.size() >= 2 && lows.size() >= 2) {
            Swing h1 = highs.get(highs.size() - 2);
            Swing h2 = highs.get(highs.size() - 1);
            Swing l1 = lows.get(lows.size() - 2);
            Swing l2 = lows.get(lows.size() - 1);
            if (h2.price > h1.price && l2.price > l1.price) {
                return TrendBias.UP;
            }
            if (h2.price < h1.price && l2.price < l1.price) {
                return TrendBias.DOWN;
            }
        }
        double first = window.get(0).close();
        double last = window.get(window.size() - 1).close();
        if (Math.abs(last - first) / 0.01 < 30) {
            return TrendBias.NONE;
        }
        return last > first ? TrendBias.UP : TrendBias.DOWN;
    }

    static List<Double> findSwingLevels(List<TrendBar> window) {
        List<Swing> swings = swings(window, 3);
        List<Double> levels = new ArrayList<>();
        for (Swing s : swings) {
            levels.add(s.price);
        }
        // Also session high/low of window
        double hi = window.stream().mapToDouble(TrendBar::high).max().orElse(Double.NaN);
        double lo = window.stream().mapToDouble(TrendBar::low).min().orElse(Double.NaN);
        if (!Double.isNaN(hi)) {
            levels.add(hi);
        }
        if (!Double.isNaN(lo)) {
            levels.add(lo);
        }
        // Dedupe within 5 points
        levels.sort(Double::compareTo);
        List<Double> uniq = new ArrayList<>();
        for (double lv : levels) {
            if (uniq.isEmpty() || Math.abs(uniq.get(uniq.size() - 1) - lv) >= 0.05) {
                uniq.add(lv);
            }
        }
        // Keep up to 4 nearest-to-end meaningful levels (checklist 2–4)
        if (uniq.size() <= 4) {
            return uniq;
        }
        double last = window.get(window.size() - 1).close();
        return uniq.stream()
                .sorted(Comparator.comparingDouble(l -> Math.abs(l - last)))
                .limit(4)
                .sorted()
                .toList();
    }

    private static List<Swing> swings(List<TrendBar> window, int pivot) {
        List<Swing> out = new ArrayList<>();
        for (int i = pivot; i < window.size() - pivot; i++) {
            double h = window.get(i).high();
            double l = window.get(i).low();
            boolean isHigh = true;
            boolean isLow = true;
            for (int j = i - pivot; j <= i + pivot; j++) {
                if (j == i) {
                    continue;
                }
                if (window.get(j).high() >= h) {
                    isHigh = false;
                }
                if (window.get(j).low() <= l) {
                    isLow = false;
                }
            }
            if (isHigh) {
                out.add(new Swing(i, h, true));
            }
            if (isLow) {
                out.add(new Swing(i, l, false));
            }
        }
        return out;
    }

    private TrendRobotPlan noTrade(TrendBarSeries series, String reason) {
        return plan(series, TrendRobotState.NO_TRADE, null, true, null, null,
                Double.NaN, Double.NaN, Double.NaN, reason, List.of(reason));
    }

    private TrendRobotPlan plan(
            TrendBarSeries series,
            TrendRobotState state,
            TrendTradeMode mode,
            boolean buy,
            MergedVolumeRange range,
            LimitGridPlan grid,
            double stop,
            double tp1,
            double tp2,
            String rationale,
            List<String> notes
    ) {
        return new TrendRobotPlan(
                id(),
                series == null ? "BR" : series.instrument(),
                series == null ? "M5" : series.timeframe(),
                LocalDateTime.now(),
                state,
                mode,
                buy,
                range,
                grid,
                stop,
                tp1,
                tp2,
                settings.tp1Fraction(),
                rationale,
                notes
        );
    }

    private static String fmt(MergedVolumeRange r) {
        return String.format(Locale.ROOT, "%.2f–%.2f", r.low(), r.high());
    }

    enum TrendBias {UP, DOWN, NONE}

    private record Swing(int index, double price, boolean high) {
    }

    private record ZoneCandidate(double level, MergedVolumeRange range, String source) {
    }

    private record ModeDecision(TrendTradeMode mode, boolean buy, String reason) {
    }
}
