package com.moex.cointegration.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpreadDeskHtmlTest {

    private static String read(String rel) throws Exception {
        Path p = Path.of("src/main/resources/" + rel);
        if (!Files.isRegularFile(p)) {
            p = Path.of("trinity-app/src/main/resources/" + rel);
        }
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    void deskHasGuideAndObservationSwitch() throws Exception {
        String html = read("spread-desk.html");
        assertTrue(html.contains("Как торгует робот"), html);
        assertTrue(html.contains("id=\"spread-guide-open\""), "guide button missing");
        assertTrue(html.contains("id=\"desk-spread-auto-execution\""), "desk auto switch missing");
        assertTrue(html.contains("Наблюдение"), html);
        assertFalse(html.contains("ordinary/pref"), html);
        String js = read("static/js/spread-desk.js");
        assertTrue(js.contains("/api/dual-class/desk"), "desk API missing");
        assertTrue(js.contains("trinity-fast-boot") || js.contains("TrinityFastBoot"), js);
        assertTrue(js.contains("classList.add(\"is-open\")"), "guide must open with is-open");
        assertTrue(js.contains("function openGuide"), "guide opener missing");
        assertTrue(js.contains("rolling-60d") || js.contains("entryZ"), "must fade z vs 60d median");
        assertTrue(html.contains("Пол от центра"), html);
        assertTrue(html.contains("id=\"spread-book\""), "capital book chip");
    }
}
