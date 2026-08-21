package com.moex.trinity.marketdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Public (non-ISS, non-T-Invest) energy context: CME WTI via Yahoo {@code CL=F},
 * EIA stocks via FRED, optional street consensus file/URL (not Bloomberg Terminal),
 * CFTC COT (SODA), and 3-2-1 crack from Yahoo CL/RB/HO. Fail-soft.
 */
public final class PublicEnergyContext {

    private static final Logger log = LoggerFactory.getLogger(PublicEnergyContext.class);
    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private static final String CL_URL =
            "https://query1.finance.yahoo.com/v8/finance/chart/CL=F?interval=1h&range=5d";
    private static final String RB_URL =
            "https://query1.finance.yahoo.com/v8/finance/chart/RB=F?interval=1d&range=5d";
    private static final String HO_URL =
            "https://query1.finance.yahoo.com/v8/finance/chart/HO=F?interval=1d&range=5d";
    private static final String EIA_CSV =
            "https://fred.stlouisfed.org/graph/fredgraph.csv?id=WCESTUS1";
    /** Legacy futures-only COT. */
    private static final String CFTC_COT =
            "https://publicreporting.cftc.gov/resource/6dca-aqww.json"
                    + "?$where=market_and_exchange_names%20like%20%27%25CRUDE%20OIL%2C%20LIGHT%20SWEET%25%27"
                    + "&$order=report_date_as_yyyy_mm_dd%20DESC&$limit=6";
    private static final long CL_CACHE_MS = 5 * 60_000L;
    private static final long EIA_CACHE_MS = 6 * 60 * 60_000L;
    private static final long CFTC_CACHE_MS = 6 * 60 * 60_000L;
    private static final long CRACK_CACHE_MS = 15 * 60_000L;

    private final AtomicReference<Cached<ClSnapshot>> cl = new AtomicReference<>();
    private final AtomicReference<Cached<EiaPrint>> eia = new AtomicReference<>();
    private final AtomicReference<Cached<CotSnapshot>> cot = new AtomicReference<>();
    private final AtomicReference<Cached<CrackSnapshot>> crack = new AtomicReference<>();
    private final Path consensusFile;
    private final String consensusUrl;

    public PublicEnergyContext() {
        this(Path.of("data", "eia-consensus.json"), null);
    }

    public PublicEnergyContext(Path consensusFile, String consensusUrl) {
        this.consensusFile = consensusFile;
        this.consensusUrl = consensusUrl == null || consensusUrl.isBlank() ? null : consensusUrl.trim();
    }

