package com.moex.cointegration.smoke;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory result of startup smoke (soft-block + self-heal retries).
 */
@Component
public class StartupSmokeStatus {

    public enum Phase {
        PENDING,
        RUNNING,
        OK,
        FAIL,
        SKIPPED
    }

    public record Check(String name, boolean ok, String detail) {
    }

    public record Snapshot(
            Phase phase,
            boolean ok,
            boolean executionBlocked,
            int attempt,
            int maxAttempts,
            Instant updatedAt,
            List<Check> checks,
            String message
    ) {
    }

    private final AtomicReference<Snapshot> snap = new AtomicReference<>(
            new Snapshot(Phase.PENDING, false, false, 0, 0, Instant.now(), List.of(), "smoke not started")
    );

    public Snapshot get() {
        return snap.get();
    }

    public synchronized void pending(int maxAttempts) {
        snap.set(new Snapshot(Phase.PENDING, false, false, 0, maxAttempts, Instant.now(), List.of(), "waiting"));
    }

    public synchronized void running(int attempt, int maxAttempts) {
        snap.set(new Snapshot(Phase.RUNNING, false, snap.get().executionBlocked(), attempt, maxAttempts,
                Instant.now(), List.of(), "running attempt " + attempt));
    }

    public synchronized void ok(int attempt, int maxAttempts, List<Check> checks, boolean keepBlocked) {
        snap.set(new Snapshot(Phase.OK, true, keepBlocked, attempt, maxAttempts, Instant.now(),
                List.copyOf(checks), keepBlocked
                        ? "OK after fail — auto/live remain off until operator re-enables"
                        : "OK"));
    }

    public synchronized void fail(int attempt, int maxAttempts, List<Check> checks, String message) {
        snap.set(new Snapshot(Phase.FAIL, false, true, attempt, maxAttempts, Instant.now(),
                List.copyOf(checks), message == null ? "FAIL" : message));
    }

    public synchronized void skipped(String reason) {
        snap.set(new Snapshot(Phase.SKIPPED, true, false, 0, 0, Instant.now(), List.of(), reason));
    }

    public Map<String, Object> dto() {
        Snapshot s = snap.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phase", s.phase().name());
        m.put("ok", s.ok());
        m.put("executionBlocked", s.executionBlocked());
        m.put("attempt", s.attempt());
        m.put("maxAttempts", s.maxAttempts());
        m.put("updatedAt", s.updatedAt() == null ? null : s.updatedAt().toString());
        m.put("message", s.message());
        List<Map<String, Object>> checks = new ArrayList<>();
        for (Check c : s.checks()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", c.name());
            row.put("ok", c.ok());
            row.put("detail", c.detail());
            checks.add(row);
        }
        m.put("checks", checks);
        m.put("checklist", List.of(
                "GET /api/ops/smoke — какой check упал",
                "401 на GET desk/settings — не слать Bearer на публичный GET / сломан security",
                "5xx на desk — warmup/архив; не торговать",
                "POST settings без auth не 401 при auth.enabled=true — дыра в security",
                "Починить → POST /api/ops/smoke/rerun или рестарт; auto/live включать вручную"
        ));
        return m;
    }
}
