package com.moex.cointegration.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Terminal dock: TF / alerts / templates / tape must stay wired. */
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
        String html = read("src/main/resources/trend-charts-terminal.html");
        assertTrue(html.contains("id=\"charts-tf\""), "TF strip missing");
        assertTrue(html.contains("id=\"charts-alert-add\""), "alert add missing");
        assertTrue(html.contains("id=\"charts-tpl-save\""), "template save missing");
        assertTrue(html.contains("id=\"charts-tpl-apply\""), "template apply missing");
        assertTrue(html.contains("id=\"charts-watchlist\""), "watchlist missing");
        assertTrue(html.contains("id=\"charts-dom\""), "DOM missing");
        assertTrue(html.contains("id=\"charts-tape\""), "tape missing");
    }

    @Test
    void terminalJsWiresDockAndPersistsTemplates() throws Exception {
        String js = read("src/main/resources/static/js/trend-charts-terminal.js");
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
        assertTrue(js.contains("study=1"), "terminal must request study bars, not Exclusive oil lock");
        assertTrue(js.contains("positional-volume-h1"), "non-oil panes must use positional desk until study=1 is live");
        assertTrue(js.contains("function deskBarsMatchPane"), "foreign-family bars must not paint");
        assertTrue(js.contains("quotesMatchInstrument"), "tape must not apply BR 103 onto Ri/Si");
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
        assertTrue(js.contains("do NOT preventDefault"), "plain wheel must reach native LW scale");
        assertTrue(js.contains("const TARGET = 18"), "cluster auto-zoom must nudge gently, not blow to 42");
        assertTrue(js.contains("const CAP = 120"), "cluster auto-zoom must allow deep mouse zoom");
        // Syntax: miss-banner edit previously dropped a closing brace and broke all desks.
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
