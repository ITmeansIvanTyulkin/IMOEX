package com.moex.cointegration.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moex.cointegration.client.MoexIssClient;
import com.moex.cointegration.product.ProductEdition;
import com.moex.cointegration.product.ProductEditionService;
import com.moex.cointegration.service.InvestChartHistoryStore;
import com.moex.cointegration.service.InvestmentsDeskService;
import com.moex.cointegration.service.InvestmentsFairPaperLiveService;
import com.moex.cointegration.service.InvestmentsPaperJournalService;
import com.moex.cointegration.service.InvestmentsSettingsService;
import com.moex.cointegration.upsell.UpsellAccess;
import com.moex.cointegration.upsell.UpsellService;
import com.moex.trinity.investments.InvestmentsSignal;
import com.moex.trinity.marketdata.PlainHttp;
import com.moex.trinity.marketdata.TInvestBrokerMarketData;
import com.moex.trinity.marketdata.TInvestCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/investments")
@ConditionalOnProperty(prefix = "imoex.strategies.investments", name = "enabled", havingValue = "true", matchIfMissing = true)
public class InvestmentsController {

    private static final Logger log = LoggerFactory.getLogger(InvestmentsController.class);
    private static final ObjectMapper ISS_JSON = new ObjectMapper();
    private static final long CHART_CACHE_MS = 120_000L;

    private final ConcurrentHashMap<String, CachedChart> chartCache = new ConcurrentHashMap<>();

    private final InvestmentsSettingsService settings;
    private final InvestmentsDeskService desk;
    private final InvestmentsFairPaperLiveService fairPaper;
    private final InvestmentsPaperJournalService journal;
    private final InvestChartHistoryStore historyStore;
    private final ObjectProvider<TInvestBrokerMarketData> marketData;
    private final ObjectProvider<MoexIssClient> iss;
    private final ObjectProvider<UpsellService> upsell;
    private final ObjectProvider<ProductEditionService> productEdition;

    public InvestmentsController(
            InvestmentsSettingsService settings,
            InvestmentsDeskService desk,
            InvestmentsFairPaperLiveService fairPaper,
            InvestmentsPaperJournalService journal,
            InvestChartHistoryStore historyStore,
            ObjectProvider<TInvestBrokerMarketData> marketData,
            ObjectProvider<MoexIssClient> iss,
            ObjectProvider<UpsellService> upsell,
            ObjectProvider<ProductEditionService> productEdition
    ) {
        this.settings = settings;
        this.desk = desk;
        this.fairPaper = fairPaper;
        this.journal = journal;
        this.historyStore = historyStore;
        this.marketData = marketData;
        this.iss = iss;
        this.upsell = upsell;
        this.productEdition = productEdition;
    }

