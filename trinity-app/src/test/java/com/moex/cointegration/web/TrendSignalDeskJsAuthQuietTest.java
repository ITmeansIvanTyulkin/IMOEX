package com.moex.cointegration.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cold load with ?playbook= must not alert on missing auth (quiet URL apply).
 */
class TrendSignalDeskJsAuthQuietTest {

    @Test
    void urlPlaybookApplyIsQuietAndGuardsWriteAuth() throws Exception {
        Path js = Path.of("src/main/resources/static/js/trend-signal-desk.js");
        if (!Files.isRegularFile(js)) {
            js = Path.of("trinity-app/src/main/resources/static/js/trend-signal-desk.js");
        }
        String src = Files.readString(js, StandardCharsets.UTF_8);
        assertTrue(src.contains("function hasDeskWriteAuth"), "hasDeskWriteAuth helper missing");
        assertTrue(src.contains("quiet: true"), "URL playbook apply must pass quiet:true");
        assertTrue(src.contains("async function applyUrlPlaybookOnce"), "applyUrlPlaybookOnce missing");
        int applyAt = src.indexOf("async function applyUrlPlaybookOnce");
        int nextFn = src.indexOf("\n  ", applyAt + 10);
        // Look at applyUrlPlaybookOnce body for quiet saveDeskSelection
        String applyBody = src.substring(applyAt, Math.min(src.length(), applyAt + 900));
        assertTrue(applyBody.contains("saveDeskSelection({ playbookId: pb }, { quiet: true })")
                        || applyBody.contains("quiet: true"),
                "applyUrlPlaybookOnce must call saveDeskSelection quietly");
        assertTrue(src.contains("saveDeskSelection skipped") || src.contains("hasDeskWriteAuth()"),
                "saveDeskSelection should skip/guard when no session");
        assertTrue(src.contains("function deskScope"), "deskScope missing");
        assertTrue(src.contains("view/trend-positional"), "positional URL redirect missing");
        assertTrue(src.contains("function buildPositionalBrief"), "positional brief missing");
        assertTrue(src.contains("function familyRu"), "positional family labels missing");
        assertTrue(src.contains("После 16:00 новый вход не ставим"), "late-arm copy must be Russian");
        assertTrue(src.contains("deskReloadQueued"), "instrument change must queue desk reload");
    }
}
