package com.moex.cointegration.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalendarArbDeskHtmlTest {

    private static String read(String rel) throws Exception {
        Path p = Path.of("src/main/resources/" + rel);
        if (!Files.isRegularFile(p)) {
            p = Path.of("trinity-app/src/main/resources/" + rel);
        }
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    void deskHasGuideAndObservationSwitch() throws Exception {
        String html = read("calendar-arb-desk.html");
        assertTrue(html.contains("Как торгует робот"), html);
        assertTrue(html.contains("id=\"arb-guide-open\""), "guide button missing");
        assertTrue(html.contains("id=\"desk-arb-auto-execution\""), "desk auto switch missing");
        assertTrue(html.contains("Наблюдение"), html);
        assertTrue(html.contains("id=\"arb-guide-idea\""), "guide idea section missing");
        assertTrue(html.contains("календарный спред"), html.toLowerCase());
        assertFalse(html.contains("mean-reversion"), html);
        assertFalse(html.contains("z-score"), html.toLowerCase());
        assertFalse(html.contains("§"), "no checklist paragraph marks in arb desk copy");
        String js = read("static/js/calendar-arb-desk.js");
        assertTrue(js.contains("/api/calendar-arb/settings/auto-execution"), "desk auto API missing");
        assertTrue(js.contains("function bindArbGuide"), "guide binder missing");
        assertTrue(js.contains("Наблюдение"), js);
    }

    @Test
    void settingsSwitchIsObservationNotSignalOnly() throws Exception {
        Path p = Path.of("src/main/java/com/moex/cointegration/web/AnalysisHtmlRenderer.java");
        if (!Files.isRegularFile(p)) {
            p = Path.of("trinity-app/src/main/java/com/moex/cointegration/web/AnalysisHtmlRenderer.java");
        }
        String src = Files.readString(p, StandardCharsets.UTF_8);
        assertTrue(src.contains("calendar-arb-settings"), "calendar arb settings id missing");
        assertTrue(src.contains("\"arb-auto-execution\""), src);
        assertTrue(src.contains("robotsDeliveryStrip"), "robots delivery strip missing");
        assertTrue(src.contains("\"Арбитраж\""), src);
        assertTrue(src.contains("\"Коинтеграция\""), src);
        assertTrue(src.contains("Наблюдение"), src);
        assertFalse(src.contains("Только сигнал"), src);
        assertFalse(src.contains("Z-спред"), src);
    }
}
