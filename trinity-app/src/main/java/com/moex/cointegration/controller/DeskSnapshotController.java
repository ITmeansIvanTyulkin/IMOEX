package com.moex.cointegration.controller;

import com.moex.cointegration.config.DeskCloudSessionStore;
import com.moex.cointegration.service.DeskSnapshotPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cloud desk snapshot: register cabinet JWT on the desk and push paper/regime to Supabase
 * so trinity.trading cabinet can read live numbers.
 */
@RestController
@RequestMapping("/api/desk")
public class DeskSnapshotController {

    private final DeskCloudSessionStore cloudSessions;
    private final ObjectProvider<DeskSnapshotPublisher> publisher;

    public DeskSnapshotController(
            DeskCloudSessionStore cloudSessions,
            ObjectProvider<DeskSnapshotPublisher> publisher
    ) {
        this.cloudSessions = cloudSessions;
        this.publisher = publisher;
    }

    public record CloudSessionRequest(
            String email,
            String accessToken,
            String refreshToken,
            Long expiresIn
    ) {}

    @PostMapping("/cloud-session")
    public ResponseEntity<Map<String, Object>> registerCloudSession(
            @RequestBody CloudSessionRequest body
    ) {
        String access = body == null || body.accessToken() == null ? "" : body.accessToken().trim();
        if (access.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "ok", false,
                    "error", "missing_token",
                    "message", "accessToken обязателен"
            ));
        }
        long exp = body.expiresIn() == null || body.expiresIn() <= 0 ? 3600L : body.expiresIn();
        cloudSessions.remember(
                body.email(),
                access,
                body.refreshToken(),
                exp
        );
        DeskSnapshotPublisher pub = publisher.getIfAvailable();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("hasSession", cloudSessions.hasSession());
        if (pub != null) {
            out.putAll(pub.status());
            try {
                out.putAll(pub.publishNow());
            } catch (Exception e) {
                out.put("publishError", e.getMessage() == null ? e.toString() : e.getMessage());
            }
        } else {
            out.put("enabled", false);
            out.put("message", "Supabase snapshot publisher выключен (imoex.auth.supabase.enabled)");
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/snapshot")
    public Map<String, Object> status() {
        DeskSnapshotPublisher pub = publisher.getIfAvailable();
        if (pub == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("enabled", false);
            out.put("hasSession", cloudSessions.hasSession());
            return out;
        }
        return pub.status();
    }

    @PostMapping("/snapshot/publish")
    public ResponseEntity<Map<String, Object>> publishNow() {
        DeskSnapshotPublisher pub = publisher.getIfAvailable();
        if (pub == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "ok", false,
                    "error", "publisher_disabled",
                    "message", "Включите imoex.auth.supabase.enabled"
            ));
        }
        try {
            return ResponseEntity.ok(pub.publishNow());
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "ok", false,
                    "error", "publish_failed",
                    "message", e.getMessage() == null ? e.toString() : e.getMessage()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "ok", false,
                    "error", "publish_upstream",
                    "message", e.getMessage() == null ? e.toString() : e.getMessage()
            ));
        }
    }
}
