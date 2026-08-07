package com.moex.trinity.trend.replay;

import com.moex.trinity.trend.BarAggregator;
import com.moex.trinity.trend.IssFuturesM1Client;
import com.moex.trinity.trend.LevelsProfileBrPlaybook;
import com.moex.trinity.trend.LimitGridPlan;
import com.moex.trinity.trend.MergedVolumeRange;
import com.moex.trinity.trend.TrendAccountContext;
import com.moex.trinity.trend.TrendBar;
import com.moex.trinity.trend.TrendBarSeries;
import com.moex.trinity.trend.TrendPlaybookSettings;
import com.moex.trinity.trend.TrendPositionManager;
import com.moex.trinity.trend.TrendRobotEngine;
import com.moex.trinity.trend.TrendRobotPlan;
import com.moex.trinity.trend.TrendRobotState;
import com.moex.trinity.trend.TrendSignal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * One-off / research replay: BR front M5 from MOEX ISS (1m → aggregate 5m), walk today's session.
 *
 * <pre>
 * mvn -pl trinity-trend -DskipTests package
 * mvn -pl trinity-trend -q exec:java -Dexec.mainClass=com.moex.trinity.trend.replay.BrM5DayReplay
 * </pre>
 */
public final class BrM5DayReplay {

    private static final DateTimeFormatter MOEX_DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        boolean withRetest = false;
        boolean barsOnly = false;
        String barFile = null;
        LocalDate dayArg = null;
        String secidArg = null;
        for (String a : args) {
            if ("--with-retest".equals(a)) {
                withRetest = true;
            } else if ("--bars-only".equals(a)) {
                barsOnly = true;
            } else if (a.endsWith(".json")) {
                barFile = a;
            } else if (a.matches("\\d{4}-\\d{2}-\\d{2}")) {
                if (dayArg == null) {
                    dayArg = LocalDate.parse(a);
                }
                // never treat a date as secid
            } else if (secidArg == null && !a.startsWith("--") && a.matches("[A-Za-z][A-Za-z0-9]*")) {
                secidArg = a;
            }
        }

        LocalDate day;
        String secid;
        List<TrendBar> m1;

        if (barFile != null) {
            Path file = Path.of(barFile);
            JsonNode root = MAPPER.readTree(file.toFile());
            day = LocalDate.parse(root.path("day").asText());
            secid = root.path("secid").asText("BRU6");
            m1 = new ArrayList<>();
            for (JsonNode row : root.path("bars")) {
                m1.add(new TrendBar(
                        LocalDateTime.parse(row.path("time").asText(), MOEX_DT),
                        row.path("open").asDouble(),
                        row.path("high").asDouble(),
                        row.path("low").asDouble(),
                        row.path("close").asDouble(),
                        row.path("volume").asDouble()
                ));
            }
            System.out.printf(Locale.ROOT, "=== BR M5 day replay %s secid=%s (from %s) ===%n",
                    day, secid, file);
        } else {
            day = dayArg != null ? dayArg : LocalDate.now();
            secid = secidArg != null ? secidArg : resolveFrontBr();
            System.out.printf(Locale.ROOT, "=== BR M5 day replay %s secid=%s ===%n", day, secid);
            LocalDate from = day.minusDays(1);
            m1 = fetchM1(secid, from, day);
            cacheM1(secid, day, from, m1);
        }

