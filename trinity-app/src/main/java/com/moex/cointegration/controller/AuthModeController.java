package com.moex.cointegration.controller;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moex.cointegration.config.ImoexProperties;

/**
 * Public auth mode + same-origin login proxy (browser → app → Supabase).
 * Direct browser→Supabase often fails as Safari/WebKit «Load failed» (extensions / privacy).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthModeController {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ImoexProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public AuthModeController(ImoexProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/mode")
    public Map<String, Object> mode() {
        var auth = properties.auth();
        var sb = auth.supabase();
        Map<String, Object> supabase = new LinkedHashMap<>();
        supabase.put("enabled", sb.enabled());
        supabase.put("configured", sb.jwtConfigured());
        supabase.put("url", sb.url() == null ? "" : sb.url());
        /* anon key is public by design (RLS); jwt-secret never exposed.
         * Login goes through POST /api/auth/login — anonKey kept for diagnostics only. */
        supabase.put("anonKey", sb.anonKey() == null ? "" : sb.anonKey());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("basicEnabled", auth.enabled());
        out.put("supabase", supabase);
        out.put(
                "note",
                "Same email/password as TRINITY cabinet when Supabase is enabled. "
                        + "Boot unlock (imoex.run.unlock) is separate from user login."
        );
        return out;
    }

    public record LoginRequest(String email, String password) {}

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest request) {
        var sb = properties.auth().supabase();
        if (!sb.enabled() || !sb.jwtConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error(
                    "supabase_disabled",
                    "Supabase auth выключен. Включите imoex.auth.supabase.enabled и url."
            ));
        }
        String email = request == null || request.email() == null ? "" : request.email().trim();
        String password = request == null || request.password() == null ? "" : request.password();
        if (email.isEmpty() || password.isEmpty()) {
            return ResponseEntity.badRequest().body(error(
                    "missing_credentials",
                    "Укажите email и пароль кабинета."
            ));
        }
        String anon = sb.anonKey() == null ? "" : sb.anonKey().trim();
        if (anon.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error(
                    "missing_anon_key",
                    "imoex.auth.supabase.anon-key пуст — нужен для password grant."
            ));
        }

        String base = sb.url().endsWith("/") ? sb.url().substring(0, sb.url().length() - 1) : sb.url();
        String uri = base + "/auth/v1/token?grant_type=password";
        try {
            byte[] payload = objectMapper.writeValueAsBytes(Map.of(
                    "email", email,
                    "password", password
            ));
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(uri))
                    .timeout(Duration.ofSeconds(20))
                    .header("apikey", anon)
                    .header("Authorization", "Bearer " + anon)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Map<String, Object> body = parseJson(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || body == null
                    || !(body.get("access_token") instanceof String token)
                    || token.isBlank()) {
                String msg = firstNonBlank(
                        stringVal(body, "error_description"),
                        stringVal(body, "msg"),
                        stringVal(body, "error"),
                        "Supabase login HTTP " + response.statusCode()
                );
                HttpStatus status = (response.statusCode() >= 400 && response.statusCode() < 500)
                        ? HttpStatus.UNAUTHORIZED
                        : HttpStatus.BAD_GATEWAY;
                return ResponseEntity.status(status).body(error("login_failed", msg));
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("access_token", body.get("access_token"));
            out.put("token_type", body.getOrDefault("token_type", "bearer"));
            out.put("expires_in", body.get("expires_in"));
            out.put("refresh_token", body.get("refresh_token"));
            out.put("email", email);
            return ResponseEntity.ok(out);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(error(
                    "login_interrupted",
                    "Запрос к Supabase прерван."
            ));
        } catch (Exception e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(error(
                    "login_upstream",
                    "Не удалось связаться с Supabase: " + detail
            ));
        }
    }

    private Map<String, Object> parseJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(text, MAP_TYPE);
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", code);
        out.put("message", message);
        return out;
    }

    private static String stringVal(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }
}
