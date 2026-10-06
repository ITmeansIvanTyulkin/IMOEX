package com.moex.cointegration.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Investments chart terminal (moved from Trend): dock + equity chart API. */
class TrendChartsTerminalTvContractTest {

    private static String read(String rel) throws Exception {
        Path p = Path.of(rel);
        if (!Files.isRegularFile(p)) {
            p = Path.of("trinity-app/" + rel);
        }
        assertTrue(Files.isRegularFile(p), "missing " + rel);
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    void terminalHtmlHasDockControls() throws Exception {
        String html = read("src/main/resources/investments-desk.html");
        assertTrue(html.contains("id=\"charts-tf\""), "TF strip missing");
        assertTrue(html.contains("id=\"charts-alert-add\""), "alert add missing");
        assertTrue(html.contains("id=\"charts-tpl-save\""), "template save missing");
        assertTrue(html.contains("id=\"charts-tpl-apply\""), "template apply missing");
        assertTrue(html.contains("id=\"charts-watchlist\""), "watchlist missing");
        assertTrue(html.contains("id=\"charts-dom\""), "DOM missing");
        assertTrue(html.contains("id=\"charts-tape\""), "tape missing");
        assertTrue(html.contains("id=\"invest-ops-panel\""), "invest ops panel missing");
        assertTrue(html.contains("data-nav-strategy=\"invest\""), "must be invest strategy");
        assertTrue(html.contains("mode-switch-track"), "auto must be a mode switch");
        assertTrue(html.contains("id=\"invest-guide-open\""), "invest how-the-robot-trades missing");
    }

    @Test
    void investAutoHydratesFromSettingsNotDeskScan() throws Exception {
        String js = read("src/main/resources/static/js/investments-desk.js");
        assertTrue(js.contains("/api/investments/settings"), "must load persisted auto before desk scan");
        assertTrue(js.contains("function hydrateAuto"), "hydrateAuto missing");
        assertTrue(js.contains("function paintAuto"), "paintAuto missing");
        assertTrue(js.contains("function bindGuide"), "invest guide modal must bind");
        assertTrue(js.contains("is-auto"), "mode-switch is-auto class missing");
        assertFalse(js.contains("fromDesk"), "stale desk snapshot must not snap the toggle back");
    }

    @Test
    void plaquesShowOnEveryStrategyDesk() throws Exception {
        String js = read("src/main/resources/static/js/trinity-status-plaques.js");
        assertTrue(js.contains("function pathWantsPlaques"), "must show plaques on strategy desks");
        assertTrue(js.contains("view/investments"), "investments is a strategy desk");
        assertTrue(js.contains("calendar-arb"), "calendar-arb plaque click");
        assertFalse(js.contains("trinity.supabase.access_token"),
                "must not send a stored Bearer that 401s over the desk cookie");
        String html = read("src/main/java/com/moex/cointegration/web/AnalysisHtmlRenderer.java");
        assertTrue(html.contains("trinity-status-plaques.js"), "all pages must load plaques");
    }

    @Test
    void terminalJsWiresDockAndEquityApi() throws Exception {
        String js = read("src/main/resources/static/js/investments-charts-terminal.js");
        assertTrue(js.contains("charts-alert-add"), "alert click missing");
        assertTrue(js.contains("charts-tpl-save"), "template save click missing");
        assertTrue(js.contains("charts-tpl-apply"), "template apply click missing");
        assertTrue(js.contains("charts-tf-btn"), "TF click missing");
        assertTrue(js.contains("cur.templates"), "layout templates not persisted");
        assertTrue(js.contains("function applyTf"), "applyTf missing");
        assertTrue(js.contains("buildRenko"), "renko missing");
        assertTrue(js.contains("buildRangeBars"), "range missing");
        assertTrue(js.contains("ingestPrint"), "footprint ingest missing");
        assertTrue(js.contains("mergeDomBook"), "terminal DOM must keep both shelves");
        assertTrue(js.contains("/api/investments/chart/"), "equity chart API missing");
        assertTrue(js.contains("/api/investments/watchlist"), "invest watchlist bootstrap missing");
        assertTrue(js.contains("function openInstrument"), "clicking robot universe must open chart");
        assertTrue(js.contains("MAX_OPEN_PANES"), "grid stays capped");
        assertTrue(js.contains("deskVerdict"), "watchlist shows robot verdict");
        assertTrue(js.contains("TrinityInvestCharts"), "desk candidates must open the same chart");
        assertFalse(js.contains("/api/trend/desk"), "must not load FORTS trend desk");
        assertTrue(js.contains("function deskBarsMatchPane"), "foreign-family bars must not paint");
        assertTrue(js.contains("investTerminal"), "drawings must persist under investTerminal");
        assertTrue(js.contains("is-chart-fs"), "viewport fullscreen class missing");
        assertTrue(js.contains("sanitizeCandles"), "OHLC sanitize missing");
        assertTrue(js.contains("mergeLayoutDocs"), "session drawings merge missing");
        assertTrue(js.contains("showRecentBars"), "TV-like visible range missing");
        assertTrue(js.contains("body.classList.add(\"invest-chart-fs\")")
                || js.contains("document.body.classList.add(\"invest-chart-fs\")"),
                "fullscreen must lock document scroll");
        assertTrue(js.contains("SYNTHETIC"), "must refuse synthetic bars");
        assertTrue(js.contains("function ensureTfBars"), "TF switch must load D1/H1/M5 bars");
        assertTrue(js.contains("function apiTfFor"), "chart API tf map missing");
        assertTrue(js.contains("aggregateCalendarBars"), "W1/MN aggregation missing");
        assertTrue(js.contains("looksLikeSyntheticStairs"), "must reject stale synthetic cache");
        assertTrue(js.contains("warmLocalHistory"), "multi-year local history warm missing");
        assertTrue(js.contains("/api/investments/history-cache"), "history entitlement check missing");
        assertTrue(js.contains("years="), "deep history years query missing");
        assertTrue(js.contains("tf=M5") || js.contains("\"M5\""), "warm must sync M5 into local archive");
        assertTrue(js.contains("Локальный архив") || js.contains("локальный архив"),
                "UI should mention per-machine local archive");
        assertFalse(js.contains("p.m5Bars || p.bars"), "empty m5Bars must not wipe the series");
        String html = read("src/main/resources/investments-desk.html");
        assertTrue(html.contains("data-tf=\"W1\""), "Week TF button missing");
        assertTrue(html.contains("data-tf=\"MN\""), "Month TF button missing");
    }

    @Test
    void investmentsChartApiDoesNotPaintSynthetic() throws Exception {
        String java = read("src/main/java/com/moex/cointegration/controller/InvestmentsController.java");
        assertFalse(java.contains("syntheticUptrend"), "equity charts must not fall back to fake stairs");
        assertTrue(java.contains("PlainHttp.get"), "ISS must use short-timeout PlainHttp");
        assertTrue(java.contains("TInvestCredentials.resolve"), "T-Invest fallback when ISS is down");
        assertTrue(java.contains("history-cache"), "history-cache entitlement endpoint missing");
        assertTrue(java.contains("InvestChartHistoryStore"), "local SQLite history store missing");
        assertTrue(java.contains("historyStore.save") || java.contains("historyStore.load"),
                "chart API must read/write local history DB");
        assertTrue(java.contains("localArchive"), "history-cache must expose localArchive");
        assertTrue(java.contains("ROLE_OPERATOR") || java.contains("subscription"),
                "warm must cover operator and subscribers");
        String store = read("src/main/java/com/moex/cointegration/service/InvestChartHistoryStore.java");
        assertTrue(store.contains("invest_bars"), "invest_bars table missing");
        assertTrue(store.contains("MAX_D1_YEARS"), "max D1 depth missing");
        String paths = read("src/main/java/com/moex/trinity/TrinityUserDataPaths.java");
        assertTrue(paths.contains("Application Support"), "durable macOS data path missing");
        assertTrue(paths.contains("LOCALAPPDATA") || paths.contains("AppData"),
                "durable Windows data path missing");
        String html = read("src/main/resources/investments-desk.html");
        assertTrue(html.contains("body.invest-chart-fs"), "fullscreen body lock missing");
        assertTrue(html.contains("display: flex !important"), "toolbar must stay in fullscreen");
    }

    @Test
    void kitMergesOneSidedBooks() throws Exception {
        String js = read("src/main/resources/static/js/trinity-chart-kit.js");
        assertTrue(js.contains("function mergeDomBook"), "mergeDomBook helper missing");
        assertTrue(js.contains("shorter full shelf") || js.contains("Empty bids with live asks"),
                "shallower complete shelf must replace prev; one-sided empty must keep opposite");
        assertFalse(js.contains("n.length >= p.length ? n : p"),
                "must not keep stale longer shelf when next is shorter");
        assertTrue(js.contains("access_token"), "tape WS must pass cabinet token on handshake");
        assertTrue(js.contains("instruments: want") || js.contains("instruments:want"),
                "subscribe must send instrument ids, not bare {all:true}");
    }

    @Test
    void kitClusterCellsStayInsideBar() throws Exception {
        String js = read("src/main/resources/static/js/trinity-chart-kit.js");
        String css = read("src/main/resources/static/css/operator.css");
        assertTrue(js.contains("charts-cluster-cell"), "clusters must paint readable cells, not raw ticks");
        assertTrue(js.contains("colW"), "cluster column width must follow barSpacing");
        assertTrue(js.contains("signal-fp-handle"), "footprint range handles must be drawable");
        assertTrue(js.contains("fpDragEnd"), "footprint handles must stretch the pinned range");
        assertTrue(js.contains("clusterZoomTried = true"), "restoring clusters must not steal the user's zoom");
        assertTrue(js.contains("pickSessionProfile"), "session VAP must pick densest ATAS-like histogram");
        assertTrue(js.contains("Prefer authoritative day-tape server profile"),
                "server day-tape profile must win over multi-day footprint VAP");
        assertTrue(js.contains("Always replace the map so instrument switches"),
                "empty footprint payload must clear stale instrument levels");
        assertTrue(js.contains("mouseWheel: true"), "native LW wheel zoom must be enabled");
        assertTrue(js.contains("function currentBucketUnix"), "MSK bucket clock missing");
        assertTrue(js.contains("function barTimeUnix"), "ISO desk bars must convert to unix for live prints");
        assertTrue(js.contains("Closed last bar: open a forming candle"),
                "applyTradeToCandle must roll the current bucket, not skip live prints");
        assertTrue(js.contains("0.006"), "printFitsLast must be TV-tight, not 2.5% / barRange*12 wicks");
        assertFalse(js.contains("barRange * 12"), "spiked bar range must not widen the next tick cap");
        assertTrue(js.contains("do NOT preventDefault"), "plain wheel must reach native LW scale");
        assertTrue(js.contains("const TARGET = 18"), "cluster auto-zoom must nudge gently, not blow to 42");
        assertTrue(js.contains("const CAP = 120"), "cluster auto-zoom must allow deep mouse zoom");
        Process p = new ProcessBuilder("node", "--check",
                Files.isRegularFile(Path.of("src/main/resources/static/js/trinity-chart-kit.js"))
                        ? "src/main/resources/static/js/trinity-chart-kit.js"
                        : "trinity-app/src/main/resources/static/js/trinity-chart-kit.js")
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor() == 0, "trinity-chart-kit.js must parse: " + out);
        assertTrue(css.contains(".charts-flow-profile"), "session VAP must have a positioned overlay");
        assertTrue(css.contains(".charts-cluster-cell"), "cluster cells need contrast styles");
        int vapAt = css.indexOf(".signal-profile-overlay,\n.charts-flow-profile {");
        assertTrue(vapAt >= 0, "session profile overlay must be CSS-positioned");
        String vapBlock = css.substring(vapAt, Math.min(css.length(), vapAt + 220));
        assertTrue(vapBlock.contains("left: 0"), "horizontal volumes grow from the left, not the price scale");
        assertFalse(vapBlock.contains("right: 56px"), "session VAP must not sit on the right plaques");
    }

    @Test
    void calendarFlyUsesThreeLegs() throws Exception {
        String js = read("src/main/resources/static/js/calendar-arb-desk.js");
        assertTrue(js.contains("lastLegs.wing"), "fly wing not subscribed");
        assertTrue(js.contains("lastPx.near - 2 * lastPx.mid + lastPx.wing"), "fly formula missing");
    }

    @Test
    void pairsPollIssLastForEquity() throws Exception {
        String js = read("src/main/resources/static/js/pairs-charts.js");
        assertTrue(js.contains("/api/marketdata/iss-last"), "ISS last poll missing");
    }
}
