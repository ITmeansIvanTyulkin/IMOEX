package com.moex.cointegration.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cold start must be the login gate, not the last desk URL. */
class DeskEntranceContractTest {

    private static String read(String... rels) throws Exception {
        for (String rel : rels) {
            Path p = Path.of(rel);
            if (Files.isRegularFile(p)) {
                return Files.readString(p, StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("missing " + String.join(" | ", rels));
    }

    @Test
    void htmlBouncesDeepLinksUntilDeskEntered() throws Exception {
        String src = read(
                "src/main/java/com/moex/cointegration/web/AnalysisHtmlRenderer.java",
                "trinity-app/src/main/java/com/moex/cointegration/web/AnalysisHtmlRenderer.java");
        assertTrue(src.contains("trinity.desk.entered"), src);
        assertTrue(src.contains("trinity.desk.enteredUntil"), src);
        assertTrue(src.contains("trinity.desk.boot"), src);
        assertTrue(src.contains("location.replace(\"/view\")"), src);
        assertTrue(src.contains("{{BOOT_ID}}"), src);
        assertTrue(src.contains("trinity-need-gate"), src);
        assertTrue(src.contains("operator.js?v=20260921-sess8"), src);
    }

    @Test
    void operatorDoesNotSkipGateOnLeftoverJwt() throws Exception {
        String src = read(
                "src/main/resources/static/js/operator.js",
                "trinity-app/src/main/resources/static/js/operator.js");
        assertTrue(src.contains("function deskSessionReady"), src);
        assertTrue(src.contains("function markDeskEntered"), src);
        assertTrue(src.contains("function revokeDeskSession"), src);
        assertTrue(src.contains("injectAuthHeaders"), src);
        assertTrue(src.contains("headers.forEach"), src);
        assertTrue(src.contains("jwtExpired"), src);
        assertTrue(src.contains("DESK_TTL_MS"), src);
        assertTrue(src.contains("location.replace(\"/view\")"), src);
        int gateAt = src.indexOf("function maybeShowAuthGate");
        assertTrue(gateAt > 0, "maybeShowAuthGate missing");
        String body = src.substring(gateAt, Math.min(src.length(), gateAt + 1200));
        assertFalse(body.contains("if (localStorage.getItem(SB_TOKEN_KEY)) {\n      closeAuthGateHard()"),
                "leftover JWT must not skip the start screen");
        assertTrue(body.contains("deskSessionReady()"), body);
    }
}
