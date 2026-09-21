package com.moex.cointegration.config;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Tape WS is browser-only on this machine. {@code origins=*} previously allowed any site
 * to subscribe if :8080 was reachable.
 */
@Component
public class TrendTapeHandshakeInterceptor implements HandshakeInterceptor {

    private final ImoexProperties properties;
    private final DeskSessionStore sessions;

    public TrendTapeHandshakeInterceptor(ImoexProperties properties, DeskSessionStore sessions) {
        this.properties = properties;
        this.sessions = sessions;
    }

    @Override
    public boolean beforeHandshake(
            @NonNull ServerHttpRequest request,
            @NonNull ServerHttpResponse response,
            @NonNull WebSocketHandler wsHandler,
            @NonNull Map<String, Object> attributes
    ) {
        if (!originAllowed(originOf(request))) {
            return false;
        }
        boolean authOn = properties.auth() != null && properties.auth().enabled();
        if (!authOn) {
            return true;
        }
        if (deskSessionOk(request)) {
            return true;
        }
        String token = accessToken(request);
        return token != null && !token.isBlank();
    }

    @Override
    public void afterHandshake(
            @NonNull ServerHttpRequest request,
            @NonNull ServerHttpResponse response,
            @NonNull WebSocketHandler wsHandler,
            Exception exception
    ) {
        // no-op
    }

    static boolean originAllowed(String origin) {
        if (origin == null || origin.isBlank()) {
            return false;
        }
        try {
            URI u = URI.create(origin.trim());
            String host = u.getHost();
            String scheme = u.getScheme();
            if (host == null || scheme == null) {
                return false;
            }
            boolean local = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
            boolean http = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            return local && http;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private boolean deskSessionOk(ServerHttpRequest request) {
        if (sessions == null) {
            return false;
        }
        if (request instanceof ServletServerHttpRequest servlet) {
            return sessions.valid(sessions.idOf(servlet.getServletRequest()));
        }
        return false;
    }

    private static String originOf(ServerHttpRequest request) {
        List<String> origins = request.getHeaders().get("Origin");
        if (origins != null && !origins.isEmpty()) {
            return origins.get(0);
        }
        return null;
    }

    private static String accessToken(ServerHttpRequest request) {
        List<String> auth = request.getHeaders().get("Authorization");
        if (auth != null && !auth.isEmpty()) {
            String v = auth.get(0);
            if (v != null && v.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return v.substring(7).trim();
            }
        }
        if (request instanceof ServletServerHttpRequest servlet) {
            String q = servlet.getServletRequest().getParameter("access_token");
            if (q != null && !q.isBlank()) {
                return q.trim();
            }
        }
        URI uri = request.getURI();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            if ("access_token".equals(k) && eq >= 0 && eq < part.length() - 1) {
                return java.net.URLDecoder.decode(part.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