    /**
     * Local multi-year history warm-up entitlement.
     * Allowed for: root/operator desk, any active subscription/trial, or when commercial gate is off.
     */
    @GetMapping("/history-cache")
    public Map<String, Object> historyCacheAccess() {
        Map<String, Object> out = new LinkedHashMap<>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean loggedIn = auth != null && auth.isAuthenticated()
                && auth.getName() != null
                && !"anonymousUser".equalsIgnoreCase(auth.getName());
        boolean operatorRole = loggedIn && auth.getAuthorities() != null
                && auth.getAuthorities().stream()
                .anyMatch(a -> a != null && "ROLE_OPERATOR".equalsIgnoreCase(a.getAuthority()));

        String phase = "OFF";
        boolean trialOrSub = false;
        boolean upsellOn = false;
        UpsellService upsellSvc = upsell.getIfAvailable();
        if (upsellSvc != null) {
            UpsellAccess access = upsellSvc.access();
            upsellOn = access.enabled();
            phase = access.phase() == null ? "OFF" : access.phase();
            // Any active commercial unlock: reverse trial or simulated paid Full Core.
            trialOrSub = access.hasFullCoreAccess()
                    || "TRIAL".equalsIgnoreCase(phase);
        } else {
            trialOrSub = true;
        }

        ProductEdition edition = null;
        ProductEditionService pe = productEdition.getIfAvailable();
        if (pe != null) {
            edition = pe.current();
        }
        // Any SKU above bare archive counts as an activated subscription for history cache.
        boolean editionSub = edition == ProductEdition.PAIRS_TREND || edition == ProductEdition.FULL
                || edition == ProductEdition.PAIRS;

        boolean gateOff = !upsellOn;
        boolean allowed = loggedIn && (operatorRole || trialOrSub || editionSub || gateOff);
        String reason = !loggedIn ? "login"
                : operatorRole ? "operator"
                : trialOrSub ? "subscription"
                : editionSub ? "edition"
                : gateOff ? "gate-off"
                : "locked";

        out.put("allowed", allowed);
        out.put("years", InvestChartHistoryStore.MAX_D1_YEARS);
        out.put("h1Days", InvestChartHistoryStore.MAX_H1_DAYS);
        out.put("m5Days", InvestChartHistoryStore.MAX_M5_DAYS);
        out.put("m15Days", InvestChartHistoryStore.MAX_M15_DAYS);
        // Per-machine archive on this host (not Supabase). Survives app reinstall when data-dir=user.
        out.put("shared", false);
        out.put("localArchive", true);
        out.put("store", historyStore.ready() ? "sqlite-local" : "memory");
        out.put("dataDir", historyStore.dbPath().getParent() == null
                ? ""
                : historyStore.dbPath().getParent().toAbsolutePath().toString());
        out.put("db", historyStore.dbPath().toAbsolutePath().toString());
        out.put("reason", reason);
        out.put("phase", phase);
        out.put("edition", edition == null ? "" : edition.name());
        out.put("user", loggedIn ? auth.getName() : "");
        return out;
    }

    @GetMapping("/settings")
    public Map<String, Object> settings() {
        return settings.view();
    }

    @PostMapping("/settings/auto-execution")
    public Map<String, Object> toggleAuto(@RequestBody(required = false) ToggleBody body) {
        boolean next = body != null && body.enabled() != null
                ? body.enabled()
                : !settings.autoExecution();
        return settings.setAutoExecution(next);
    }

    @PostMapping("/settings/live-execution")
    public Map<String, Object> toggleLive(@RequestBody(required = false) ToggleBody body) {
        boolean next = body != null && body.enabled() != null
                ? body.enabled()
                : !settings.liveExecution();
        return settings.setLiveExecution(next);
    }

    @GetMapping("/desk")
    public Map<String, Object> desk() {
        return desk.desk();
    }

    /** Fast catalog for chart panes (no full universe evaluate). */
    @GetMapping("/watchlist")
    public Map<String, Object> watchlist() {
        return desk.watchlistView();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("settings", settings.view());
        m.put("fairPaper", fairPaper.snapshot());
        m.put("journal", journal.view());
        return m;
    }

    @GetMapping("/evaluate/{ticker}")
    public Map<String, Object> evaluate(@PathVariable String ticker) {
        InvestmentsSignal sig = desk.evaluateTicker(ticker);
        return sig.toDeskRow();
    }

