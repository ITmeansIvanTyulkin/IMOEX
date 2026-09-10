package com.moex.cointegration.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PairsDeskGuideHtmlTest {

    private static String read(String rel) throws Exception {
        Path p = Path.of("src/main/resources/" + rel);
        if (!Files.isRegularFile(p)) {
            p = Path.of("trinity-app/src/main/resources/" + rel);
        }
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    void guideIsRussianWithoutKitchenJargon() throws Exception {
        String html = read("pairs-desk-guide.html");
        assertTrue(html.contains("Как торгует робот"), html);
        assertTrue(html.contains("id=\"pairs-guide-idea\""), "idea section missing");
        assertTrue(html.toLowerCase().contains("парн"), html);
        assertTrue(html.contains("фаворит отрасли") || html.contains("Фаворит отрасли"), html);
        assertTrue(html.contains("закрытия реестра"), "dividend window missing from pairs guide");
        assertFalse(html.contains("mean-reversion"), html);
        assertFalse(html.toLowerCase().contains("z-score"), html);
        assertFalse(html.contains("Kalman") || html.contains("kalman"), html);
        assertFalse(html.contains("CUSUM"), html);
        assertFalse(html.contains("sit-out"), html);
        assertFalse(html.contains("§"), "no checklist paragraph marks in pairs guide");
    }

    @Test
    void deskWiresGuideButtonAndScript() throws Exception {
        Path p = Path.of("src/main/java/com/moex/cointegration/web/AnalysisHtmlRenderer.java");
        if (!Files.isRegularFile(p)) {
            p = Path.of("trinity-app/src/main/java/com/moex/cointegration/web/AnalysisHtmlRenderer.java");
        }
        String src = Files.readString(p, StandardCharsets.UTF_8);
        assertTrue(src.contains("id=\"pairs-guide-open\""), "guide button missing on pairs desk");
        assertTrue(src.contains("pairs-desk-guide.html"), "guide resource not loaded");
        assertTrue(src.contains("pairs-final-desk.js?v=20260902-guide2"), src);
        String js = read("static/js/pairs-final-desk.js");
        assertTrue(js.contains("function bindPairsGuide"), "guide binder missing");
    }
}
