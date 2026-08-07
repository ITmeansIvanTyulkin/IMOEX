package com.moex.trinity.trend.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moex.trinity.trend.TrendBar;
import com.moex.trinity.trend.TrendPlaybookSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Multi-day OOS campaign over BR M5 fair-paper days.
 *
 * <pre>
 * mvn -pl trinity-trend -DskipTests package
 * mvn -pl trinity-trend -q exec:java \
 *   -Dexec.mainClass=com.moex.trinity.trend.replay.BrM5CampaignReplay \
 *   -Dexec.args="2026-08-03 2026-08-04 2026-08-05 2026-08-06"
 * # or: --bars-only 2026-08-03 2026-08-06
 * # or: data/br-m1-2026-08-04.json data/br-m1-2026-08-05.json
 * </pre>
 *
 * Does not write {@code data/trend-paper-journal.json}.
 */
public final class BrM5CampaignReplay {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    public static void main(String[] args) throws Exception {
        boolean barsOnly = false;
        List<LocalDate> days = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        String secid = "BRU6";
        for (String a : args) {
            if ("--bars-only".equals(a)) {
                barsOnly = true;
            } else if (a.endsWith(".json")) {
                files.add(Path.of(a));
            } else if (a.matches("\\d{4}-\\d{2}-\\d{2}")) {
                days.add(LocalDate.parse(a));
            } else if (!a.startsWith("--") && a.matches("BR[A-Z0-9]+")) {
                secid = a;
            }
        }
        if (files.isEmpty() && days.isEmpty()) {
            // default gold set with local tape
            days.addAll(List.of(
                    LocalDate.of(2026, 8, 3),
                    LocalDate.of(2026, 8, 4),
                    LocalDate.of(2026, 8, 5),
                    LocalDate.of(2026, 8, 6)
            ));
        }

        TrendPlaybookSettings settings = TrendPlaybookSettings.brDefaults().withASetupBounceOnly(false);
        List<Map<String, Object>> dayReports = new ArrayList<>();
        List<Double> equity = new ArrayList<>();
        equity.add(0.0);
        double peak = 0;
        double maxDd = 0;
        double grossWin = 0;
        double grossLoss = 0;
        int wins = 0;
        int losses = 0;
        int flat = 0;
        Map<String, ModeAgg> byMode = new TreeMap<>();
        Map<String, Integer> reasonHist = new TreeMap<>();
        int dayLossBlocks = 0;

        System.out.printf(Locale.ROOT, "=== BR M5 campaign barsOnly=%s secid=%s ===%n", barsOnly, secid);

        if (!files.isEmpty()) {
            for (Path f : files) {
                LocalDate day = BrM5DayReplay.cachedDay(f);
                String sid = BrM5DayReplay.cachedSecid(f);
                List<TrendBar> m1 = BrM5DayReplay.loadCachedM1(f);
                m1 = mergeWarmupFromNeighborCaches(m1, day, sid);
                Map<String, Object> dayRep = BrM5DayReplay.runOneDay(day, sid, m1, settings, barsOnly, true);
                accumulate(dayRep, dayReports, equity, byMode, reasonHist);
                dayLossBlocks += ((Number) dayRep.getOrDefault("dayLossBlocks", 0)).intValue();
            }
        } else {
            for (LocalDate day : days) {
                Path cache = Path.of("data", "br-m1-" + day + ".json");
                List<TrendBar> m1;
                String sid = secid;
                if (Files.isRegularFile(cache)) {
                    m1 = BrM5DayReplay.loadCachedM1(cache);
                    sid = BrM5DayReplay.cachedSecid(cache);
                    m1 = mergeWarmupFromNeighborCaches(m1, day, sid);
                    System.out.println("Using cache " + cache);
                } else {
                    LocalDate from = day.minusDays(1);
                    m1 = BrM5DayReplay.fetchM1(sid, from, day);
                    BrM5DayReplay.cacheM1(sid, day, from, m1);
                }
                Map<String, Object> dayRep = BrM5DayReplay.runOneDay(day, sid, m1, settings, barsOnly, true);
                accumulate(dayRep, dayReports, equity, byMode, reasonHist);
                dayLossBlocks += ((Number) dayRep.getOrDefault("dayLossBlocks", 0)).intValue();
            }
        }

