package com.moex.cointegration.controller;

import com.moex.cointegration.upsell.UpsellAccess;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Soft commercial upsell API (events + optional prompt + reverse trial). No billing.
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
     * GET /api/upsell/tip — static Full Core tip for teaser clicks (no cooldown).
     */
    @GetMapping("/tip")
    public ResponseEntity<UpsellPrompt> tip() {
        return upsellService.infoTip()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * GET /api/upsell/access — reverse-trial / lock status for UI.
     */
    @GetMapping("/access")
    public UpsellAccess access() {
        return upsellService.access();
    }

    /**
     * POST /api/upsell/trial — start / expire / reset reverse trial (local scaffolding).
     * action=start|expire|reset
     */
    @PostMapping("/trial")
    public Map<String, Object> trial(@RequestParam(defaultValue = "start") String action) {
        UpsellAccess access = switch (action == null ? "start" : action.trim().toLowerCase()) {
            case "expire" -> upsellService.expireTrial();
            case "reset" -> upsellService.resetTrial();
            default -> upsellService.startTrial();
        };
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", action);
        out.put("access", access);
        return out;
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