        TrendPlaybookSettings settings = TrendPlaybookSettings.brDefaults().withASetupBounceOnly(false);
        Map<String, Object> report = runOneDay(day, secid, m1, settings, barsOnly, true);
        if (withRetest) {
            report.put("modeNote", "FULL checklist (bounce + RETEST)");
        }
        Path out = Path.of("data", "trend-day-replay-" + day + "-" + secid + "-retest.json");
        Files.createDirectories(out.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), report);
        System.out.println("Wrote " + out.toAbsolutePath());
    }

    /**
     * Fair-paper one session. Does not write paper journal.
     *
     * @param barsOnly if true, skip broker tape (quality=BAR_PROXY)
     */
    public static Map<String, Object> runOneDay(
            LocalDate day,
            String secid,
            List<TrendBar> m1,
            TrendPlaybookSettings settings,
            boolean barsOnly,
            boolean verbose
    ) throws Exception {
        if (m1 == null || m1.size() < 50) {
            throw new IllegalStateException("Too few M1 bars for " + day);
        }
        List<TrendBar> m5 = aggregateM5(m1);
        List<TrendBar> warmup = new ArrayList<>();
        List<TrendBar> today = new ArrayList<>();
        for (TrendBar b : m5) {
            if (b.time().toLocalDate().equals(day)) {
                today.add(b);
            } else if (b.time().toLocalDate().isBefore(day)) {
                warmup.add(b);
            }
        }
        if (verbose) {
            System.out.printf(Locale.ROOT, "Loaded %d × 1m → %d M5 | warm-up=%d today=%d%n",
                    m1.size(), m5.size(), warmup.size(), today.size());
        }
        if (today.isEmpty()) {
            throw new IllegalStateException("No M5 bars for " + day);
        }

        String quality;
        com.moex.trinity.marketdata.HistoricalTapeFeed tapeFeed;
        String tapeSource;
        int tapePrints;
        int domSnaps;
        if (barsOnly) {
            quality = "BAR_PROXY";
            tapeFeed = new com.moex.trinity.marketdata.HistoricalTapeFeed(secid, List.of(), 1);
            tapeSource = "NONE";
            tapePrints = 0;
            domSnaps = 0;
            if (verbose) {
                System.out.println("Mode: --bars-only (no tape VAP)");
            }
        } else {
            quality = "TAPE";
            TapeBundle tapeBundle = loadBrokerTape(secid, day);
            tapeFeed = new com.moex.trinity.marketdata.HistoricalTapeFeed(secid, tapeBundle.prints(), 400_000)
                    .withBooks(tapeBundle.domHistory());
            if (tapeBundle.domHistory().isEmpty() && tapeBundle.book() != null) {
                tapeFeed.withBook(tapeBundle.book());
            }
            tapeSource = tapeBundle.source();
            tapePrints = tapeFeed.tapeSize();
            domSnaps = tapeFeed.domSnapshots();
            if (verbose) {
                System.out.printf(Locale.ROOT, "Tape prints=%d (%s) DOM=%d%n",
                        tapePrints, tapeSource, domSnaps);
            }
        }

        LevelsProfileBrPlaybook playbook = new LevelsProfileBrPlaybook(settings, tapeFeed);
        TrendRobotEngine engine = new TrendRobotEngine(playbook, settings);
        TrendAccountContext account = TrendAccountContext.of(100_000, 15_000, 16_000, 1.0);
        double rubPerPoint = settings.instrument().rubPerPoint();
        double point = settings.instrument().pointSize();

        List<Map<String, Object>> events = new ArrayList<>();
        List<Map<String, Object>> trades = new ArrayList<>();
        TrendRobotState prev = null;
        int newSetups = 0;
        int lockedBars = 0;
        int zoneReadyBars = 0;
        int noTradeBars = 0;
        Map<String, Integer> reasonHist = new TreeMap<>();

        OpenPaper open = null;
        TrendRobotPlan pending = null;

        List<TrendBar> seriesBars = new ArrayList<>(warmup);
        for (int bi = 0; bi < today.size(); bi++) {
            TrendBar bar = today.get(bi);
            seriesBars.add(bar);
            tapeFeed.setAsOf(bar.time());

            if (open != null) {
                ExitResult er = manageFair(open, bar, rubPerPoint, point);
                if (er != null) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("entry", open.entryTime.toString());
                    t.put("exit", bar.time().toString());
                    t.put("side", open.buy ? "BUY" : "SELL");
                    t.put("mode", open.mode);
                    t.put("pnl", er.pnl);
                    t.put("reason", er.reason);
                    trades.add(t);
                    engine.registerRealizedPnl(er.pnl);
                    if (verbose) {
                        System.out.printf(Locale.ROOT, "TRADE %s %s → %s  PnL=%+.0f ₽ (%s)%n",
                                open.entryTime, open.buy ? "BUY" : "SELL", er.reason, er.pnl, bar.time());
                    }
                    if ("SL".equals(er.reason)) {
                        engine.registerStopLoss(bar.time());
                    } else {
                        engine.registerFlatWin(bar.time());
                    }
                    open = null;
                }
            }

            if (open == null && pending != null && pending.grid() != null) {
                if (pending.range() != null) {
                    double unlockPts = settings.unlockDistancePoints() > 0
                            ? settings.unlockDistancePoints() : 40;
                    double dist = Math.abs(bar.close() - pending.range().mid()) / point;
                    if (dist >= unlockPts) {
                        if (verbose) {
                            System.out.printf(Locale.ROOT, "%s  CANCEL pending (unlock %.0f pts)%n",
                                    bar.time(), dist);
                        }
                        pending = null;
                        engine.clearSetupLock();
                    }
                }
                if (pending != null) {
                    OpenPaper filled = tryOpenFair(pending, bar);
                    if (filled != null) {
                        open = filled;
                        pending = null;
                        engine.registerFill(bar.time());
                        if (verbose) {
                            System.out.printf(Locale.ROOT, "%s  FILL %s avg=%.2f qty=%d SL=%.2f TP1=%.2f%n",
                                    bar.time(), open.buy ? "BUY" : "SELL", open.avg, open.qty, open.sl, open.tp1);
                        }
                    }
                }
            }

            TrendBarSeries series = new TrendBarSeries(secid, "M5", seriesBars);
            Optional<TrendRobotPlan> opt = engine.evaluate(series, account);
            if (opt.isEmpty()) {
                noTradeBars++;
                prev = null;
                continue;
            }
            TrendRobotPlan plan = opt.get();
            TrendRobotState st = plan.state();
            String why = plan.rationale() == null ? "" : plan.rationale();
            if (!why.isBlank()) {
                String key = why.length() > 120 ? why.substring(0, 120) : why;
                reasonHist.merge(key, 1, Integer::sum);
            }
            if (st == TrendRobotState.NO_TRADE) {
                noTradeBars++;
                if (open == null && pending != null && plan.rationale() != null
                        && (plan.rationale().contains("cooldown")
                        || plan.rationale().contains("session edge")
                        || plan.rationale().contains("event edge")
                        || plan.rationale().contains("max day loss")
                        || plan.rationale().contains("max fills/day"))) {
                    pending = null;
                }
            } else if (st == TrendRobotState.ZONE_READY) {
                zoneReadyBars++;
            } else if (st == TrendRobotState.WORKING_ORDERS) {
                lockedBars++;
            }

            boolean isNewArmed = open == null && pending == null && plan.actionable()
                    && (st == TrendRobotState.ARMED_BOUNCE || st == TrendRobotState.ARMED_RETEST)
                    && st != prev;
            if (isNewArmed) {
                newSetups++;
                pending = plan;
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("time", bar.time().toString());
                ev.put("close", bar.close());
                ev.put("state", st.name());
                ev.put("side", plan.buy() ? "BUY" : "SELL");
                ev.put("mode", plan.mode() == null ? null : plan.mode().name());
                ev.put("summary", TrendSignal.from(plan).summary());
                if (plan.range() != null) {
                    ev.put("zone", String.format(Locale.ROOT, "%.2f–%.2f (%.0f pts)",
                            plan.range().low(), plan.range().high(),
                            plan.range().widthPoints(point)));
                }
                events.add(ev);
                if (verbose) {
                    System.out.printf(Locale.ROOT, "%s  NEW_SETUP %s %s  C=%.2f  %s%n",
                            bar.time(), plan.buy() ? "BUY" : "SELL",
                            plan.mode(), bar.close(), plan.rationale());
                }
            } else if (verbose && st != prev && (st == TrendRobotState.ZONE_READY || st == TrendRobotState.NO_TRADE
                    || st == TrendRobotState.WORKING_ORDERS)) {
                System.out.printf(Locale.ROOT, "%s  %-14s C=%.2f  %s%n",
                        bar.time(), st, bar.close(),
                        why.length() > 100 ? why.substring(0, 100) + "…" : why);
            }
            prev = st;
        }

        if (open != null) {
            TrendBar last = today.get(today.size() - 1);
            double pnl = open.realized + cashPnl(open, last.close(), open.qty, point, rubPerPoint);
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("entry", open.entryTime.toString());
            t.put("exit", last.time().toString());
            t.put("side", open.buy ? "BUY" : "SELL");
            t.put("mode", open.mode);
            t.put("pnl", pnl);
            t.put("reason", "EOD");
            trades.add(t);
            engine.registerRealizedPnl(pnl);
            engine.registerFlatWin(last.time());
            if (verbose) {
                System.out.printf(Locale.ROOT, "TRADE EOD flatten PnL=%+.0f ₽%n", pnl);
            }
        }

        double dayPnl = trades.stream().mapToDouble(t -> ((Number) t.get("pnl")).doubleValue()).sum();
        long wins = trades.stream().filter(t -> ((Number) t.get("pnl")).doubleValue() > 0).count();
        long losses = trades.stream().filter(t -> ((Number) t.get("pnl")).doubleValue() < 0).count();

        if (verbose) {
            System.out.println();
            System.out.println("--- Summary ---");
            System.out.printf(Locale.ROOT,
                    "Day bars: %d | NEW setups: %d | trades: %d | W/L %d/%d | day PnL: %+.0f ₽%n",
                    today.size(), newSetups, trades.size(), wins, losses, dayPnl);
            System.out.printf(Locale.ROOT, "ZONE_READY bars≈%d | locked≈%d | NO_TRADE≈%d | dayLossBlocks=%d%n",
                    zoneReadyBars, lockedBars, noTradeBars, engine.dayLossBlockCount());
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("day", day.toString());
        report.put("secid", secid);
        report.put("quality", quality);
        report.put("todayM5", today.size());
        report.put("newSetups", newSetups);
        report.put("trades", trades);
        report.put("dayPnlRub", dayPnl);
        report.put("wins", wins);
        report.put("losses", losses);
        report.put("events", events);
        report.put("reasonHist", reasonHist);
        report.put("tapePrints", tapePrints);
        report.put("tapeSource", tapeSource);
        report.put("domSnapshots", domSnaps);
        report.put("dayLossBlocks", engine.dayLossBlockCount());
        report.put("maxSetupsPerDay", settings.maxSetupsPerDay());
        report.put("maxDayLossRub", settings.maxDayLossRub());
        report.put("minShelfVolume", settings.minShelfVolume());
        report.put("note", "Fair paper replay — does not write trend-paper-journal. "
                + "FULL checklist bounce+retest + day-lock/prior/session/HTF/anti-thin.");
        return report;
    }

    /** Persist ISS M1 for offline replay / campaign. */
    static void cacheM1(String secid, LocalDate day, LocalDate from, List<TrendBar> m1) {
        try {
            Path out = Path.of("data", "br-m1-" + day + ".json");
            Files.createDirectories(out.getParent());
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("day", day.toString());
            root.put("secid", secid);
            root.put("from", from.toString());
            root.put("till", day.toString());
            List<Map<String, Object>> bars = new ArrayList<>();
            for (TrendBar b : m1) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("time", b.time().format(MOEX_DT));
                row.put("open", b.open());
                row.put("high", b.high());
                row.put("low", b.low());
                row.put("close", b.close());
                row.put("volume", b.volume());
                bars.add(row);
            }
            root.put("bars", bars);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), root);
            System.out.println("Cached M1 → " + out.toAbsolutePath());
        } catch (Exception ex) {
            System.out.println("M1 cache skip: " + ex.getMessage());
        }
    }

    /** Load offline M1 cache if present. */
    public static List<TrendBar> loadCachedM1(Path file) throws Exception {
        JsonNode root = MAPPER.readTree(file.toFile());
        List<TrendBar> m1 = new ArrayList<>();
        for (JsonNode row : root.path("bars")) {
            m1.add(new TrendBar(
                    LocalDateTime.parse(row.path("time").asText(), MOEX_DT),
                    row.path("open").asDouble(),
                    row.path("high").asDouble(),
                    row.path("low").asDouble(),
                    row.path("close").asDouble(),
                    row.path("volume").asDouble()
            ));
        }
        return m1;
    }

    public static LocalDate cachedDay(Path file) throws Exception {
        return LocalDate.parse(MAPPER.readTree(file.toFile()).path("day").asText());
    }

    public static String cachedSecid(Path file) throws Exception {
        return MAPPER.readTree(file.toFile()).path("secid").asText("BRU6");
    }

    private record TapeBundle(
            List<com.moex.trinity.marketdata.TradePrint> prints,
            String source,
            com.moex.trinity.marketdata.DomBook book,
            List<com.moex.trinity.marketdata.DomBook> domHistory
    ) {
        TapeBundle {
            if (domHistory == null) {
                domHistory = List.of();
            }
        }
    }

    /**
     * Tape + DOM sources (broker only):
     * 1) archived stream under {@code data/broker-tape/}
     * 2) GetLastTrades (~last hour) + live unary DOM seed
     */
    static TapeBundle loadBrokerTape(String secid, LocalDate day) throws Exception {
        com.moex.trinity.marketdata.TInvestCredentials creds =
                com.moex.trinity.marketdata.TInvestCredentials.resolve();
        if (!creds.present()) {
            throw new IllegalStateException(
                    "Broker token required. Set T_INVEST_TOKEN or data/broker-ui-settings.json — "
                            + "tape/DOM come only from T-Invest (no ISS/M1 fallback).");
        }
        com.moex.trinity.marketdata.BrokerTapeArchive archive =
                new com.moex.trinity.marketdata.BrokerTapeArchive(Path.of("data", "broker-tape"));
        List<com.moex.trinity.marketdata.TradePrint> archived = archive.loadDay(secid, day);
        List<com.moex.trinity.marketdata.DomBook> domHist = archive.loadDomDay(secid, day);
        if (!archived.isEmpty()) {
            System.out.printf(Locale.ROOT, "Loaded %d prints + %d DOM snaps from broker archive%n",
                    archived.size(), domHist.size());
            com.moex.trinity.marketdata.DomBook book = domHist.isEmpty()
                    ? fetchBookOrNull(creds, secid)
                    : domHist.get(domHist.size() - 1);
            return new TapeBundle(archived, "T_INVEST_ARCHIVE", book, domHist);
        }

        System.out.printf(Locale.ROOT, "Fetching T-Invest GetLastTrades for %s %s …%n", secid, day);
        boolean mdSandbox = Boolean.parseBoolean(
                System.getProperty("imoex.marketdata.sandbox", "false"));
        try (com.moex.trinity.marketdata.TInvestBrokerMarketData md =
                     new com.moex.trinity.marketdata.TInvestBrokerMarketData(
                             new com.moex.trinity.marketdata.TInvestCredentials(creds.token(), mdSandbox))) {
            String figi = md.resolveFigi(secid);
            System.out.printf(Locale.ROOT, "  FIGI %s → %s%n", secid, figi);
            List<com.moex.trinity.marketdata.TradePrint> prints =
                    md.fetchTradesForMoscowDay(secid, figi, day);
            com.moex.trinity.marketdata.DomBook book = md.fetchOrderBook(
                    secid, figi, com.moex.trinity.marketdata.TInvestBrokerMarketData.MAX_ORDERBOOK_DEPTH);
            if (prints.isEmpty()) {
                throw new IllegalStateException(
                        "T-Invest returned 0 trades for " + day + ". GetLastTrades is ~last hour only; "
                                + "run live marketdata / BrokerTapeRecorder to archive tape+DOM under "
                                + "data/broker-tape/ for full-day replay.");
            }
            for (com.moex.trinity.marketdata.TradePrint p : prints) {
                archive.append(p);
            }
            archive.appendDom(book);
            System.out.printf(Locale.ROOT, "  GetLastTrades: %d prints | DOM depth=%d (live seed)%n",
                    prints.size(), book.depth());
            return new TapeBundle(prints, "T_INVEST_GET_LAST_TRADES", book, List.of(book));
        }
    }

    static com.moex.trinity.marketdata.DomBook fetchBookOrNull(
            com.moex.trinity.marketdata.TInvestCredentials creds,
            String secid
    ) {
        try (com.moex.trinity.marketdata.TInvestBrokerMarketData md =
                     new com.moex.trinity.marketdata.TInvestBrokerMarketData(
                             new com.moex.trinity.marketdata.TInvestCredentials(
                                     creds.token(),
                                     Boolean.parseBoolean(System.getProperty("imoex.marketdata.sandbox", "false"))
                             ))) {
            String figi = md.resolveFigi(secid);
            return md.fetchOrderBook(
                    secid, figi, com.moex.trinity.marketdata.TInvestBrokerMarketData.MAX_ORDERBOOK_DEPTH);
        } catch (Exception ex) {
            System.out.println("  DOM fetch skip: " + ex.getMessage());
            return null;
        }
    }

    private static OpenPaper tryOpenFair(TrendRobotPlan plan, TrendBar bar) {
        LimitGridPlan g = plan.grid();
        boolean buy = plan.buy();
        List<double[]> fills = new ArrayList<>(); // price, qty
        double[][] limits = {
                {g.nearPrice(), g.nearQty()},
                {g.midPrice(), g.midQty()},
                {g.farPrice(), g.farQty()}
        };
        for (double[] lim : limits) {
            if (lim[1] <= 0) {
                continue;
            }
            if (bar.low() <= lim[0] && lim[0] <= bar.high()) {
                fills.add(new double[]{lim[0], lim[1]});
            }
        }
        if (fills.isEmpty()) {
            return null; // keep pending for later bars
        }
        double qty = fills.stream().mapToDouble(f -> f[1]).sum();
        double avg = fills.stream().mapToDouble(f -> f[0] * f[1]).sum() / qty;
        // Re-anchor SL/TP to fill avg (plan levels were vs full-grid theoretical avg)
        double gridAvg = g.averagePrice();
        double risk = Math.abs(plan.stopLossPrice() - gridAvg);
        double r1 = Math.abs(plan.tp1Price() - gridAvg);
        double r2 = Math.abs(plan.tp2Price() - gridAvg);
        double sl = buy ? avg - risk : avg + risk;
        double tp1 = buy ? avg + r1 : avg - r1;
        double tp2 = buy ? avg + r2 : avg - r2;
        return new OpenPaper(
                bar.time(),
                buy,
                plan.mode() == null ? "?" : plan.mode().name(),
                avg,
                (int) qty,
                sl,
                tp1,
                tp2,
                plan.tp1Fraction(),
                false,
                true // fill bar — no SL this bar
        );
    }

    private static ExitResult manageFair(OpenPaper open, TrendBar bar, double rubPerPoint, double point) {
        if (open.fillBar) {
            open.fillBar = false;
            return null;
        }
        boolean buy = open.buy;

        // §12: after TP1 — BE + trail on each bar (close-based trail)
        if (open.tp1Done) {
            double trailPts = Math.abs(open.avg - open.sl) > 0
                    ? 20 // default BR stop points when already at BE
                    : 20;
            // Prefer instrument-typical 20 pts trail after BE
            trailPts = 20;
            var advice = TrendPositionManager.update(
                    buy, open.avg, open.sl, open.tp1, bar.close(), point, trailPts,
                    open.qty, open.tp1Fraction, true);
            if (Double.isFinite(advice.stop())) {
                open.sl = advice.stop();
            }
            // §12.2: stop qty must match remainder after TP1
            if (advice.stopQty() > 0 && advice.stopQty() < open.qty) {
                open.qty = advice.stopQty();
            }
        }

        // Gap through stop at open
        if (buy && bar.open() <= open.sl) {
            return new ExitResult(open.realized + cashPnl(open, bar.open(), open.qty, point, rubPerPoint),
                    open.tp1Done ? "BE_STOP" : "SL");
        }
        if (!buy && bar.open() >= open.sl) {
            return new ExitResult(open.realized + cashPnl(open, bar.open(), open.qty, point, rubPerPoint),
                    open.tp1Done ? "BE_STOP" : "SL");
        }
        boolean hitSl = buy ? bar.low() <= open.sl : bar.high() >= open.sl;
        boolean hitTp1 = !open.tp1Done && (buy ? bar.high() >= open.tp1 : bar.low() <= open.tp1);
        boolean hitTp2 = open.tp1Done && (buy ? bar.high() >= open.tp2 : bar.low() <= open.tp2);

        if (hitSl && (hitTp1 || hitTp2)) {
            // adverse first on ambiguous bar
            return new ExitResult(open.realized + cashPnl(open, open.sl, open.qty, point, rubPerPoint),
                    open.tp1Done ? "BE_STOP" : "SL");
        }
        if (hitSl) {
            double pnl = open.realized + cashPnl(open, open.sl, open.qty, point, rubPerPoint);
            return new ExitResult(pnl, open.tp1Done ? "BE_STOP" : "SL");
        }
        if (hitTp1) {
            int q1 = Math.max(1, (int) Math.round(open.qty * open.tp1Fraction));
            q1 = Math.min(q1, open.qty);
            open.realized += cashPnl(open, open.tp1, q1, point, rubPerPoint);
            open.qty -= q1;
            open.sl = open.avg;
            open.tp1Done = true;
            // §12.2: remainder stays on BE stop
            var advice = TrendPositionManager.update(
                    buy, open.avg, open.sl, open.tp1, bar.close(), point, 20,
                    open.qty + q1, open.tp1Fraction, true);
            if (advice.stopQty() > 0) {
                open.qty = advice.stopQty();
            }
            if (Double.isFinite(advice.stop()) && advice.trailing()) {
                open.sl = advice.stop();
            }
            if (open.qty <= 0) {
                return new ExitResult(open.realized, "TP1_FULL");
            }
            // continue; if also TP2 same bar
            if (buy ? bar.high() >= open.tp2 : bar.low() <= open.tp2) {
                open.realized += cashPnl(open, open.tp2, open.qty, point, rubPerPoint);
                return new ExitResult(open.realized, "TP2");
            }
            return null;
        }
        if (hitTp2) {
            open.realized += cashPnl(open, open.tp2, open.qty, point, rubPerPoint);
            return new ExitResult(open.realized, "TP2");
        }
        return null;
    }

    private static double cashPnl(OpenPaper open, double exit, int qty, double point, double rubPerPoint) {
        if (qty <= 0 || point <= 0) {
            return 0;
        }
        double pts = (exit - open.avg) / point;
        double signed = open.buy ? pts : -pts;
        return signed * qty * rubPerPoint;
    }

    private static final class OpenPaper {
        final LocalDateTime entryTime;
        final boolean buy;
        final String mode;
        final double avg;
        int qty;
        double sl;
        final double tp1;
        final double tp2;
        final double tp1Fraction;
        boolean tp1Done;
        boolean fillBar;
        double realized;

        OpenPaper(LocalDateTime entryTime, boolean buy, String mode, double avg, int qty,
                  double sl, double tp1, double tp2, double tp1Fraction, boolean tp1Done, boolean fillBar) {
            this.entryTime = entryTime;
            this.buy = buy;
            this.mode = mode;
            this.avg = avg;
            this.qty = qty;
            this.sl = sl;
            this.tp1 = tp1;
            this.tp2 = tp2;
            this.tp1Fraction = tp1Fraction;
            this.tp1Done = tp1Done;
            this.fillBar = fillBar;
        }
    }

    private record ExitResult(double pnl, String reason) {
    }

    static String resolveFrontBr() throws Exception {
        String url = "https://iss.moex.com/iss/engines/futures/markets/forts/securities.json"
                + "?iss.meta=off&iss.only=securities,marketdata";
        JsonNode root = getJson(url);
        JsonNode secs = root.path("securities");
        JsonNode mds = root.path("marketdata");
        Map<String, Integer> si = colIndex(secs.path("columns"));
        Map<String, Integer> mi = colIndex(mds.path("columns"));
        String best = null;
        long bestVol = -1;
        Map<String, Long> volBy = new TreeMap<>();
        for (JsonNode row : mds.path("data")) {
            String sec = row.get(mi.get("SECID")).asText();
            long vol = row.get(mi.get("VOLTODAY")).asLong(0);
            volBy.put(sec, vol);
        }
        for (JsonNode row : secs.path("data")) {
            String sec = row.get(si.get("SECID")).asText();
            if (!sec.startsWith("BR") || sec.length() > 5) {
                continue;
            }
            long vol = volBy.getOrDefault(sec, 0L);
            if (vol > bestVol) {
                bestVol = vol;
                best = sec;
            }
        }
        if (best == null) {
            throw new IllegalStateException("No BR contract found");
        }
        System.out.printf(Locale.ROOT, "Front by VOLTODAY: %s (vol=%d)%n", best, bestVol);
        return best;
    }

    static List<TrendBar> fetchM1(String secid, LocalDate from, LocalDate till) throws Exception {
        return IssFuturesM1Client.fetchM1(secid, from, till);
    }

    static List<TrendBar> aggregateM5(List<TrendBar> m1) {
        return BarAggregator.aggregateM5(m1);
    }

    private static Map<String, Integer> colIndex(JsonNode columns) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            m.put(columns.get(i).asText(), i);
        }
        return m;
    }

    private static JsonNode getJson(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", "TRINITY-BrM5DayReplay/1.0")
                .GET()
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + res.statusCode() + " for " + url);
        }
        return MAPPER.readTree(res.body());
    }
}
