package com.moex.trinity.trend;

import com.moex.trinity.marketdata.DomBook;
import com.moex.trinity.marketdata.MarketDataFeed;
import com.moex.trinity.marketdata.TradePrint;

import java.nio.file.Path;
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
    /** Checklist: TOP/BOT fixed for the MSK trading day once established. */
    private final DayZoneLock dayZones;
    /** Extension: full 2–4 level set locked for the day. */
    private final ChecklistDayLock checklistDayLock;

    public LevelsProfileBrPlaybook() {
        this(TrendPlaybookSettings.brDefaults(), null, null);
    }

    public LevelsProfileBrPlaybook(TrendPlaybookSettings settings) {
        this(settings, null, null);
    }

    public LevelsProfileBrPlaybook(TrendPlaybookSettings settings, MarketDataFeed marketData) {
        this(settings, marketData, null);
    }

    public LevelsProfileBrPlaybook(TrendPlaybookSettings settings, MarketDataFeed marketData, Path dayZoneFile) {
        this.settings = settings == null ? TrendPlaybookSettings.brDefaults() : settings;
        this.vap = new VolumeAtPriceBuilder(
                this.settings.instrument(),
                this.settings.allowZonePad(),
                this.settings.minHvnBands(),
                this.settings.minShelfVolume()
        );
        this.marketData = marketData;
        this.dayZones = new DayZoneLock(dayZoneFile);
        this.checklistDayLock = new ChecklistDayLock();
    }

    /** Test/reset hook. */
    public void clearDayZoneLock() {
        dayZones.clear();
        checklistDayLock.clear();
    }

    public record KickResult(
            boolean cleared,
            String reason,
            boolean hadTop,
            boolean hadBottom,
            int levelsCleared
    ) {
    }

    /**
     * Kick: wipe day shelves + checklist level lock so robot re-discovers structure at max aggression.
     * Does not touch paper statement / journal.
     */
    public KickResult kickHard(String reason) {
        DayZoneLock.Snapshot before = dayZones.get();
        int levelsBefore = checklistDayLock.get() == null ? 0 : checklistDayLock.get().levels().size();
        dayZones.clear();
        checklistDayLock.clear();
        return new KickResult(
                true,
                reason == null ? "kickHard" : reason,
                before != null && before.hasTop(),
                before != null && before.hasBottom(),
                levelsBefore
        );
    }

    public DayZoneLock.Snapshot dayZoneSnapshot() {
        return dayZones.get();
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
        return "INTRADAY BR M5: Exclusive checklist (bounce+retest) + day-lock/prior/session/HTF hardenings";
    }

    /**
     * Desk overlay: checklist §3–8 structure (hist, zero, current HI/LO, dual zones).
     * Entry gates stay in {@link #evaluate}.
     */
    @Override
    public TrendStructureSnapshot structure(TrendBarSeries series) {
        if (series == null || series.isEmpty() || series.size() < 10) {
            return TrendStructureSnapshot.empty("need more M5 bars for structure");
        }
        TrendInstrumentSpec spec = settings.instrument();
        List<TrendBar> bars = series.bars();
        int lookback = Math.min(bars.size(), Math.max(60, settings.levelLookbackBars()));
        List<TrendBar> window = bars.subList(bars.size() - lookback, bars.size());

        // §3: historical extremes of all available M5
        double[] hist = historicalExtremes(bars);
        double historicalHigh = hist[0];
        double historicalLow = hist[1];

        // §5 live extremes (candidates); day-lock freezes HI/LO + zones once set
        double[] extremes = currentTrendExtremes(window);
        double liveHigh = extremes[0];
        double liveLow = extremes[1];

        HtfTrend htf = HtfTrend.resolve(window, series.last().time(), settings);
        MarketState marketState = ChecklistStructure.detectMarketState(
                window, settings.sessionBiasMinPoints(), spec.pointSize());
        TrendBias bias = marketState.toBias();
        if (bias == TrendBias.NONE) {
            bias = detectBias(window);
        }

        Optional<ZoneCandidate> topLive = Optional.empty();
        Optional<ZoneCandidate> botLive = Optional.empty();
        if (Double.isFinite(liveHigh) && Double.isFinite(liveLow) && liveHigh > liveLow) {
            topLive = buildZoneAtAnchor(series.instrument(), window, liveHigh, true, true);
            botLive = buildZoneAtAnchor(series.instrument(), window, liveLow, true, false);
        }
        ZonePair lockCands = dayLockCandidates(series, liveHigh, liveLow, topLive, botLive);
        java.time.LocalDate day = series.last().time().toLocalDate();
        DayZoneLock.Snapshot locked = resolveDayZones(
                series.last().time(),
                day,
                liveHigh,
                liveLow,
                lockCands.topRange(),
                lockCands.topSource(),
                lockCands.bottomRange(),
                lockCands.bottomSource()
        );

        // Display / trade anchors = locked day ranges; before 09:40 show live candidates only
        double trendHigh;
        double trendLow;
        TrendStructureSnapshot.Zone zoneTop;
        TrendStructureSnapshot.Zone zoneBottom;
        if (locked.hasTop() || locked.hasBottom()) {
            trendHigh = Double.isFinite(locked.trendHigh()) ? locked.trendHigh() : liveHigh;
            trendLow = Double.isFinite(locked.trendLow()) ? locked.trendLow() : liveLow;
            zoneTop = locked.hasTop()
                    ? toZoneDto(new ZoneCandidate(trendHigh, locked.top(),
                    locked.topSource() == null ? "DAY" : locked.topSource() + "+DAY"), spec, "TOP")
                    : null;
            zoneBottom = locked.hasBottom()
                    ? toZoneDto(new ZoneCandidate(trendLow, locked.bottom(),
                    locked.bottomSource() == null ? "DAY" : locked.bottomSource() + "+DAY"), spec, "BOTTOM")
                    : null;
        } else {
            trendHigh = liveHigh;
            trendLow = liveLow;
            zoneTop = topLive.map(c -> toZoneDto(c, spec, "TOP")).orElse(null);
            zoneBottom = botLive.map(c -> toZoneDto(c, spec, "BOTTOM")).orElse(null);
        }

        // §4: previous-trend zero point
        double zero = previousTrendZeroPoint(window, bias, trendHigh, trendLow);
        boolean zeroBroken = zeroPointBroken(bias, trendHigh, trendLow, zero, spec.pointSize());

        List<Swing> swings = swings(window, 3);
        List<Double> swingHighs = swings.stream()
                .filter(Swing::high)
                .sorted(Comparator.comparingDouble((Swing s) -> Math.abs(s.price - series.last().close())))
                .limit(3)
                .map(s -> s.price)
                .sorted()
                .toList();
        List<Double> swingLows = swings.stream()
                .filter(s -> !s.high())
                .sorted(Comparator.comparingDouble((Swing s) -> Math.abs(s.price - series.last().close())))
                .limit(3)
                .map(s -> s.price)
                .sorted()
                .toList();

        Optional<ZoneCandidate> topCand = locked.hasTop()
                ? Optional.of(new ZoneCandidate(trendHigh, locked.top(), locked.topSource()))
                : topLive;
        Optional<ZoneCandidate> botCand = locked.hasBottom()
                ? Optional.of(new ZoneCandidate(trendLow, locked.bottom(), locked.bottomSource()))
                : botLive;

        // §8 status against locked day zones
        boolean topBrokenHeld = topCand
                .map(c -> breakHoldSatisfied(window, c.range(), true, Math.max(1, settings.confirmBarsAfterBreak())))
                .orElse(false);
        boolean bottomBrokenHeld = botCand
                .map(c -> breakHoldSatisfied(window, c.range(), false, Math.max(1, settings.confirmBarsAfterBreak())))
                .orElse(false);

        String note = checklistNote(htf, bias, zoneTop, zoneBottom, zero, zeroBroken, topBrokenHeld, bottomBrokenHeld);
        List<ChecklistLevel> levelsLive = buildProfiledLevels(
                series.instrument(), window, marketState, trendHigh, trendLow, zero, series.last().close());
        List<ChecklistLevel> levels = checklistDayLock.absorb(day, levelsLive);
        note = ChecklistStructure.majorityNote(levels, marketState) + ". " + note;
        if (locked.hasTop() || locked.hasBottom()) {
            String srcHint = "";
            if (locked.topSource() != null && locked.topSource().contains("PRIOR")) {
                srcHint = "TOP с вчерашнего объёма. ";
            }
            if (locked.bottomSource() != null && locked.bottomSource().contains("PRIOR")) {
                srcHint += "BOT с вчерашнего объёма. ";
            }
            note = "Зоны дня зафиксированы (не двигаем до завтра). " + srcHint + note;
        } else if (!TrendSessionEdge.isTradable(series.last().time(), settings)) {
            note = "Day-lock после окна сессии (open+40м). Сейчас live-кандидаты. " + note;
        }
        List<TrendStructureSnapshot.LevelDto> levelDtos = new ArrayList<>();
        for (ChecklistLevel l : levels) {
            levelDtos.add(new TrendStructureSnapshot.LevelDto(
                    l.price(), l.role(), l.source(), l.preferBuy(),
                    l.hasValidRange() ? l.range().low() : null,
                    l.hasValidRange() ? l.range().high() : null,
                    l.brokenHeld()));
        }
        return new TrendStructureSnapshot(
                lookback,
                trendHigh,
                trendLow,
                historicalHigh,
                historicalLow,
                zero,
                zeroBroken,
                topBrokenHeld,
                bottomBrokenHeld,
                swingHighs,
                swingLows,
                zoneTop,
                zoneBottom,
                htf.name(),
                bias.name(),
                note,
                marketState.name(),
                levelDtos
        );
    }

    /**
     * Day-lock only inside the tradable session window (after open+N, before close−M).
     * Pre-open / first N minutes: do not freeze thin overnight/early shelves.
     * Broken prior shelves (new HI/LO beyond zone) are cleared so a fresh volume range can lock.
     */
    private DayZoneLock.Snapshot resolveDayZones(
            java.time.LocalDateTime now,
            java.time.LocalDate day,
            double trendHigh,
            double trendLow,
            MergedVolumeRange topCand,
            String topSrc,
            MergedVolumeRange bottomCand,
            String bottomSrc
    ) {
        DayZoneLock.Snapshot cur = dayZones.get();
        if (cur != null && cur.day() != null && !cur.day().equals(day)) {
            dayZones.clear();
            cur = null;
        }
        if (TrendSessionEdge.isTradable(now, settings)) {
            double breakPts = Math.max(settings.instrument().zoneMaxPoints(), 20);
            DayZoneLock.Snapshot before = dayZones.get();
            dayZones.clearBrokenShelves(trendHigh, trendLow, breakPts, settings.instrument().pointSize());
            DayZoneLock.Snapshot afterClear = dayZones.get();
            // If TOP/BOT shelf was broken, drop locked checklist HI/LO so new extreme re-profiles
            if (before != null && afterClear != null) {
                if (before.hasTop() && !afterClear.hasTop()) {
                    checklistDayLock.dropRoles("TREND_HI");
                }
                if (before.hasBottom() && !afterClear.hasBottom()) {
                    checklistDayLock.dropRoles("TREND_LO");
                }
            }
            // Anti-thin: never day-lock soft / invalid shelves (desk may still draw them)
            MergedVolumeRange topLock = lockableShelf(topCand, topSrc);
            String topLockSrc = topLock == null ? null : topSrc;
            MergedVolumeRange botLock = lockableShelf(bottomCand, bottomSrc);
            String botLockSrc = botLock == null ? null : bottomSrc;
            return dayZones.absorb(day, trendHigh, trendLow, topLock, topLockSrc, botLock, botLockSrc);
        }
        if (cur != null && day.equals(cur.day())) {
            return cur;
        }
        // Ephemeral unlocked snapshot for desk — not persisted
        return new DayZoneLock.Snapshot(day, trendHigh, trendLow, null, null, null, null);
    }

    /**
     * Checklist: when placing today's TOP/BOT, prefer previous day's volume-traded shelves.
     * Live shelf wins only if prior is missing or today's extreme already broke through prior.
     */
    private ZonePair dayLockCandidates(
            TrendBarSeries series,
            double liveHigh,
            double liveLow,
            Optional<ZoneCandidate> topLive,
            Optional<ZoneCandidate> botLive
    ) {
        PriorDayZones prior = priorDayVolumeZones(series);
        MergedVolumeRange top = chooseShelf(prior.top(), topLive.map(ZoneCandidate::range).orElse(null),
                liveHigh, true);
        String topSrc = sourceFor(top, prior.top(), topLive.map(ZoneCandidate::source).orElse(null), true);
        MergedVolumeRange bot = chooseShelf(prior.bottom(), botLive.map(ZoneCandidate::range).orElse(null),
                liveLow, false);
        String botSrc = sourceFor(bot, prior.bottom(), botLive.map(ZoneCandidate::source).orElse(null), false);
        return new ZonePair(top, topSrc, bot, botSrc);
    }

    private MergedVolumeRange chooseShelf(
            MergedVolumeRange prior,
            MergedVolumeRange live,
            double liveExtreme,
            boolean atHigh
    ) {
        double point = settings.instrument().pointSize();
        double breakPts = Math.max(settings.instrument().zoneMaxPoints(), 20) * point;
        if (prior != null && prior.low() < prior.high()) {
            boolean broken = atHigh
                    ? Double.isFinite(liveExtreme) && liveExtreme > prior.high() + breakPts
                    : Double.isFinite(liveExtreme) && liveExtreme < prior.low() - breakPts;
            if (!broken) {
                return prior;
            }
        }
        if (live != null && live.low() < live.high()) {
            return live;
        }
        return prior;
    }

    private static String sourceFor(
            MergedVolumeRange chosen,
            MergedVolumeRange prior,
            String liveSrc,
            boolean atHigh
    ) {
        if (chosen == null) {
            return null;
        }
        if (prior != null && Math.abs(chosen.low() - prior.low()) < 1e-9
                && Math.abs(chosen.high() - prior.high()) < 1e-9) {
            return atHigh ? "PRIOR_DAY_TOP" : "PRIOR_DAY_BOT";
        }
        return liveSrc == null ? "BARS" : liveSrc;
    }

    /**
     * Volume TOP/BOT from the previous calendar day in the series (warmup + today).
     */
    PriorDayZones priorDayVolumeZones(TrendBarSeries series) {
        if (series == null || series.isEmpty()) {
            return PriorDayZones.empty();
        }
        java.time.LocalDate today = series.last().time().toLocalDate();
        java.time.LocalDate priorDay = today.minusDays(1);
        // skip weekend gap: walk back until we find bars
        List<TrendBar> priorBars = new ArrayList<>();
        for (int back = 1; back <= 4; back++) {
            java.time.LocalDate d = today.minusDays(back);
            priorBars = series.bars().stream()
                    .filter(b -> b.time().toLocalDate().equals(d))
                    .toList();
            if (!priorBars.isEmpty()) {
                priorDay = d;
                break;
            }
        }
        if (priorBars.isEmpty()) {
            return PriorDayZones.empty();
        }
        double priorHigh = priorBars.stream().mapToDouble(TrendBar::high).max().orElse(Double.NaN);
        double priorLow = priorBars.stream().mapToDouble(TrendBar::low).min().orElse(Double.NaN);
        Optional<ZoneCandidate> top = buildZoneAtAnchor(series.instrument(), priorBars, priorHigh, true, true);
        Optional<ZoneCandidate> bot = buildZoneAtAnchor(series.instrument(), priorBars, priorLow, true, false);
        return new PriorDayZones(
                priorDay,
                top.map(ZoneCandidate::range).orElse(null),
                bot.map(ZoneCandidate::range).orElse(null)
        );
    }

    private record ZonePair(
            MergedVolumeRange topRange,
            String topSource,
            MergedVolumeRange bottomRange,
            String bottomSource
    ) {
    }

    record PriorDayZones(java.time.LocalDate day, MergedVolumeRange top, MergedVolumeRange bottom) {
        static PriorDayZones empty() {
            return new PriorDayZones(null, null, null);
        }
    }

    private String checklistNote(
            HtfTrend htf,
            TrendBias bias,
            TrendStructureSnapshot.Zone zoneTop,
            TrendStructureSnapshot.Zone zoneBottom,
            double zero,
            boolean zeroBroken,
            boolean topBrokenHeld,
            boolean bottomBrokenHeld
    ) {
        StringBuilder sb = new StringBuilder();
        if (htf.isFlat()) {
            sb.append("HTF FLAT — bounce у day-locked TOP/BOT; RETEST после break+hold тоже по §7–8. ");
        }
        if (Double.isFinite(zero)) {
            sb.append("§4 zero=").append(String.format(Locale.ROOT, "%.2f", zero))
                    .append(zeroBroken ? " (пробита)" : " (не пробита)").append(". ");
        }
        if (bias == TrendBias.UP) {
            sb.append(topBrokenHeld
                    ? "§8 TOP break+hold — можно RETEST верха или bounce низа. "
                    : "§8 пока нет пробоя TOP — торгуем только BOT bounce. ");
        } else if (bias == TrendBias.DOWN) {
            sb.append(bottomBrokenHeld
                    ? "§8 BOT break+hold — можно RETEST низа или bounce верха. "
                    : "§8 пока нет пробоя BOT — торгуем только TOP bounce. ");
        } else {
            if (topBrokenHeld) {
                sb.append("§7–8 TOP break+hold — RETEST long с верха. ");
            }
            if (bottomBrokenHeld) {
                sb.append("§7–8 BOT break+hold — RETEST short с низа. ");
            }
            if (!topBrokenHeld && !bottomBrokenHeld) {
                sb.append("§8 без пробоя — bounce между day-locked TOP/BOT. ");
            }
        }
        if (zoneTop == null && zoneBottom == null) {
            sb.append("Нет профиля у max/min текущего тренда.");
        } else if (zoneTop == null) {
            sb.append("Есть BOT; TOP пока нет.");
        } else if (zoneBottom == null) {
            sb.append("Есть TOP; BOT пока нет.");
        } else {
            sb.append("Два диапазона TOP+BOT (§6); торговля между ними.");
        }
        return sb.toString().trim();
    }

    /** §3: max/min of entire available series. */
    static double[] historicalExtremes(List<TrendBar> bars) {
        if (bars == null || bars.isEmpty()) {
            return new double[]{Double.NaN, Double.NaN};
        }
        double hi = bars.stream().mapToDouble(TrendBar::high).max().orElse(Double.NaN);
        double lo = bars.stream().mapToDouble(TrendBar::low).min().orElse(Double.NaN);
        return new double[]{hi, lo};
    }

    /**
     * Current-trend high/low for structure (checklist §5).
     * Uses a recent window (~session), not the full multi-day lookback spike.
     */
    double[] currentTrendExtremes(List<TrendBar> window) {
        if (window == null || window.isEmpty()) {
            return new double[]{Double.NaN, Double.NaN};
        }
        int n = Math.min(window.size(), Math.max(48, settings.sessionBiasBars() * 2));
        List<TrendBar> trend = window.subList(window.size() - n, window.size());
        double hi = trend.stream().mapToDouble(TrendBar::high).max().orElse(Double.NaN);
        double lo = trend.stream().mapToDouble(TrendBar::low).min().orElse(Double.NaN);
        return new double[]{hi, lo};
    }

    /**
     * §4: origin of the previous opposing trend.
     * UP → last swing high before current trendLow; DOWN → last swing low before trendHigh.
     */
    static double previousTrendZeroPoint(
            List<TrendBar> window,
            TrendBias bias,
            double trendHigh,
            double trendLow
    ) {
        if (window == null || window.isEmpty() || bias == TrendBias.NONE) {
            return Double.NaN;
        }
        List<Swing> sw = swings(window, 3);
        if (sw.isEmpty()) {
            return Double.NaN;
        }
        if (bias == TrendBias.UP) {
            // Find index of trendLow, then last swing high before that bar
            int loIdx = indexOfExtreme(window, trendLow, false);
            return sw.stream()
                    .filter(Swing::high)
                    .filter(s -> s.index() < loIdx || loIdx < 0)
                    .reduce((a, b) -> b)
                    .map(s -> s.price)
                    .orElse(Double.NaN);
        }
        if (bias == TrendBias.DOWN) {
            int hiIdx = indexOfExtreme(window, trendHigh, true);
            return sw.stream()
                    .filter(s -> !s.high())
                    .filter(s -> s.index() < hiIdx || hiIdx < 0)
                    .reduce((a, b) -> b)
                    .map(s -> s.price)
                    .orElse(Double.NaN);
        }
        return Double.NaN;
    }

    static boolean zeroPointBroken(
            TrendBias bias,
            double trendHigh,
            double trendLow,
            double zero,
            double pointSize
    ) {
        if (!Double.isFinite(zero) || !(pointSize > 0)) {
            return false;
        }
        double need = pointSize; // ≥1 point through zero
        if (bias == TrendBias.UP) {
            return Double.isFinite(trendHigh) && trendHigh >= zero + need;
        }
        if (bias == TrendBias.DOWN) {
            return Double.isFinite(trendLow) && trendLow <= zero - need;
        }
        return false;
    }

    private static int indexOfExtreme(List<TrendBar> window, double price, boolean high) {
        if (!Double.isFinite(price)) {
            return -1;
        }
        for (int i = window.size() - 1; i >= 0; i--) {
            TrendBar b = window.get(i);
            if (high && Math.abs(b.high() - price) < 1e-9) {
                return i;
            }
            if (!high && Math.abs(b.low() - price) < 1e-9) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Market profile on candles that touch {@code anchor} (HI or LO).
     * Checklist §6: 5–6 candles per bounce → merge into 15–20 pt range.
     *
     * @param structureSoft if true, draw with ≥1 touch cluster and soft pad for desk
     * @param atHigh        true → TOP (band under HI); false → BOT (band above LO)
     */
    private Optional<ZoneCandidate> buildZoneAtAnchor(
            String instrument,
            List<TrendBar> window,
            double anchor,
            boolean structureSoft,
            boolean atHigh
    ) {
        if (!Double.isFinite(anchor) || window == null || window.isEmpty()) {
            return Optional.empty();
        }
        int candlesPerTouch = Math.max(5, settings.candlesPerTouch()); // checklist 5–6
        int touchLookback = Math.max(3, settings.touchLookback());
        int minTouches = structureSoft ? 1 : settings.minTouchCount();

        List<double[]> tape = tapePrints(instrument);
        boolean useTape = settings.preferMarketDataZones() && tape != null && !tape.isEmpty();
        MergedVolumeRange range;
        String src;
        if (useTape) {
            range = vap.buildAroundLevelFromPrints(tape, anchor, minTouches);
            src = "TAPE";
            if (!range.validForEntry()) {
                range = vap.buildAroundLevel(
                        window, anchor, touchLookback, candlesPerTouch, minTouches);
                src = "BARS";
            }
        } else {
            range = vap.buildAroundLevel(
                    window, anchor, touchLookback, candlesPerTouch, minTouches);
            src = "BARS";
        }

        if (range.validForEntry()) {
            double maxDrift = settings.instrument().zoneMaxPoints() * settings.instrument().pointSize();
            // Must stay at the HI or LO — not a mid-channel shelf from candle bodies
            if (Math.abs(range.mid() - anchor) <= maxDrift) {
                return Optional.of(new ZoneCandidate(anchor, range, src));
            }
            // Clamp valid shelf toward the extreme if volume was found but POC drifted
            if (structureSoft) {
                double width = Math.min(
                        range.width(),
                        settings.instrument().zoneMaxPoints() * settings.instrument().pointSize());
                width = Math.max(width,
                        settings.instrument().zoneMinPoints() * settings.instrument().pointSize());
                double low = atHigh ? anchor - width : anchor;
                double high = atHigh ? anchor : anchor + width;
                MergedVolumeRange clamped = new MergedVolumeRange(
                        low, high, range.totalVolume(), range.sourceBands(), true, null);
                return Optional.of(new ZoneCandidate(anchor, clamped, src + "+CLAMP"));
            }
            return Optional.empty();
        }

        if (!structureSoft) {
            return Optional.empty();
        }

        // Desk: still show a 15 pt band at the extreme if candles touched it
        double tol = settings.instrument().pointSize() * 2;
        boolean anyTouch = window.stream().anyMatch(b -> b.valid() && b.touches(anchor, tol));
        if (!anyTouch) {
            return Optional.empty();
        }
        double width = settings.instrument().zoneMinPoints() * settings.instrument().pointSize();
        double low = atHigh ? anchor - width : anchor;
        double high = atHigh ? anchor : anchor + width;
        MergedVolumeRange soft = new MergedVolumeRange(
                low,
                high,
                range.totalVolume(),
                List.of(),
                false,
                range.invalidReason() != null ? range.invalidReason() : "structure soft zone"
        );
        return Optional.of(new ZoneCandidate(anchor, soft, src + "+SOFT"));
    }

    private TrendStructureSnapshot.Zone toZoneDto(ZoneCandidate z, TrendInstrumentSpec spec, String role) {
        MergedVolumeRange r = z.range();
        return new TrendStructureSnapshot.Zone(
                r.low(),
                r.high(),
                r.mid(),
                z.source(),
                r.widthPoints(spec.pointSize()),
                r.validForEntry(),
                role
        );
    }

    private List<ZoneCandidate> collectValidZones(
            String instrument,
            List<TrendBar> window,
            List<Double> levels
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
        return ok;
    }

    @Override
    public Optional<TrendRobotPlan> evaluate(TrendBarSeries series, TrendAccountContext account) {
        // §1–2
        if (series == null || series.isEmpty() || series.size() < 30) {
            return Optional.of(noTrade(series, "need ≥30 M5 bars"));
        }
        String tf = series.timeframe() == null ? "" : series.timeframe().toUpperCase(Locale.ROOT);
        if (!tf.contains("M5") && !tf.equals("5")) {
            return Optional.of(noTrade(series, "§2: timeframe must be M5 (got " + series.timeframe() + ")"));
        }
        String inst = series.instrument() == null ? "" : series.instrument().toUpperCase(Locale.ROOT);
        if (!inst.startsWith("BR")) {
            return Optional.of(noTrade(series, "§1: playbook #1 is BR futures only (got " + series.instrument() + ")"));
        }

        TrendInstrumentSpec spec = settings.instrument();
        List<TrendBar> bars = series.bars();
        int lookback = Math.min(bars.size(), Math.max(60, settings.levelLookbackBars()));
        List<TrendBar> window = bars.subList(bars.size() - lookback, bars.size());

        HtfTrend htf = HtfTrend.resolve(window, series.last().time(), settings);
        MarketState marketState = ChecklistStructure.detectMarketState(
                window, settings.sessionBiasMinPoints(), spec.pointSize());
        TrendBias bias = marketState.toBias();
        if (bias == TrendBias.NONE) {
            bias = detectBias(window);
        }

        SessionBias session = SessionBias.fromBars(
                window, settings.sessionBiasBars(), settings.sessionBiasMinPoints(), spec.pointSize());

        double lastClose = series.last().close();
        double[] extremes = currentTrendExtremes(window);
        double liveHigh = extremes[0];
        double liveLow = extremes[1];

        // Extension: day-lock TOP/BOT shelves (prior-day seed)
        Optional<ZoneCandidate> topLive = buildZoneAtAnchor(series.instrument(), window, liveHigh, false, true);
        Optional<ZoneCandidate> botLive = buildZoneAtAnchor(series.instrument(), window, liveLow, false, false);
        if (topLive.isEmpty()) {
            topLive = buildZoneAtAnchor(series.instrument(), window, liveHigh, true, true);
        }
        if (botLive.isEmpty()) {
            botLive = buildZoneAtAnchor(series.instrument(), window, liveLow, true, false);
        }
        ZonePair lockCands = dayLockCandidates(series, liveHigh, liveLow, topLive, botLive);
        java.time.LocalDate day = series.last().time().toLocalDate();
        DayZoneLock.Snapshot locked = resolveDayZones(
                series.last().time(),
                day, liveHigh, liveLow,
                lockCands.topRange(),
                lockCands.topSource(),
                lockCands.bottomRange(),
                lockCands.bottomSource()
        );
        double trendHigh = Double.isFinite(locked.trendHigh()) && (locked.hasTop() || locked.hasBottom())
                ? locked.trendHigh() : liveHigh;
        double trendLow = Double.isFinite(locked.trendLow()) && (locked.hasTop() || locked.hasBottom())
                ? locked.trendLow() : liveLow;

        // §4 zero
        double zero = previousTrendZeroPoint(window, bias, trendHigh, trendLow);
        if (Double.isFinite(zero) && marketState.isTrend()
                && !zeroPointBroken(bias, trendHigh, trendLow, zero, spec.pointSize())) {
            return Optional.of(noTrade(series, String.format(Locale.ROOT,
                    "§4 zero point %.2f not broken by current trend — no trade", zero)));
        }

        // §4–§7: 2–4 levels + profile ranges; day-lock level set
        List<ChecklistLevel> levelsLive = buildProfiledLevels(
                series.instrument(), window, marketState, trendHigh, trendLow, zero, lastClose);
        // Extension: stamp day-locked TOP/BOT shelves onto TREND_HI / TREND_LO
        levelsLive = applyDayLockedShelves(levelsLive, locked, trendHigh, trendLow, window);
        List<ChecklistLevel> levels = checklistDayLock.absorb(day, levelsLive);
        if (levels.isEmpty()) {
            return Optional.of(noTrade(series, "§4: no 2–4 TA levels with valid §6–7 profile"));
        }

        ChecklistLevel active = ChecklistStructure.pickActive(
                levels, lastClose, marketState,
                settings.retestArmMaxDistancePoints(), spec.pointSize());
        if (active == null || !active.hasValidRange()) {
            return Optional.of(noTrade(series, "§6–7: no valid profiled range on active level"));
        }

        // Unstick: if chosen level is broken+held but price is far (not approaching), drop it and re-pick
        {
            MergedVolumeRange ar = active.range();
            boolean bu = breakHoldSatisfied(window, ar, true, Math.max(1, settings.confirmBarsAfterBreak()));
            boolean bd = breakHoldSatisfied(window, ar, false, Math.max(1, settings.confirmBarsAfterBreak()));
            double distPts = Math.abs(lastClose - ar.mid()) / spec.pointSize();
            double arm = settings.retestArmMaxDistancePoints();
            if ((bu || bd) && distPts > arm) {
                List<ChecklistLevel> rest = new ArrayList<>();
                for (ChecklistLevel l : levels) {
                    if (Math.abs(l.price() - active.price()) > spec.pointSize()) {
                        rest.add(l);
                    }
                }
                ChecklistLevel alt = ChecklistStructure.pickActive(
                        rest, lastClose, marketState, arm, spec.pointSize());
                if (alt != null && alt.hasValidRange()) {
                    active = alt;
                } else {
                    // Hard kick shelves once — new extreme must re-lock
                    dayZones.forceClearShelves(liveHigh, liveLow);
                    checklistDayLock.dropRoles("TREND_HI", "TREND_LO", "ACCUM");
                    levelsLive = buildProfiledLevels(
                            series.instrument(), window, marketState, liveHigh, liveLow, zero, lastClose);
                    levelsLive = applyDayLockedShelves(levelsLive, dayZones.get(), liveHigh, liveLow, window);
                    levels = checklistDayLock.absorb(day, levelsLive);
                    active = ChecklistStructure.pickActive(
                            levels, lastClose, marketState, arm, spec.pointSize());
                    if (active == null || !active.hasValidRange()) {
                        return Optional.of(noTrade(series, "§6–7: no valid profiled range after unstick"));
                    }
                }
            }
        }

        MergedVolumeRange range = active.range();
        boolean brokenUp = breakHoldSatisfied(window, range, true, Math.max(1, settings.confirmBarsAfterBreak()));
        boolean brokenDown = breakHoldSatisfied(window, range, false, Math.max(1, settings.confirmBarsAfterBreak()));
        active = active.withBrokenHeld(brokenUp || brokenDown);

        ModeDecision md = decideChecklistAtLevel(window, active, brokenUp, brokenDown);

        if (md.mode() == null) {
            return Optional.of(plan(
                    series,
                    TrendRobotState.ZONE_READY,
                    null,
                    active.preferBuy(),
                    range,
                    null,
                    Double.NaN, Double.NaN, Double.NaN,
                    "§" + (active.preferBuy() ? "14" : "14") + " level " + active.role()
                            + " " + fmt(range) + " — waiting: " + md.reason()
                            + " | " + ChecklistStructure.majorityNote(levels, marketState),
                    List.of(md.reason(), "htf=" + htf, "state=" + marketState, "session=" + session)
            ));
        }

        if (settings.aSetupBounceOnly() && md.mode() == TrendTradeMode.RETEST) {
            return Optional.of(plan(
                    series, TrendRobotState.ZONE_READY, null, md.buy(), range, null,
                    Double.NaN, Double.NaN, Double.NaN,
                    "a-setup-bounce-only=true — RETEST skipped (research cut)",
                    List.of("htf=" + htf)
            ));
        }

        // Operator style: skip noisy ACCUM POC; ZERO retest only with HTF (not knife noise)
        if (settings.preferStructuralEntries()) {
            String role = active.role() == null ? "" : active.role();
            if ("ACCUM".equals(role)) {
                return Optional.of(plan(
                        series, TrendRobotState.ZONE_READY, null, md.buy(), range, null,
                        Double.NaN, Double.NaN, Double.NaN,
                        "operator: ACCUM POC skipped — prefer day TOP/BOT / ZERO shelves",
                        List.of("htf=" + htf, "state=" + marketState)
                ));
            }
            // TOP shelf → prefer short bounce/retest down, not long into the high unless HTF-up continuation
            if ("TREND_HI".equals(role) && md.buy() && md.mode() == TrendTradeMode.RETEST
                    && htf != HtfTrend.UP) {
                return Optional.of(plan(
                        series, TrendRobotState.ZONE_READY, null, true, range, null,
                        Double.NaN, Double.NaN, Double.NaN,
                        "operator: TREND_HI long only with HTF UP continuation",
                        List.of("htf=" + htf)
                ));
            }
            // BOT shelf → prefer long; skip short into the low unless HTF-down continuation
            if ("TREND_LO".equals(role) && !md.buy() && md.mode() == TrendTradeMode.RETEST
                    && htf != HtfTrend.DOWN) {
                return Optional.of(plan(
                        series, TrendRobotState.ZONE_READY, null, false, range, null,
                        Double.NaN, Double.NaN, Double.NaN,
                        "operator: TREND_LO short only with HTF DOWN continuation",
                        List.of("htf=" + htf)
                ));
            }
        }

        boolean buy = md.buy();
        BrMacroBias macro = settings.macroBiasEnabled()
                ? BrMacroBias.resolve(
                window, series.last().time(), htf, marketState, settings,
                settings.macroMinDayMovePoints())
                : BrMacroBias.NEUTRAL;
        // Knife-catch only: no BUY into dump. TOP bounce SELL still allowed on rally days.
        if (macro.blocksBuy() && buy) {
            return Optional.of(noTrade(series,
                    "FA/macro " + macro + " — no BUY (knife-catch filter); day dump / HTF down"));
        }
        if (macro.blocksSell() && !buy && md.mode() == TrendTradeMode.RETEST
                && !"TREND_HI".equals(active.role())) {
            return Optional.of(noTrade(series,
                    "FA/macro " + macro + " — no mid/LO SELL into melt-up; wait TOP shelf bounce"));
        }

        // ZERO mid-retest only with HTF (operator: don't invent mean-reversion mid-noise)
        if (settings.preferStructuralEntries()
                && "ZERO".equals(active.role())
                && md.mode() == TrendTradeMode.RETEST
                && !htf.withTrend(buy)
                && !(md.buy() && brokenUp || !md.buy() && brokenDown)) {
            return Optional.of(plan(
                    series, TrendRobotState.ZONE_READY, null, buy, range, null,
                    Double.NaN, Double.NaN, Double.NaN,
                    "operator: ZERO RETEST only with HTF / break+hold continuation",
                    List.of("htf=" + htf)
            ));
        }

        boolean against = htf.againstTrend(buy);
        boolean checklistRetestContinuation = md.mode() == TrendTradeMode.RETEST
                && ((buy && brokenUp) || (!buy && brokenDown));

        if (against && settings.counterTrendBounceOnly() && md.mode() == TrendTradeMode.RETEST
                && !checklistRetestContinuation) {
            return Optional.of(noTrade(series,
                    "HTF " + htf + " counter — RETEST blocked (bounce only)"));
        }
        if ((settings.requireBounceConfirm() || (against && settings.counterTrendRequireConfirm()))
                && md.mode() == TrendTradeMode.BOUNCE
                && !bounceConfirmed(window, range, buy)) {
            return Optional.of(plan(
                    series, TrendRobotState.ZONE_READY, null, buy, range, null,
                    Double.NaN, Double.NaN, Double.NaN,
                    "§14 bounce — waiting closed rejection confirm",
                    List.of("htf=" + htf, "state=" + marketState)
            ));
        }
        if (against && md.mode() == TrendTradeMode.RETEST && !checklistRetestContinuation) {
            double maxDist = settings.counterTrendMaxDistancePoints();
            if (!retestEntryAllowed(window, range, buy, maxDist, spec.pointSize())) {
                return Optional.of(plan(
                        series, TrendRobotState.ZONE_READY, null, buy, range, null,
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
        if (against && !checklistRetestContinuation) {
            double frac = settings.counterTrendSizeFraction() > 0 ? settings.counterTrendSizeFraction() : 0.6;
            contracts = Math.max(1, (int) Math.round(contracts * frac));
        }
        if (contracts < 1) {
            return Optional.of(noTrade(series, "size=0 after GO/risk / initial fraction"));
        }

        // §9 / §14 grid
        LimitGridPlan grid = LimitGridBuilder.build(range, buy, contracts, settings.gridStyle());
        double avg = grid.averagePrice();
        // §10 / §15 stop ≤ speculative; §12 / §17 TP1 = checklist pts
        double stopPtsPrice = spec.stopPoints() * spec.pointSize();
        double tp1PtsPrice = spec.tp1Points() * spec.pointSize();
        double rrFloor = (against && !checklistRetestContinuation)
                ? Math.max(settings.minRewardRisk(), settings.counterTrendMinRewardRisk())
                : Math.max(1.0, settings.minRewardRisk());
        tp1PtsPrice = Math.max(tp1PtsPrice, stopPtsPrice * Math.min(rrFloor, 1.0));
        // §13 RETEST ×1.5 / §18 BOUNCE ×2
        double runnerMult = md.mode() == TrendTradeMode.BOUNCE ? 2.0 : 1.5;
        double stop;
        double tp1;
        double tp2;
        if (buy) {
            stop = Math.max(avg - stopPtsPrice, range.low() - stopPtsPrice);
            // Cap stop distance at speculative stop
            if (avg - stop > stopPtsPrice) {
                stop = avg - stopPtsPrice;
            }
            tp1 = avg + tp1PtsPrice;
            tp2 = avg + stopPtsPrice * runnerMult;
            if (tp2 < tp1) {
                tp2 = tp1 + stopPtsPrice * 0.5;
            }
        } else {
            stop = Math.min(avg + stopPtsPrice, range.high() + stopPtsPrice);
            if (stop - avg > stopPtsPrice) {
                stop = avg + stopPtsPrice;
            }
            tp1 = avg - tp1PtsPrice;
            tp2 = avg - stopPtsPrice * runnerMult;
            if (tp2 > tp1) {
                tp2 = tp1 - stopPtsPrice * 0.5;
            }
        }

        TrendRobotState state = md.mode() == TrendTradeMode.BOUNCE
                ? TrendRobotState.ARMED_BOUNCE
                : TrendRobotState.ARMED_RETEST;

        String tilt = against ? "COUNTER×" + settings.counterTrendSizeFraction() : "WITH";
        if (macro != BrMacroBias.NEUTRAL) {
            tilt = tilt + " macro=" + macro;
        }
        String domNote = domSoftNote(series.instrument(), range);
        String runnerTag = md.mode() == TrendTradeMode.BOUNCE ? "§18×2" : "§13×1.5";
        String rationale = String.format(Locale.ROOT,
                "%s %s | %s | htf=%s %s | level=%s %s [%s] (%.0f pts) | size×%.2f until BE | grid=%s %d | SL=%.2f TP1=%.2f(%s) TP2=%.2f %s | stopQty→⅔ after TP1%s",
                md.mode(), buy ? "BUY" : "SELL",
                ChecklistStructure.majorityNote(levels, marketState),
                htf, tilt,
                active.role(), active.source(), fmt(range), range.widthPoints(spec.pointSize()),
                initFrac, settings.gridStyle(), grid.totalQty(), stop, tp1,
                "§12/17 " + (int) spec.tp1Points() + "pts",
                tp2, runnerTag,
                domNote.isEmpty() ? "" : " | " + domNote);

        return Optional.of(plan(
                series, state, md.mode(), buy, range, grid, stop, tp1, tp2, rationale,
                List.of(md.reason(), "htf=" + htf, tilt, "state=" + marketState,
                        "level=" + active.role(), "session=" + session, "initialSize=" + initFrac)
        ));
    }

    /**
     * Stamp day-locked TOP/BOT onto TREND_HI / TREND_LO so bounce uses the same shelves as desk.
     */
    private List<ChecklistLevel> applyDayLockedShelves(
            List<ChecklistLevel> levels,
            DayZoneLock.Snapshot locked,
            double trendHigh,
            double trendLow,
            List<TrendBar> window
    ) {
        if (levels == null) {
            levels = new ArrayList<>();
        } else {
            levels = new ArrayList<>(levels);
        }
        int need = Math.max(1, settings.confirmBarsAfterBreak());
        if (locked != null && locked.hasTop()) {
            boolean broken = breakHoldSatisfied(window, locked.top(), true, need);
            ChecklistLevel top = new ChecklistLevel(
                    trendHigh, "TREND_HI",
                    (locked.topSource() == null ? "DAY" : locked.topSource()) + "+DAY",
                    false, locked.top(), broken);
            levels.removeIf(l -> "TREND_HI".equals(l.role()));
            levels.add(top);
        }
        if (locked != null && locked.hasBottom()) {
            boolean broken = breakHoldSatisfied(window, locked.bottom(), false, need);
            ChecklistLevel bot = new ChecklistLevel(
                    trendLow, "TREND_LO",
                    (locked.bottomSource() == null ? "DAY" : locked.bottomSource()) + "+DAY",
                    true, locked.bottom(), broken);
            levels.removeIf(l -> "TREND_LO".equals(l.role()));
            levels.add(bot);
        }
        return levels;
    }

    /**
     * §4 discover + §6–7 profile each level (last 2–3 bounces × 1–3 candles; tape if available).
     */
    List<ChecklistLevel> buildProfiledLevels(
            String instrument,
            List<TrendBar> window,
            MarketState state,
            double trendHigh,
            double trendLow,
            double zero,
            double lastClose
    ) {
        List<ChecklistLevel> raw = ChecklistStructure.discoverLevels(
                window, state, trendHigh, trendLow, zero, lastClose, settings.instrument(), vap);
        List<ChecklistLevel> out = new ArrayList<>();
        List<double[]> tape = tapePrints(instrument);
        boolean useTape = settings.preferMarketDataZones() && tape != null && !tape.isEmpty();
        for (ChecklistLevel l : raw) {
            MergedVolumeRange range = vap.buildFromLastBounces(window, l.price(), 3, 3);
            String src = "BARS§6";
            if (!range.validForEntry() && useTape) {
                MergedVolumeRange tapeR = vap.buildAroundLevelFromPrints(tape, l.price(), 2);
                if (tapeR.validForEntry()) {
                    range = tapeR;
                    src = "TAPE§6";
                }
            }
            if (!range.validForEntry()) {
                range = vap.buildAroundLevel(window, l.price(),
                        settings.touchLookback(), settings.candlesPerTouch(), 2);
                src = "BARS-FALLBACK";
            }
            // Structural HI/LO desk shelf — geometry only; not tradable without real §6–7 volume
            if (!range.validForEntry()
                    && ("TREND_HI".equals(l.role()) || "TREND_LO".equals(l.role()))) {
                double width = settings.instrument().zoneMinPoints() * settings.instrument().pointSize();
                double low = "TREND_HI".equals(l.role()) ? l.price() - width : l.price();
                double high = "TREND_HI".equals(l.role()) ? l.price() : l.price() + width;
                range = new MergedVolumeRange(low, high, range.totalVolume(), List.of(), false,
                        range.invalidReason() != null ? range.invalidReason() : "SOFT§6 desk only");
                src = "SOFT§6";
                // Keep for overlay context but do not mark as entry-valid
                out.add(new ChecklistLevel(l.price(), l.role(), src + "+" + l.source(),
                        l.preferBuy(), range, false));
                continue;
            }
            if (range.validForEntry()) {
                boolean brokenUp = breakHoldSatisfied(window, range, true, Math.max(1, settings.confirmBarsAfterBreak()));
                boolean brokenDown = breakHoldSatisfied(window, range, false, Math.max(1, settings.confirmBarsAfterBreak()));
                out.add(new ChecklistLevel(l.price(), l.role(), src + "+" + l.source(),
                        l.preferBuy(), range, brokenUp || brokenDown));
            }
        }
        return out;
    }

    /**
     * §8 RETEST after break+hold, else §14 bounce by preferBuy side.
     */
    private ModeDecision decideChecklistAtLevel(
            List<TrendBar> window,
            ChecklistLevel level,
            boolean brokenUp,
            boolean brokenDown
    ) {
        MergedVolumeRange range = level.range();
        double maxDist = settings.retestArmMaxDistancePoints();
        double pt = settings.instrument().pointSize();
        String role = level.role() == null ? "" : level.role();
        boolean structuralTop = "TREND_HI".equals(role) || !level.preferBuy();
        boolean structuralBot = "TREND_LO".equals(role) || level.preferBuy();

        if (brokenUp) {
            // Operator TOP rejection out of the shelf — not a from-above RETEST long touch
            TrendBar last = window.get(window.size() - 1);
            if (structuralTop && bounceConfirmed(window, range, false) && last.close() < range.low()) {
                return new ModeDecision(TrendTradeMode.BOUNCE, false,
                        "TOP rejection bounce short (confirmed below shelf) — prefer over §8 RETEST long");
            }
            // §8: retest long from above
            if (!retestEntryAllowed(window, range, true, maxDist, pt)) {
                return new ModeDecision(null, true,
                        "§8 TOP/level break+hold — waiting retest from above");
            }
            return new ModeDecision(TrendTradeMode.RETEST, true, "§8 break+hold+retest long");
        }
        if (brokenDown) {
            TrendBar last = window.get(window.size() - 1);
            if (structuralBot && bounceConfirmed(window, range, true) && last.close() > range.high()) {
                return new ModeDecision(TrendTradeMode.BOUNCE, true,
                        "BOT rejection bounce long (confirmed above shelf) — prefer over §8 RETEST short");
            }
            if (!retestEntryAllowed(window, range, false, maxDist, pt)) {
                return new ModeDecision(null, false,
                        "§8 BOT/level break+hold — waiting retest from below");
            }
            return new ModeDecision(TrendTradeMode.RETEST, false, "§8 break+hold+retest short");
        }

        // §14 bounce: preferBuy → long bounce, else short bounce
        boolean zoneIsTop = !level.preferBuy();
        return decideBounceAtShelf(window, range, zoneIsTop);
    }

    /**
     * Dual day-locked TOP+BOT: trade between shelves — nearest zone to price.
     * After a far-range break+hold, prefer that broken shelf for retest approach.
     */
    private Optional<ZoneCandidate> pickChecklistZone(
            Optional<ZoneCandidate> top,
            Optional<ZoneCandidate> bottom,
            TrendBias bias,
            boolean topBrokenHeld,
            boolean bottomBrokenHeld,
            double lastClose
    ) {
        if (top.isPresent() && bottom.isPresent()) {
            // After far-shelf break+hold — stick to that shelf for RETEST approach (§7–8)
            if (topBrokenHeld && !bottomBrokenHeld) {
                return top;
            }
            if (bottomBrokenHeld && !topBrokenHeld) {
                return bottom;
            }
            // Both broken or neither — nearest shelf
            double dTop = Math.abs(top.get().range().mid() - lastClose);
            double dBot = Math.abs(bottom.get().range().mid() - lastClose);
            return dBot <= dTop ? bottom : top;
        }
        if (bias == TrendBias.UP) {
            return bottom.isPresent() ? bottom : top;
        }
        if (bias == TrendBias.DOWN) {
            return top.isPresent() ? top : bottom;
        }
        return pickSideZone(top, bottom, lastClose, bias);
    }

    /**
     * Checklist: trade between the two ranges — UP prefers lower (BOT) bounce,
     * DOWN prefers upper (TOP) rejection; otherwise nearest to last price.
     */
    private Optional<ZoneCandidate> pickSideZone(
            Optional<ZoneCandidate> top,
            Optional<ZoneCandidate> bottom,
            double lastClose,
            TrendBias bias
    ) {
        if (top.isEmpty() && bottom.isEmpty()) {
            return Optional.empty();
        }
        if (top.isEmpty()) {
            return bottom;
        }
        if (bottom.isEmpty()) {
            return top;
        }
        if (bias == TrendBias.UP) {
            return bottom; // buy support band
        }
        if (bias == TrendBias.DOWN) {
            return top; // sell resistance band
        }
        double dTop = Math.abs(top.get().range().mid() - lastClose);
        double dBot = Math.abs(bottom.get().range().mid() - lastClose);
        return dBot <= dTop ? bottom : top;
    }

    private Optional<ZoneCandidate> pickBestZone(
            String instrument,
            List<TrendBar> window,
            List<Double> levels,
            double lastClose,
            TrendBias bias
    ) {
        List<ZoneCandidate> ok = collectValidZones(instrument, window, levels);
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

    /**
     * Full checklist at a day-locked shelf (§7–8):
     * <ul>
     *   <li>TOP unbroken → bounce short into the range</li>
     *   <li>TOP broken+held → RETEST long from above (continuation)</li>
     *   <li>BOT unbroken → bounce long into the range</li>
     *   <li>BOT broken+held → RETEST short from below (continuation)</li>
     * </ul>
     */
    private ModeDecision decideChecklistAtShelf(
            List<TrendBar> window,
            MergedVolumeRange range,
            boolean zoneIsTop,
            boolean topBrokenHeld,
            boolean bottomBrokenHeld
    ) {
        double maxDist = settings.retestArmMaxDistancePoints();
        double pt = settings.instrument().pointSize();

        if (zoneIsTop) {
            if (topBrokenHeld) {
                TrendBar last = window.get(window.size() - 1);
                // Only steal §8 RETEST when rejection closes fully below the shelf
                if (bounceConfirmed(window, range, false) && last.close() < range.low()) {
                    return new ModeDecision(TrendTradeMode.BOUNCE, false,
                            "TOP rejection bounce short (confirmed below shelf) — prefer over break RETEST long");
                }
                if (!retestEntryAllowed(window, range, true, maxDist, pt)) {
                    return new ModeDecision(null, true,
                            "TOP break+hold — waiting retest from above (touch / within "
                                    + (int) maxDist + " pts)");
                }
                return new ModeDecision(TrendTradeMode.RETEST, true,
                        "§7–8 TOP break+hold+retest long from above");
            }
            return decideBounceAtShelf(window, range, true);
        }

        if (bottomBrokenHeld) {
            TrendBar last = window.get(window.size() - 1);
            if (bounceConfirmed(window, range, true) && last.close() > range.high()) {
                return new ModeDecision(TrendTradeMode.BOUNCE, true,
                        "BOT rejection bounce long (confirmed above shelf) — prefer over break RETEST short");
            }
            if (!retestEntryAllowed(window, range, false, maxDist, pt)) {
                return new ModeDecision(null, false,
                        "BOT break+hold — waiting retest from below (touch / within "
                                + (int) maxDist + " pts)");
            }
            return new ModeDecision(TrendTradeMode.RETEST, false,
                    "§7–8 BOT break+hold+retest short from below");
        }
        return decideBounceAtShelf(window, range, false);
    }

    /**
     * Bounce at a day-locked shelf: BOT → BUY, TOP → SELL (mean-reversion between ranges).
     * Operator rejection: wick into shelf then close out of zone still arms bounce.
     */
    private ModeDecision decideBounceAtShelf(List<TrendBar> window, MergedVolumeRange range, boolean zoneIsTop) {
        BounceShelfArm arm = armBounceAtShelf(window, range, zoneIsTop, settings.requireBounceConfirm());
        return new ModeDecision(arm.mode(), arm.buy(), arm.reason());
    }

    /**
     * TOP/BOT bounce arm logic. Package-visible for tests.
     * When {@code bounceConfirmed}, arms even if close already left the shelf.
     */
    static BounceShelfArm armBounceAtShelf(
            List<TrendBar> window,
            MergedVolumeRange range,
            boolean zoneIsTop,
            boolean requireConfirm
    ) {
        boolean buy = !zoneIsTop;
        boolean above = window.get(window.size() - 1).close() > range.high();
        boolean below = window.get(window.size() - 1).close() < range.low();
        boolean inside = !above && !below;
        double lastClose = window.get(window.size() - 1).close();

        // Operator path: rejection candle may close outside the shelf.
        if (bounceConfirmed(window, range, buy)) {
            if (buy) {
                return new BounceShelfArm(TrendTradeMode.BOUNCE, true, "BOT shelf: bounce long (confirmed)");
            }
            return new BounceShelfArm(TrendTradeMode.BOUNCE, false, "TOP shelf: bounce short (confirmed)");
        }

        if (buy) {
            if (below || (inside && lastClose <= range.mid())) {
                if (requireConfirm) {
                    return new BounceShelfArm(null, true, "BOT bounce: waiting closed rejection candle in zone");
                }
                return new BounceShelfArm(TrendTradeMode.BOUNCE, true, "BOT shelf: bounce long (confirmed)");
            }
            if (above) {
                return new BounceShelfArm(null, true, "price above BOT — waiting return to shelf");
            }
            return new BounceShelfArm(null, true, "BOT shelf — mid-zone, no bounce confirm yet");
        }

        if (above || (inside && lastClose >= range.mid())) {
            if (requireConfirm) {
                return new BounceShelfArm(null, false, "TOP bounce: waiting closed rejection candle in zone");
            }
            return new BounceShelfArm(TrendTradeMode.BOUNCE, false, "TOP shelf: bounce short (confirmed)");
        }
        if (below) {
            return new BounceShelfArm(null, false, "price below TOP — waiting return to shelf");
        }
        return new BounceShelfArm(null, false, "TOP shelf — mid-zone, no bounce confirm yet");
    }

    /** Result of {@link #armBounceAtShelf} — package-visible for tests. */
    record BounceShelfArm(TrendTradeMode mode, boolean buy, String reason) {
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

    private record Swing(int index, double price, boolean high) {
    }

    /**
     * Day-locked range keeps geometry for desk; never promotes soft/invalid to tradable entry.
     */
    private ZoneCandidate asDayZone(double level, MergedVolumeRange range, String source) {
        MergedVolumeRange r = range;
        String src = source == null ? "DAY" : source + "+DAY";
        return new ZoneCandidate(level, r, src);
    }

    /** Soft / thin shelves may appear on desk but must not freeze as the day's tradable lock. */
    static boolean isSoftSource(String source) {
        if (source == null || source.isBlank()) {
            return false;
        }
        String u = source.toUpperCase(Locale.ROOT);
        return u.contains("SOFT");
    }

    static MergedVolumeRange lockableShelf(MergedVolumeRange cand, String source) {
        if (cand == null || !(cand.low() < cand.high())) {
            return null;
        }
        if (isSoftSource(source) || !cand.validForEntry()) {
            return null;
        }
        return cand;
    }

    private record ZoneCandidate(double level, MergedVolumeRange range, String source) {
    }

    private record ModeDecision(TrendTradeMode mode, boolean buy, String reason) {
    }
}