        // Trade-level equity for max DD (day-end curve alone can hide intraday DD)
        List<Double> tradeEquity = new ArrayList<>();
        tradeEquity.add(0.0);
        for (Map<String, Object> d : dayReports) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> trades = (List<Map<String, Object>>) d.get("trades");
            if (trades == null) {
                continue;
            }
            for (Map<String, Object> t : trades) {
                double pnl = ((Number) t.get("pnl")).doubleValue();
                double next = tradeEquity.get(tradeEquity.size() - 1) + pnl;
                tradeEquity.add(next);
                if (pnl > 0) {
                    wins++;
                    grossWin += pnl;
                } else if (pnl < 0) {
                    losses++;
                    grossLoss += -pnl;
                } else {
                    flat++;
                }
            }
        }
        for (double e : tradeEquity) {
            peak = Math.max(peak, e);
            maxDd = Math.min(maxDd, e - peak);
        }

        double totalPnl = equity.isEmpty() ? 0 : equity.get(equity.size() - 1);
        int closed = wins + losses;
        double wr = closed == 0 ? 0 : (100.0 * wins / closed);
        double pf = grossLoss <= 1e-9 ? (grossWin > 0 ? Double.POSITIVE_INFINITY : 0) : grossWin / grossLoss;

        Map<String, Object> modesOut = new LinkedHashMap<>();
        for (Map.Entry<String, ModeAgg> e : byMode.entrySet()) {
            modesOut.put(e.getKey(), e.getValue().toMap());
        }

        System.out.println();
        System.out.println("======== CAMPAIGN ========");
        System.out.printf(Locale.ROOT, "Days=%d trades=%d W/L=%d/%d WR=%.1f%% PF=%s total=%+.0f ₽ maxDD=%+.0f ₽%n",
                dayReports.size(), wins + losses + flat, wins, losses, wr,
                Double.isInfinite(pf) ? "inf" : String.format(Locale.ROOT, "%.2f", pf),
                totalPnl, maxDd);
        for (Map.Entry<String, ModeAgg> e : byMode.entrySet()) {
            ModeAgg m = e.getValue();
            System.out.printf(Locale.ROOT, "  %s: n=%d W/L=%d/%d PnL=%+.0f PF=%s%n",
                    e.getKey(), m.n, m.wins, m.losses, m.pnl,
                    m.grossLoss <= 1e-9 ? (m.grossWin > 0 ? "inf" : "0")
                            : String.format(Locale.ROOT, "%.2f", m.grossWin / m.grossLoss));
        }
        System.out.printf(Locale.ROOT, "dayLossBlocks=%d maxSetups=%d maxDayLoss=%.0f minShelfVol=%.0f%n",
                dayLossBlocks, settings.maxSetupsPerDay(), settings.maxDayLossRub(), settings.minShelfVolume());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", LocalDateTime.now().toString());
        report.put("barsOnly", barsOnly);
        report.put("secid", secid);
        report.put("days", dayReports.size());
        report.put("totalPnlRub", totalPnl);
        report.put("wins", wins);
        report.put("losses", losses);
        report.put("flat", flat);
        report.put("winRatePct", wr);
        report.put("profitFactor", Double.isInfinite(pf) ? null : pf);
        report.put("profitFactorInfinite", Double.isInfinite(pf));
        report.put("maxDrawdownRub", maxDd);
        report.put("equityCurve", equity);
        report.put("tradeEquityCurve", tradeEquity);
        report.put("byMode", modesOut);
        report.put("dayLossBlocks", dayLossBlocks);
        report.put("reasonHist", reasonHist);
        report.put("dayReports", dayReports);
        report.put("settings", Map.of(
                "maxSetupsPerDay", settings.maxSetupsPerDay(),
                "maxDayLossRub", settings.maxDayLossRub(),
                "minShelfVolume", settings.minShelfVolume(),
                "aSetupBounceOnly", settings.aSetupBounceOnly(),
                "preferStructuralEntries", settings.preferStructuralEntries()
        ));
        report.put("note", "Research campaign — does not touch trend-paper-journal.");

