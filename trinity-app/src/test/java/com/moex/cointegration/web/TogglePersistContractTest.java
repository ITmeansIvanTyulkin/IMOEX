package com.moex.cointegration.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Paper auto toggles must survive a new session; smoke may only persist liveExecution=false. */
class TogglePersistContractTest {

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
    void smokeSoftBlockDoesNotWipePaperAutos() throws Exception {
        String src = read(
                "src/main/java/com/moex/cointegration/smoke/StartupSmokeRunner.java",
                "trinity-app/src/main/java/com/moex/cointegration/smoke/StartupSmokeRunner.java");
        assertTrue(src.contains("softBlockLiveExecution"), src);
        assertFalse(src.contains("softBlockExecution("), src);
        assertTrue(src.contains("new TrendSettingsService.UpdateRequest(null, false, null, null, null)"), src);
        assertFalse(src.contains("new TrendSettingsService.UpdateRequest(false, false"), src);
    }

    @Test
    void deskScriptsHydrateBeforeWrite() throws Exception {
        String trend = read(
                "src/main/resources/static/js/trend-signal-desk.js",
                "trinity-app/src/main/resources/static/js/trend-signal-desk.js");
        assertTrue(trend.contains("hydrateDeskModeSwitches"));
        assertTrue(trend.contains("positionalAutoFlag"));
        assertTrue(trend.contains("dataset.hydrated"));
        int saveAt = trend.indexOf("async function saveDeskSelection");
        String saveBody = trend.substring(saveAt, Math.min(trend.length(), saveAt + 1600));
        assertFalse(saveBody.contains("autoExecution:"));
        assertFalse(saveBody.contains("liveExecution:"));
        assertFalse(saveBody.contains("positionalAutoExecution:"));

        String arb = read(
                "src/main/resources/static/js/calendar-arb-desk.js",
                "trinity-app/src/main/resources/static/js/calendar-arb-desk.js");
        assertTrue(arb.contains("dataset.hydrated"));
        assertTrue(arb.contains("return null;"));

        String pairs = read(
                "src/main/resources/static/js/pairs-final-desk.js",
                "trinity-app/src/main/resources/static/js/pairs-final-desk.js");
        assertTrue(pairs.contains("dataset.hydrated"));

        String ops = read(
                "src/main/resources/static/js/operator.js",
                "trinity-app/src/main/resources/static/js/operator.js");
        assertTrue(ops.contains("dataset.hydrated === \"1\""));
        assertTrue(ops.contains("payload.autoExecuteAfterAnalysis"));
    }

    @Test
    void observeResumeMustKeepPositionalAuto() throws Exception {
        String sh = read(
                "scripts/trend_observe_resume.sh",
                "../scripts/trend_observe_resume.sh",
                "IMOEX/scripts/trend_observe_resume.sh");
        assertTrue(sh.contains("positionalAutoExecution"),
                "observe resume must not drop the positional paper switch");
        assertTrue(sh.contains("{**cur, **want}") || sh.contains("want = {**cur"),
                "observe resume must merge into existing settings, not replace with a short dict");
    }
}
