package com.moex.cointegration.upsell;

import com.moex.cointegration.config.UpsellProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpsellServiceTest {

    @Test
    void suggestsCalendarArbAfterHeuristicAndMinEvents() {
        UpsellProperties props = new UpsellProperties(true, 15_000, 168, 3, 2);
        UpsellService service = new UpsellService(props);

        Instant t0 = Instant.parse("2026-08-01T10:00:00Z");
        service.seedEvent(new UpsellEvent("page_view", "/view", t0, null));
        service.seedEvent(new UpsellEvent("page_view", "/view", t0.plusSeconds(60), null));
        service.seedEvent(new UpsellEvent("page_view", "/view/signals", t0.plusSeconds(120), null));

        Optional<UpsellPrompt> prompt = service.suggestPrompt(t0.plusSeconds(200));
        assertTrue(prompt.isPresent());
        assertEquals(UpsellService.FEATURE_CALENDAR_ARB, prompt.get().featureKey());
        assertEquals(UpsellService.TARGET_TIER_FULL_CORE, prompt.get().targetTier());
        assertTrue(prompt.get().body().contains("15"));
        assertTrue(prompt.get().ctaHref().contains("/view/strategy"));
    }

    @Test
    void respectsDismissCooldown() {
        UpsellProperties props = new UpsellProperties(true, 15_000, 24, 1, 1);
        UpsellService service = new UpsellService(props);

        Instant now = Instant.parse("2026-08-01T12:00:00Z");
        service.seedEvent(new UpsellEvent("run-fast", "/view", now.minusSeconds(10), null));
        service.seedDismissal(UpsellService.FEATURE_CALENDAR_ARB, now.minus(2, ChronoUnit.HOURS));

        assertTrue(service.isOnCooldown(UpsellService.FEATURE_CALENDAR_ARB, now));
        assertFalse(service.suggestPrompt(now).isPresent());

        Instant afterCool = now.plus(23, ChronoUnit.HOURS);
        assertFalse(service.isOnCooldown(UpsellService.FEATURE_CALENDAR_ARB, afterCool));
        assertTrue(service.suggestPrompt(afterCool).isPresent());
    }

    @Test
    void disabledReturnsNoPrompt() {
        UpsellProperties props = new UpsellProperties(false, 15_000, 168, 1, 1);
        UpsellService service = new UpsellService(props);
        service.seedEvent(new UpsellEvent("run-fast", "/view", Instant.now(), null));
        assertFalse(service.suggestPrompt().isPresent());
    }

    @Test
    void analysisRunAloneTriggersAfterMinEvents() {
        UpsellProperties props = new UpsellProperties(true, 15_000, 168, 2, 99);
        UpsellService service = new UpsellService(props);
        Instant t = Instant.parse("2026-08-01T09:00:00Z");
        service.seedEvent(new UpsellEvent("page_view", "/view/guide", t, null));
        service.seedEvent(new UpsellEvent("run-full", "/view/settings", t.plusSeconds(5), null));
        assertTrue(service.heuristicTriggered());
        assertTrue(service.suggestPrompt(t.plusSeconds(10)).isPresent());
    }
}
