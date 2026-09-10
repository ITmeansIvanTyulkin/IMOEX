package com.moex.cointegration.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Permanent fast-load contract must stay wired into every strategy desk. */
class TrinityFastBootContractTest {

    @Test
    void fastBootModuleDefinesTimeoutsAndAbort() throws Exception {
        Path js = Path.of("src/main/resources/static/js/trinity-fast-boot.js");
        if (!Files.isRegularFile(js)) {
            js = Path.of("trinity-app/src/main/resources/static/js/trinity-fast-boot.js");
        }
        String src = Files.readString(js, StandardCharsets.UTF_8);
        assertTrue(src.contains("DESK_MS"), "DESK_MS missing");
        assertTrue(src.contains("BOOK_MS"), "BOOK_MS missing");
        assertTrue(src.contains("AbortController"), "AbortController missing");
        assertTrue(src.contains("fetchJson"), "fetchJson missing");
        assertTrue(src.contains("do not regress") || src.contains("all strategy desks"),
                "contract comment missing");
    }

    @Test
    void desksWireFastBootScript() throws Exception {
        String[] files = {
                "src/main/resources/trend-signal-desk.html",
                "src/main/resources/calendar-arb-desk.html",
                "src/main/resources/trend-charts-terminal.html"
        };
        for (String rel : files) {
            Path p = Path.of(rel);
            if (!Files.isRegularFile(p)) {
                p = Path.of("trinity-app/" + rel);
            }
            assertTrue(Files.isRegularFile(p), "missing " + rel);
            String html = Files.readString(p, StandardCharsets.UTF_8);
            assertTrue(html.contains("trinity-fast-boot.js"), rel + " must load TrinityFastBoot");
        }
    }

    @Test
    void pairsDeskWiresFastBootViaRenderer() throws Exception {
        Path java = Path.of("src/main/java/com/moex/cointegration/web/AnalysisHtmlRenderer.java");
        if (!Files.isRegularFile(java)) {
            java = Path.of("trinity-app/src/main/java/com/moex/cointegration/web/AnalysisHtmlRenderer.java");
        }
        String src = Files.readString(java, StandardCharsets.UTF_8);
        assertTrue(src.contains("trinity-fast-boot.js"), "pairs desk must load TrinityFastBoot");
        assertTrue(src.contains("pairs-final-desk.js"), "pairs desk script missing");
    }
}
