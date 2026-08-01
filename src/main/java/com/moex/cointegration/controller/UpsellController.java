package com.moex.cointegration.controller;

import com.moex.cointegration.upsell.UpsellDismissRequest;
import com.moex.cointegration.upsell.UpsellEvent;
import com.moex.cointegration.upsell.UpsellEventRequest;
import com.moex.cointegration.upsell.UpsellPrompt;
import com.moex.cointegration.upsell.UpsellService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Soft commercial upsell API (events + optional prompt). No billing.
 */
@RestController
@RequestMapping("/api/upsell")
public class UpsellController {

    private final UpsellService upsellService;

    public UpsellController(UpsellService upsellService) {
        this.upsellService = upsellService;
    }

    /**
     * POST /api/upsell/events — beacon operator action (auth when enabled).
     */
    @PostMapping("/events")
    public ResponseEntity<?> recordEvent(@RequestBody(required = false) UpsellEventRequest request) {
        UpsellEvent event = upsellService.recordEvent(
                request != null ? request : new UpsellEventRequest("unknown", "", null)
        );
        if (event == null) {
            return ResponseEntity.ok(Map.of("recorded", false, "enabled", false));
        }
        return ResponseEntity.ok(Map.of("recorded", true, "action", event.action()));
    }

    /**
     * GET /api/upsell/prompt — optional soft nudge (empty → 204).
     */
    @GetMapping("/prompt")
    public ResponseEntity<UpsellPrompt> prompt() {
        return upsellService.suggestPrompt()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * POST /api/upsell/dismiss — start cooldown for a feature prompt.
     */
    @PostMapping("/dismiss")
    public Map<String, Object> dismiss(@RequestBody(required = false) UpsellDismissRequest request) {
        upsellService.dismiss(
                request != null ? request : new UpsellDismissRequest(null, UpsellService.FEATURE_CALENDAR_ARB)
        );
        return Map.of("dismissed", true);
    }
}
