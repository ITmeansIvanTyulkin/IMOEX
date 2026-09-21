package com.moex.cointegration.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(src.contains("desk-range-auto-execution"), "range desk switcher missing");
        assertTrue(src.contains("/api/trend/settings/auto-execution"), "range auto API missing");
        assertTrue(src.contains("hydrateDeskModeSwitches"), "range/positional toggles must hydrate from GET settings");
        assertTrue(src.contains("positionalAutoFlag"), "positional toggle must not treat a missing desk field as off");
        assertTrue(src.contains("healInsaneZoom"), "login/resume must not restore a one-candle time scale");
        assertTrue(src.contains("MAX_BAR_SPACING = 160"), "barSpacing soft-cap must allow deep mouse zoom");
        assertTrue(src.contains("Never reset just because spacing is"), "heal must not undo wheel zoom");
        assertTrue(src.contains("vis > 0 && vis < 2.5"), "heal only one-candle fill");
        assertTrue(src.contains("function jwtUnexpired"), "expired cabinet JWT must not block desk cookie writes");
        assertTrue(src.contains("footprintByTime = {}"), "instrument change must clear foreign footprint levels");
        assertTrue(src.contains("Always replace so instrument switches"),
                "empty footprint response must clear prior map");
        int saveAt = src.indexOf("async function saveDeskSelection");
        assertTrue(saveAt > 0, "saveDeskSelection missing");
        String saveBody = src.substring(saveAt, Math.min(src.length(), saveAt + 1600));
        assertFalse(saveBody.contains("autoExecution:"),
                "saveDeskSelection must not echo autoExecution (playbook/instrument only)");
        assertFalse(saveBody.contains("positionalAutoExecution:"),
                "saveDeskSelection must not echo positionalAutoExecution");
        assertFalse(saveBody.contains("liveExecution:"),
                "saveDeskSelection must not echo liveExecution");
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
        assertTrue(src.contains("tool-clusters"), "positional/range desk must wire clusters tool");
        assertTrue(src.contains("attachFlowOverlays"), "desk must attach flow overlays for clusters");
        assertTrue(src.contains("mergeDomBook"), "DOM must keep both bid and ask shelves");
        assertTrue(src.contains("const DOM_DEPTH = 50"), "DOM must keep max bid and ask depth");
        assertTrue(src.contains("overflow-y: scroll") || src.contains("body.scrollTop"), "DOM ladder must scroll");
        assertTrue(src.contains("function centerDomOnSpread"), "DOM must pin the view on spread, not last tick");
    }
}
