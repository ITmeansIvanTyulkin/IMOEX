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

    private static UpsellProperties props(
            boolean enabled,
            int cooldownHours,
            int minEvents,
            int minDash,
            int trialDays,
            boolean autoStart,
            boolean simActive,
            boolean simExpired
    ) {
        return new UpsellProperties(
                enabled, 5_000, 7_500, 15_000, cooldownHours, minEvents, minDash,
                trialDays, autoStart, simActive, simExpired
        );
    }

    @Test
    void suggestsCalendarArbAfterHeuristicAndMinEvents() {
        UpsellProperties p = props(true, 168, 3, 2, 14, false, false, true);
        UpsellService service = new UpsellService(p);

        Instant t0 = Instant.parse("2026-08-01T10:00:00Z");
        service.seedEvent(new UpsellEvent("page_view", "/view", t0, null));
        service.seedEvent(new UpsellEvent("page_view", "/view", t0.plusSeconds(60), null));
        service.seedEvent(new UpsellEvent("page_view", "/view/signals", t0.plusSeconds(120), null));

        Optional<UpsellPrompt> prompt = service.suggestPrompt(t0.plusSeconds(200));
        assertTrue(prompt.isPresent());
        assertEquals(UpsellService.FEATURE_CALENDAR_ARB, prompt.get().featureKey());
        assertEquals(UpsellService.TARGET_TIER_FULL_CORE, prompt.get().targetTier());
        assertTrue(prompt.get().body().contains("15"));
        assertTrue(prompt.get().title().contains("полном Core"));
        assertTrue(prompt.get().ctaHref().contains("/view/full-core"));
    }

    @Test
    void respectsDismissCooldown() {
        UpsellProperties p = props(true, 24, 1, 1, 14, false, false, true);
        UpsellService service = new UpsellService(p);

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
        UpsellProperties p = props(false, 168, 1, 1, 14, true, false, false);
        UpsellService service = new UpsellService(p);
        service.seedEvent(new UpsellEvent("run-fast", "/view", Instant.now(), null));
        assertFalse(service.suggestPrompt().isPresent());
        assertEquals(UpsellService.PHASE_OFF, service.access().phase());
    }

    @Test
    void analysisRunAloneTriggersAfterMinEvents() {
        UpsellProperties p = props(true, 168, 2, 99, 14, false, false, true);
        UpsellService service = new UpsellService(p);
        Instant t = Instant.parse("2026-08-01T09:00:00Z");
        service.seedEvent(new UpsellEvent("page_view", "/view/guide", t, null));
        service.seedEvent(new UpsellEvent("run-full", "/view/settings", t.plusSeconds(5), null));
        assertTrue(service.heuristicTriggered());
        assertTrue(service.suggestPrompt(t.plusSeconds(10)).isPresent());
    }

    @Test
    void reverseTrialGrantsAccessThenExpires() {
        UpsellProperties p = props(true, 168, 1, 1, 14, false, false, false);
        UpsellService service = new UpsellService(p);
        Instant start = Instant.parse("2026-08-01T10:00:00Z");
        service.seedTrial(start, start.plus(14, ChronoUnit.DAYS));

        UpsellAccess mid = service.accessAt(start.plus(3, ChronoUnit.DAYS));
        assertTrue(mid.hasFullCoreAccess());
        assertEquals(UpsellService.PHASE_TRIAL, mid.phase());
        assertFalse(mid.locksVisible());
        assertFalse(service.suggestPrompt(start.plus(3, ChronoUnit.DAYS)).isPresent());

        UpsellAccess after = service.accessAt(start.plus(15, ChronoUnit.DAYS));
        assertFalse(after.hasFullCoreAccess());
        assertEquals(UpsellService.PHASE_EXPIRED, after.phase());
        assertTrue(after.locksVisible());
    }

    @Test
    void simulateExpiredForcesLocks() {
        UpsellProperties p = props(true, 168, 1, 1, 14, true, false, true);
        UpsellService service = new UpsellService(p);
        UpsellAccess access = service.access();
        assertEquals(UpsellService.PHASE_EXPIRED, access.phase());
        assertTrue(access.locksVisible());
    }

    @Test
    void tipIncludesTierWhenEnabled() {
        UpsellProperties p = props(true, 168, 1, 1, 14, false, false, false);
        UpsellService service = new UpsellService(p);
        Optional<UpsellPrompt> tip = service.infoTip();
        assertTrue(tip.isPresent());
        assertTrue(tip.get().body().contains("7"));
        assertTrue(tip.get().body().contains("15"));
    }
}
