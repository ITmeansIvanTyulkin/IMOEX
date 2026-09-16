package com.moex.cointegration.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