    public ClSnapshot wti() {
        Cached<ClSnapshot> hit = cl.get();
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.atMs < CL_CACHE_MS && hit.value != null) {
            return hit.value;
        }
        try {
            String body = get(CL_URL);
            ClSnapshot snap = parseYahooCl(body, Instant.now());
            cl.set(new Cached<>(now, snap));
            return snap;
        } catch (Exception ex) {
            log.debug("WTI CL=F fetch failed: {}", ex.toString());
            ClSnapshot miss = hit != null && hit.value != null ? hit.value : ClSnapshot.unknown();
            cl.set(new Cached<>(now, miss));
            return miss;
        }
    }

    public EiaPrint crudeStocks() {
        Cached<EiaPrint> hit = eia.get();
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.atMs < EIA_CACHE_MS && hit.value != null) {
            return applyConsensus(hit.value);
        }
        try {
            String body = get(EIA_CSV);
            EiaPrint print = parseFredWeekly(body);
            eia.set(new Cached<>(now, print));
            return applyConsensus(print);
        } catch (Exception ex) {
            log.debug("EIA/FRED WCESTUS1 fetch failed: {}", ex.toString());
            EiaPrint miss = hit != null && hit.value != null ? hit.value : EiaPrint.unknown();
            eia.set(new Cached<>(now, miss));
            return applyConsensus(miss);
        }
    }

    public CotSnapshot cotCrude() {
        Cached<CotSnapshot> hit = cot.get();
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.atMs < CFTC_CACHE_MS && hit.value != null) {
            return hit.value;
        }
        try {
            String body = get(CFTC_COT);
            CotSnapshot snap = parseCotJson(body);
            cot.set(new Cached<>(now, snap));
            return snap;
        } catch (Exception ex) {
            log.debug("CFTC COT fetch failed: {}", ex.toString());
            CotSnapshot miss = hit != null && hit.value != null ? hit.value : CotSnapshot.unknown();
            cot.set(new Cached<>(now, miss));
            return miss;
        }
    }

    public CrackSnapshot crack321() {
        Cached<CrackSnapshot> hit = crack.get();
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.atMs < CRACK_CACHE_MS && hit.value != null) {
            return hit.value;
        }
        try {
            double clLast = wti().last();
            double rb = extractNumber(get(RB_URL), "\"regularMarketPrice\":");
            double ho = extractNumber(get(HO_URL), "\"regularMarketPrice\":");
            CrackSnapshot snap = CrackSnapshot.of(clLast, rb, ho);
            crack.set(new Cached<>(now, snap));
            return snap;
        } catch (Exception ex) {
            log.debug("Crack 3-2-1 fetch failed: {}", ex.toString());
            CrackSnapshot miss = hit != null && hit.value != null ? hit.value : CrackSnapshot.unknown();
            crack.set(new Cached<>(now, miss));
            return miss;
        }
    }

    private EiaPrint applyConsensus(EiaPrint base) {
        if (base == null || !base.ok()) {
            return base == null ? EiaPrint.unknown() : base;
        }
        Consensus c = loadConsensus();
        if (c == null || !Double.isFinite(c.kbbl())) {
            return base;
        }
        double surprise = base.wowKbbl() - c.kbbl();
        return new EiaPrint(
                base.week(), base.stocksKbbl(), base.wowKbbl(), c.kbbl(), surprise, true,
                c.source(), true);
    }

    private Consensus loadConsensus() {
        try {
            if (consensusUrl != null) {
                String body = get(consensusUrl);
                return parseConsensusJson(body, "url");
            }
            if (consensusFile != null && Files.isRegularFile(consensusFile)) {
                return parseConsensusJson(Files.readString(consensusFile), "file:" + consensusFile);
            }
        } catch (Exception ex) {
            log.debug("EIA consensus load failed: {}", ex.toString());
        }
        return null;
    }

    static Consensus parseConsensusJson(String json, String sourceHint) {
        if (json == null || json.isBlank()) {
            return null;
        }
        double kbbl = extractNumber(json, "\"kbbl\":");
        if (!Double.isFinite(kbbl)) {
            kbbl = extractNumber(json, "\"consensusKbbl\":");
        }
        if (!Double.isFinite(kbbl)) {
            return null;
        }
        String src = extractString(json, "\"source\":");
        if (src == null || src.isBlank()) {
            src = sourceHint;
        }
        return new Consensus(kbbl, src);
    }

    public static ClSnapshot parseYahooCl(String json, Instant now) {
        if (json == null || json.isBlank() || !json.contains("regularMarketPrice")) {
            return ClSnapshot.unknown();
        }
        double last = extractNumber(json, "\"regularMarketPrice\":");
        double prev = extractNumber(json, "\"chartPreviousClose\":");
        if (!(last > 0)) {
            last = extractNumber(json, "\"previousClose\":");
        }
        if (!(last > 0)) {
            return ClSnapshot.unknown();
        }
        double chg = (prev > 0) ? last - prev : Double.NaN;
        double pct = (prev > 0) ? 100.0 * chg / prev : Double.NaN;
        String dir = !Double.isFinite(pct) || Math.abs(pct) < 0.15
                ? "FLAT"
                : (pct > 0 ? "UP" : "DOWN");
        return new ClSnapshot(last, prev, chg, pct, dir, now, true);
    }

    public static EiaPrint parseFredWeekly(String csv) {
        if (csv == null || csv.isBlank()) {
            return EiaPrint.unknown();
        }
        List<double[]> rows = new ArrayList<>();
        for (String line : csv.split("\\R")) {
            if (line == null || line.isBlank() || line.startsWith("DATE") || line.startsWith("observation")) {
                continue;
            }
            String[] p = line.split(",");
            if (p.length < 2) {
                continue;
            }
            try {
                LocalDate d = LocalDate.parse(p[0].trim());
                String raw = p[1].trim();
                if (raw.isBlank() || ".".equals(raw)) {
                    continue;
                }
                double v = Double.parseDouble(raw);
                rows.add(new double[]{d.toEpochDay(), v});
            } catch (Exception ignored) {
                // skip
            }
        }
        if (rows.size() < 2) {
            return EiaPrint.unknown();
        }
        double[] last = rows.get(rows.size() - 1);
        double[] prev = rows.get(rows.size() - 2);
        LocalDate week = LocalDate.ofEpochDay((long) last[0]);
        double wow = last[1] - prev[1];
        double avg4 = 0;
        int n = 0;
        for (int i = Math.max(1, rows.size() - 4); i < rows.size(); i++) {
            avg4 += rows.get(i)[1] - rows.get(i - 1)[1];
            n++;
        }
        double expected = n > 0 ? avg4 / n : 0;
        double surprise = wow - expected;
        return new EiaPrint(week, last[1], wow, expected, surprise, true, "FRED_4W_AVG", false);
    }

    public static CotSnapshot parseCotJson(String json) {
        if (json == null || json.isBlank() || !json.contains("noncomm_positions")) {
            return CotSnapshot.unknown();
        }
        int longI = json.indexOf("\"noncomm_positions_long_all\"");
        if (longI < 0) {
            return CotSnapshot.unknown();
        }
        double lng = extractNumber(json, "\"noncomm_positions_long_all\":");
        double sh = extractNumber(json, "\"noncomm_positions_short_all\":");
        if (!Double.isFinite(lng) || !Double.isFinite(sh)) {
            lng = extractQuotedNumber(json, "noncomm_positions_long_all");
            sh = extractQuotedNumber(json, "noncomm_positions_short_all");
        }
        LocalDate week = null;
        String dateRaw = extractString(json, "\"report_date_as_yyyy_mm_dd\":");
        if (dateRaw != null && dateRaw.length() >= 10) {
            try {
                week = LocalDate.parse(dateRaw.substring(0, 10));
            } catch (Exception ignored) {
                // skip
            }
        }
        double net = lng - sh;
        return new CotSnapshot(week, lng, sh, net, true);
    }

    private static double extractQuotedNumber(String json, String key) {
        String v = extractString(json, "\"" + key + "\":");
        if (v == null) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(v.replace(",", "").trim());
        } catch (Exception ex) {
            return Double.NaN;
        }
    }

    private static String extractString(String json, String key) {
        int i = json.indexOf(key);
        if (i < 0) {
            return null;
        }
        int a = i + key.length();
        while (a < json.length() && (json.charAt(a) == ' ' || json.charAt(a) == '"')) {
            a++;
        }
        if (a >= json.length()) {
            return null;
        }
        // if we consumed opening quote already
        int start = a;
        if (json.charAt(Math.max(0, a - 1)) == '"') {
            int end = json.indexOf('"', a);
            return end > a ? json.substring(a, end) : null;
        }
        // value may start with quote
        if (json.charAt(a) == '"') {
            int end = json.indexOf('"', a + 1);
            return end > a ? json.substring(a + 1, end) : null;
        }
        int b = start;
        while (b < json.length()) {
            char c = json.charAt(b);
            if (c == ',' || c == '}' || c == ']' || Character.isWhitespace(c)) {
                break;
            }
            b++;
        }
        return json.substring(start, b).replace("\"", "");
    }

    private static String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(2))
                .header("User-Agent", "TRINITY-desk/1.0")
                .GET()
                .build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + res.statusCode());
        }
        return res.body();
    }

    private static double extractNumber(String json, String key) {
        int i = json.indexOf(key);
        if (i < 0) {
            return Double.NaN;
        }
        int a = i + key.length();
        while (a < json.length() && (json.charAt(a) == ' ' || json.charAt(a) == '"')) {
            a++;
        }
        int b = a;
        while (b < json.length()) {
            char c = json.charAt(b);
            if ((c >= '0' && c <= '9') || c == '.' || c == '-' || c == 'e' || c == 'E') {
                b++;
            } else {
                break;
            }
        }
        try {
            return Double.parseDouble(json.substring(a, b));
        } catch (Exception ex) {
            return Double.NaN;
        }
    }

    public record ClSnapshot(
            double last,
            double previousClose,
            double change,
            double changePct,
            String direction,
            Instant asOf,
            boolean ok
    ) {
        public static ClSnapshot unknown() {
            return new ClSnapshot(Double.NaN, Double.NaN, Double.NaN, Double.NaN, "FLAT", null, false);
        }

        public String ruBrief() {
            if (!ok || !(last > 0)) {
                return "WTI (CL) сейчас недоступен — контекст без американской ноги.";
            }
            String ch = Double.isFinite(changePct)
                    ? String.format(Locale.ROOT, "%+.2f%%", changePct)
                    : "—";
            return "WTI CL=F " + String.format(Locale.ROOT, "%.2f", last)
                    + " (" + ch + " к prev close, " + direction + "). "
                    + "Это другой часовой пояс: ночной ход CL часто уже сидит в утреннем гэпе BR. "
                    + "Exclusive сторону не меняем — только контекст открытия дня.";
        }
    }

    public record EiaPrint(
            LocalDate week,
            double stocksKbbl,
            double wowKbbl,
            double expectedWowKbbl,
            double surpriseKbbl,
            boolean ok,
            String consensusSource,
            boolean streetConsensus
    ) {
        public EiaPrint(
                LocalDate week,
                double stocksKbbl,
                double wowKbbl,
                double expectedWowKbbl,
                double surpriseKbbl,
                boolean ok
        ) {
            this(week, stocksKbbl, wowKbbl, expectedWowKbbl, surpriseKbbl, ok, "FRED_4W_AVG", false);
        }

        public static EiaPrint unknown() {
            return new EiaPrint(null, Double.NaN, Double.NaN, Double.NaN, Double.NaN, false, null, false);
        }

        public boolean largeSurprise(double thresholdKbbl) {
            return ok && Double.isFinite(surpriseKbbl) && Math.abs(surpriseKbbl) >= thresholdKbbl;
        }

        public String ruLine() {
            if (!ok || week == null) {
                return "EIA crude stocks: цифры нет (FRED недоступен) — держим календарный blackout.";
            }
            String vs = streetConsensus
                    ? ("vs street " + Math.round(expectedWowKbbl) + " (" + consensusSource + ")")
                    : ("vs ср.4н " + Math.round(expectedWowKbbl));
            return "EIA WCESTUS1 нед. " + week
                    + " запасы " + Math.round(stocksKbbl)
                    + " kbbl, WoW " + Math.round(wowKbbl)
                    + " " + vs
                    + " (surprise " + Math.round(surpriseKbbl) + "). "
                    + (streetConsensus
                    ? "Street consensus из файла/URL — не Bloomberg Terminal."
                    : "Без street consensus — прокси ср.4н, не Bloomberg.");
        }
    }

    public record CotSnapshot(
            LocalDate reportDate,
            double noncommLong,
            double noncommShort,
            double noncommNet,
            boolean ok
    ) {
        public static CotSnapshot unknown() {
            return new CotSnapshot(null, Double.NaN, Double.NaN, Double.NaN, false);
        }

        public boolean extreme(double absNetThreshold) {
            return ok && Double.isFinite(noncommNet) && Math.abs(noncommNet) >= absNetThreshold;
        }

        public String ruLine() {
            if (!ok) {
                return "CFTC COT crude: нет данных (publicreporting.cftc.gov).";
            }
            return "CFTC COT Light Sweet " + reportDate
                    + " noncomm net " + Math.round(noncommNet)
                    + " (L " + Math.round(noncommLong) + " / S " + Math.round(noncommShort) + ").";
        }
    }

    public record CrackSnapshot(
            double cl,
            double rb,
            double ho,
            double crack321Usd,
            boolean ok
    ) {
        public static CrackSnapshot unknown() {
            return new CrackSnapshot(Double.NaN, Double.NaN, Double.NaN, Double.NaN, false);
        }

        public static CrackSnapshot of(double cl, double rb, double ho) {
            if (!(cl > 0) || !(rb > 0) || !(ho > 0)) {
                return unknown();
            }
            // 3-2-1: (2*RB*42 + 1*HO*42 - 3*CL) / 3
            double v = (2.0 * rb * 42.0 + ho * 42.0 - 3.0 * cl) / 3.0;
            return new CrackSnapshot(cl, rb, ho, v, true);
        }

        public boolean extreme(double absUsd) {
            return ok && Double.isFinite(crack321Usd) && Math.abs(crack321Usd) >= absUsd;
        }

        public String ruLine() {
            if (!ok) {
                return "Маржу нефтезаводов не считаем: нет публичных цен нефти, бензина и мазута.";
            }
            return String.format(Locale.ROOT,
                    "Нефтезаводы сейчас зарабатывают около $%.0f с барреля (нефть %.2f, бензин %.3f, мазут %.3f). "
                            + "Цифры Yahoo, не стакан американской биржи.",
                    crack321Usd, cl, rb, ho);
        }
    }

    public record Consensus(double kbbl, String source) {
    }

    private record Cached<T>(long atMs, T value) {
    }
}
