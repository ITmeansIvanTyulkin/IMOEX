package com.moex.cointegration.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process ceilings for login stuffing and anonymous desk polling.
 * Authenticated desk/arb polls (Bearer / Basic / trinity.desk cookie) are not capped.
 * Local desktop app: keyed by remote address (X-Forwarded-For is ignored — spoofable).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 40)
public class ApiRateLimitFilter extends OncePerRequestFilter {

    static final int LOGIN_MAX = 8;
    static final int DESK_MAX = 90;
    static final long WINDOW_MS = 60_000L;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final DeskSessionStore sessions;

    public ApiRateLimitFilter(DeskSessionStore sessions) {
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI() == null ? "" : request.getRequestURI();
        String ip = request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
        int max = 0;
        if ("POST".equalsIgnoreCase(request.getMethod()) && path.equals("/api/auth/login")) {
            max = LOGIN_MAX;
            path = "login";
        } else if ("GET".equalsIgnoreCase(request.getMethod())
                && (path.equals("/api/trend/desk") || path.equals("/api/calendar-arb/desk"))) {
            max = DESK_MAX;
            path = "desk";
        }
        if (max > 0 && !alreadyAuthed(request) && !allow(ip + "|" + path, max)) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(
                    "{\"error\":\"rate_limited\",\"message\":\"Too many requests — wait a minute.\"}"
            );
            return;
        }
        filterChain.doFilter(request, response);
    }

    boolean allow(String key, int max) {
        long now = System.currentTimeMillis();
        Window w = windows.compute(key, (k, prev) -> {
            if (prev == null || now >= prev.resetAt) {
                return new Window(new AtomicInteger(1), now + WINDOW_MS);
            }
            prev.count.incrementAndGet();
            return prev;
        });
        if (windows.size() > 4_000) {
            windows.entrySet().removeIf(e -> now >= e.getValue().resetAt);
        }
        return w.count.get() <= max;
    }

    /** Login stuffing still capped. Desk poll from a live session must not 429 the operator. */
    boolean alreadyAuthed(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && !auth.isBlank()) {
            return true;
        }
        return sessions != null && sessions.valid(sessions.idOf(request));
    }

    private record Window(AtomicInteger count, long resetAt) {
    }
}