        String stamp = LocalDateTime.now().format(STAMP);
        Path out = Path.of("data", "trend-campaign-" + stamp + ".json");
        Files.createDirectories(out.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), report);
        System.out.println("Wrote " + out.toAbsolutePath());
    }

    @SuppressWarnings("unchecked")
    private static void accumulate(
            Map<String, Object> dayRep,
            List<Map<String, Object>> dayReports,
            List<Double> equity,
            Map<String, ModeAgg> byMode,
            Map<String, Integer> reasonHist
    ) {
        dayReports.add(dayRep);
        double dayPnl = ((Number) dayRep.getOrDefault("dayPnlRub", 0)).doubleValue();
        double last = equity.get(equity.size() - 1);
        equity.add(last + dayPnl);
        List<Map<String, Object>> trades = (List<Map<String, Object>>) dayRep.get("trades");
        if (trades != null) {
            for (Map<String, Object> t : trades) {
                String mode = String.valueOf(t.getOrDefault("mode", "?"));
                ModeAgg agg = byMode.computeIfAbsent(mode, k -> new ModeAgg());
                double pnl = ((Number) t.get("pnl")).doubleValue();
                agg.n++;
                agg.pnl += pnl;
                if (pnl > 0) {
                    agg.wins++;
                    agg.grossWin += pnl;
                } else if (pnl < 0) {
                    agg.losses++;
                    agg.grossLoss += -pnl;
                }
            }
        }
        Map<String, Integer> rh = (Map<String, Integer>) dayRep.get("reasonHist");
        if (rh != null) {
            for (Map.Entry<String, Integer> e : rh.entrySet()) {
                reasonHist.merge(e.getKey(), e.getValue(), Integer::sum);
            }
        }
    }

    /**
     * Day caches often include only that calendar day — prepend prior-day M1 from neighbor file.
     */
    private static List<TrendBar> mergeWarmupFromNeighborCaches(List<TrendBar> m1, LocalDate day, String secid) {
        List<TrendBar> out = new ArrayList<>();
        for (int back = 1; back <= 3; back++) {
            Path prev = Path.of("data", "br-m1-" + day.minusDays(back) + ".json");
            if (!Files.isRegularFile(prev)) {
                continue;
            }
            try {
                if (!secid.equals(BrM5DayReplay.cachedSecid(prev))) {
                    continue;
                }
                out.addAll(BrM5DayReplay.loadCachedM1(prev));
            } catch (Exception ignored) {
                // skip bad cache
            }
        }
        Path warmup = Path.of("data", "br-m1-warmup.json");
        if (out.isEmpty() && Files.isRegularFile(warmup)) {
            try {
                out.addAll(BrM5DayReplay.loadCachedM1(warmup));
            } catch (Exception ignored) {
                // ignore
            }
        }
        out.addAll(m1);
        out.sort(Comparator.comparing(TrendBar::time));
        // de-dupe by time
        List<TrendBar> dedup = new ArrayList<>();
        LocalDateTime last = null;
        for (TrendBar b : out) {
            if (last != null && b.time().equals(last)) {
                continue;
            }
            dedup.add(b);
            last = b.time();
        }
        return dedup;
    }

    private static final class ModeAgg {
        int n;
        int wins;
        int losses;
        double pnl;
        double grossWin;
        double grossLoss;

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("trades", n);
            m.put("wins", wins);
            m.put("losses", losses);
            m.put("pnlRub", pnl);
            m.put("grossWin", grossWin);
            m.put("grossLoss", grossLoss);
            double pf = grossLoss <= 1e-9 ? (grossWin > 0 ? Double.POSITIVE_INFINITY : 0) : grossWin / grossLoss;
            m.put("profitFactor", Double.isInfinite(pf) ? null : pf);
            m.put("winRatePct", (wins + losses) == 0 ? 0 : 100.0 * wins / (wins + losses));
            return m;
        }
    }
}