    /** Daily / H1 candles for the investments chart terminal (fast ISS, then T-Invest). */
    @GetMapping("/chart/{ticker}")
    public Map<String, Object> chart(
            @PathVariable String ticker,
            @RequestParam(defaultValue = "D1") String tf,
            @RequestParam(defaultValue = "0") int years
    ) {
        String t = ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
        String tfNorm = tf == null ? "D1" : tf.trim().toUpperCase(Locale.ROOT);
        int yearsClamped = Math.max(0, Math.min(years, 10));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instrument", t);
        out.put("timeframe", tfNorm);
        out.put("years", yearsClamped);
        List<Map<String, Object>> bars = new ArrayList<>();
        String source = null;

        String cacheKey = t + "|" + tfNorm + "|y" + yearsClamped;
        CachedChart hit = chartCache.get(cacheKey);
        if (hit != null && !hit.bars().isEmpty()
                && System.currentTimeMillis() - hit.at() < CHART_CACHE_MS) {
            out.put("source", hit.source());
            out.put("bars", hit.bars());
            out.put("cached", true);
            out.put("profile", List.of());
            out.put("footprint", List.of());
            out.put("chartMarkers", List.of());
            return out;
        }

        // Local per-machine SQLite archive (max depth). W1/MN reuse D1 store.
        String storeTf = ("W1".equals(tfNorm) || "MN".equals(tfNorm)) ? "D1" : tfNorm;
        int fetchYears = Math.max(yearsClamped, maxFetchYears(tfNorm));
        InvestChartHistoryStore.SeriesSnapshot archived = historyStore.load(t, storeTf, fetchYears);
        if (historyStore.covers(archived, storeTf, fetchYears)) {
            bars = new ArrayList<>(archived.bars());
            source = archived.source() + "+DB";
        }

        if (bars.isEmpty() && !t.isBlank()) {
            try {
                bars.addAll(fetchIssShareCandlesFast(t, storeTf, fetchYears));
                if (!bars.isEmpty()) {
                    source = "ISS";
                }
            } catch (Exception ex) {
                log.debug("ISS chart {}: {}", t, ex.getMessage());
            }
        }

        if (bars.isEmpty() && !t.isBlank()) {
            TInvestBrokerMarketData owned = null;
            TInvestBrokerMarketData md = marketData.getIfAvailable();
            try {
                if (md == null) {
                    TInvestCredentials creds = TInvestCredentials.resolve();
                    if (creds.present()) {
                        owned = new TInvestBrokerMarketData(creds);
                        md = owned;
                    }
                }
                if (md != null) {
                    String figi = md.resolveShareFigi(t);
                    LocalDate till = LocalDate.now();
                    boolean m5 = "M5".equals(storeTf) || "M15".equals(storeTf);
                    boolean hour = "H1".equals(storeTf);
                    int daySpan = InvestChartHistoryStore.maxDaysForTf(storeTf);
                    if (fetchYears > 0 && !m5) {
                        daySpan = Math.min(daySpan, fetchYears * 365);
                    }
                    LocalDate from = till.minusDays(daySpan);
                    List<TInvestBrokerMarketData.BrokerCandle> raw = m5
                            ? md.fetchM5Candles(figi, from, till)
                            : hour
                            ? md.fetchHourCandles(figi, from, till)
                            : md.fetchDayCandles(figi, from, till);
                    for (TInvestBrokerMarketData.BrokerCandle c : raw) {
                        bars.add(barMap(c.time().toString(), c.open(), c.high(), c.low(), c.close(), c.volume()));
                    }
                    if (!bars.isEmpty()) {
                        source = "T_INVEST";
                    }
                }
            } catch (Exception ex) {
                log.debug("T-Invest chart {}: {}", t, ex.getMessage());
            } finally {
                if (owned != null) {
                    try {
                        owned.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        if (!bars.isEmpty()) {
            // Persist into local archive; re-load so older upserted bars stay in the max series.
            if (source != null && !source.endsWith("+DB")) {
                historyStore.save(t, storeTf, source, bars);
                InvestChartHistoryStore.SeriesSnapshot merged =
                        historyStore.load(t, storeTf, yearsClamped > 0 ? yearsClamped : fetchYears);
                if (merged != null && !merged.bars().isEmpty()) {
                    bars = new ArrayList<>(merged.bars());
                    source = source + "+DB";
                }
            } else if (yearsClamped > 0 && yearsClamped < fetchYears) {
                String since = LocalDate.now().minusDays(yearsClamped * 365L).toString();
                List<Map<String, Object>> sliced = new ArrayList<>();
                for (Map<String, Object> b : bars) {
                    Object tm = b.get("time");
                    if (tm != null && String.valueOf(tm).compareTo(since) >= 0) {
                        sliced.add(b);
                    }
                }
                bars = sliced;
            }
            chartCache.put(cacheKey, new CachedChart(List.copyOf(bars), source, System.currentTimeMillis()));
        }
        out.put("source", source == null ? "NONE" : source);
        out.put("localArchive", true);
        out.put("storeTf", storeTf);
        out.put("bars", bars);
        out.put("profile", List.of());
        out.put("footprint", List.of());
        out.put("chartMarkers", List.of());
        return out;
    }

    /** Always warm the local archive to the max depth for the TF family. */
    private static int maxFetchYears(String tfNorm) {
        return switch (tfNorm) {
            case "M5", "M15" -> 0;
            case "H1" -> 1;
            default -> InvestChartHistoryStore.MAX_D1_YEARS;
        };
    }

    /**
     * One/two ISS pages with a short PlainHttp timeout — never the shared RestTemplate
     * (15s connect × 3 retries × 60s read), which blanks the desk while MOEX hangs.
     */
    private static List<Map<String, Object>> fetchIssShareCandlesFast(
            String ticker, String tfNorm, int years
    ) throws Exception {
        boolean m5 = "M5".equals(tfNorm);
        boolean m15 = "M15".equals(tfNorm);
        boolean hour = "H1".equals(tfNorm);
        boolean longHist = "W1".equals(tfNorm) || "MN".equals(tfNorm)
                || "WEEK".equals(tfNorm) || "MONTH".equals(tfNorm)
                || years >= 3;
        LocalDate till = LocalDate.now();
        int daySpan = InvestChartHistoryStore.maxDaysForTf(tfNorm);
        if (years > 0 && !m5 && !m15) {
            daySpan = Math.min(daySpan, Math.max(years * 365, hour ? InvestChartHistoryStore.MAX_H1_DAYS : years * 365));
        }
        LocalDate from = till.minusDays(daySpan);
        int interval = m5 ? 5 : m15 ? 15 : hour ? 60 : 24;
        int maxPages = years >= 3 || longHist || hour ? 12 : 2;
        List<Map<String, Object>> bars = new ArrayList<>();
        int start = 0;
        for (int page = 0; page < maxPages; page++) {
            String url = "https://iss.moex.com/iss/engines/stock/markets/shares/boards/TQBR/securities/"
                    + ticker + "/candles.json?from=" + from + "&till=" + till
                    + "&interval=" + interval + "&start=" + start + "&iss.meta=off";
            String body = PlainHttp.get(url, years >= 3 ? 4000 : 2500, "TRINITY-invest-chart/1.0");
            JsonNode root = ISS_JSON.readTree(body);
            JsonNode columns = root.path("candles").path("columns");
            JsonNode data = root.path("candles").path("data");
            if (!data.isArray() || data.isEmpty()) {
                break;
            }
            int openIdx = issCol(columns, "open");
            int highIdx = issCol(columns, "high");
            int lowIdx = issCol(columns, "low");
            int closeIdx = issCol(columns, "close");
            int volIdx = issCol(columns, "volume");
            int beginIdx = issCol(columns, "begin");
            for (JsonNode row : data) {
                if (!row.isArray()) {
                    continue;
                }
                String begin = beginIdx >= 0 && row.size() > beginIdx ? row.get(beginIdx).asText() : "";
                if (begin == null || begin.isBlank()) {
                    continue;
                }
                bars.add(barMap(
                        begin,
                        issNum(row, openIdx),
                        issNum(row, highIdx),
                        issNum(row, lowIdx),
                        issNum(row, closeIdx),
                        issNum(row, volIdx)
                ));
            }
            if (data.size() < 500) {
                break;
            }
            start += data.size();
        }
        return bars;
    }

    private static int issCol(JsonNode columns, String name) {
        if (columns == null || !columns.isArray()) {
            return -1;
        }
        for (int i = 0; i < columns.size(); i++) {
            if (name.equalsIgnoreCase(columns.get(i).asText())) {
                return i;
            }
        }
        return -1;
    }

    private static double issNum(JsonNode row, int idx) {
        if (idx < 0 || row == null || idx >= row.size()) {
            return 0;
        }
        return row.get(idx).asDouble(0);
    }

    private record CachedChart(List<Map<String, Object>> bars, String source, long at) {
    }

    private static Map<String, Object> barMap(
            String time, double open, double high, double low, double close, double volume
    ) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time", time);
        m.put("open", open);
        m.put("high", high);
        m.put("low", low);
        m.put("close", close);
        m.put("volume", volume);
        return m;
    }

    @PostMapping("/fair-paper/tick")
    public Map<String, Object> forceTick() {
        fairPaper.tickOnce();
        return fairPaper.snapshot();
    }

    public record ToggleBody(Boolean enabled) {
    }
}
