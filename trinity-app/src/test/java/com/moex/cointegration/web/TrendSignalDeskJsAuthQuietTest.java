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
        assertTrue(src.contains("desk-positional-auto-execution"), "positional desk switcher missing");
        assertTrue(src.contains("/api/trend/settings/positional-auto-execution"), "positional auto API missing");
        assertTrue(src.contains("Перед входом · фундамент и охота"), "hunt brief heading missing");
        assertTrue(src.contains("function wantedDeskInstrument"), "desk must request pinned instrument");
        assertTrue(src.contains("invalidateDeskFetch"), "stale oil payload must be dropped on instrument change");
        assertTrue(src.contains("deskReloadQueued"), "instrument change must queue desk reload");
        assertTrue(src.contains("deskScope() === \"positional\" ? \"H1\" : \"M5\""),
                "positional boot must paint local H1 archive, not skip cache");
        assertTrue(src.contains("function isRangeDesk"), "range desk helper missing");
        assertTrue(src.contains("INST_STORE + \".range\""),
                "range must not share positional instrument pin");
        assertTrue(src.contains("isRangeDesk() && want && !isOilInstrument(want)"),
                "range wanted instrument must ignore Si/Ri pin");
        assertTrue(src.contains("function formatSecidWithMonth"), "chart must show FORTS month next to SECID");
        assertTrue(src.contains("FORTS_MONTH_RU"), "FORTS month map missing");
        assertTrue(src.contains("октябрь"), "October month label missing");
        assertTrue(src.contains("DESK_FETCH_MS"), "desk fetch timeout missing");
        assertTrue(src.contains("AbortController"), "desk/book must abort hung fetches");
    }
}
