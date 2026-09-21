package com.moex.cointegration.config;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory desk session for HTML {@code /view/**}. Wiped on JVM restart so leftover
 * cabinet JWT in localStorage cannot open strategy pages without logging in again.
 */
@Component
public class DeskSessionStore {

    public static final String COOKIE_NAME = "trinity.desk";

    private final ConcurrentHashMap<String, Held> sessions = new ConcurrentHashMap<>();

    public ResponseCookie issueCookie(String email, Duration ttl) {
        Duration life = ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofHours(1) : ttl;
        String id = UUID.randomUUID().toString();
        sessions.put(id, new Held(email == null ? "" : email, Instant.now().plus(life)));
        prune();
        return cookie(id, life);
    }

    public ResponseCookie expireCookie() {
        return cookie("", Duration.ZERO);
    }

    public void revoke(String id) {
        if (id != null && !id.isBlank()) {
            sessions.remove(id);
        }
    }

    public String idOf(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (COOKIE_NAME.equals(c.getName())) {
                String v = c.getValue();
                return v == null || v.isBlank() ? null : v;
            }
        }
        return null;
    }

    public boolean valid(String id) {
        Held held = lookup(id);
        return held != null;
    }

    public String email(String id) {
        Held held = lookup(id);
        return held == null ? "" : held.email();
    }

    private Held lookup(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        Held held = sessions.get(id);
        if (held == null) {
            return null;
        }
        if (Instant.now().isAfter(held.expiresAt())) {
            sessions.remove(id);
            return null;
        }
        return held;
    }

    private void prune() {
        if (sessions.size() < 256) {
            return;
        }
        Instant now = Instant.now();
        sessions.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt()));
    }

    static ResponseCookie cookie(String value, Duration ttl) {
        return ResponseCookie.from(COOKIE_NAME, value == null ? "" : value)
                .httpOnly(true)
                .path("/")
                .maxAge(ttl)
                .sameSite("Lax")
                .build();
    }

    private record Held(String email, Instant expiresAt) {
    }
}
