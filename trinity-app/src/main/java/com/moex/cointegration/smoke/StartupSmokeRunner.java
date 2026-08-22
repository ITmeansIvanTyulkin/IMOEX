package com.moex.cointegration.smoke;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.service.TrendSettingsService;
import com.moex.cointegration.smoke.StartupSmokeStatus.Check;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Short HTTP smoke after ready: soft-block auto/live on fail, retry to self-heal transients.
 * Does not kill the JVM.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "imoex.smoke", name = "on-startup", havingValue = "true", matchIfMissing = true)
public class StartupSmokeRunner implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartupSmokeRunner.class);

    private final StartupSmokeStatus status;
    private final Environment environment;
    private final ImoexProperties properties;
    private final ObjectProvider<TrendSettingsService> trendSettings;
    private final int retryMax;
    private final long retryDelayMs;
    private final RestTemplate http = new RestTemplate();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "trinity-startup-smoke");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final AtomicBoolean everFailed = new AtomicBoolean(false);
    private volatile String baseUrl;

    public StartupSmokeRunner(
            StartupSmokeStatus status,
            Environment environment,
            ImoexProperties properties,
            ObjectProvider<TrendSettingsService> trendSettings,
            @Value("${imoex.smoke.retry-max:5}") int retryMax,
            @Value("${imoex.smoke.retry-delay-ms:15000}") long retryDelayMs
    ) {
        this.status = status;
        this.environment = environment;
        this.properties = properties;
        this.trendSettings = trendSettings;
        this.retryMax = Math.max(1, retryMax);
        this.retryDelayMs = Math.max(1000L, retryDelayMs);
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        int port = resolvePort(event);
        if (port <= 0) {
            status.skipped("no local web port — smoke skipped");
            log.info("Startup smoke skipped (no web port)");
            return;
        }
        baseUrl = "http://127.0.0.1:" + port;
        status.pending(retryMax);
        scheduler.execute(() -> runAttempt(1));
    }

    /** Operator / self-heal kick. */
    public void rerun() {
        if (baseUrl == null || baseUrl.isBlank()) {
            status.fail(0, retryMax, List.of(), "smoke base URL unknown — restart app");
            return;
        }
        scheduler.execute(() -> runAttempt(1));
    }

    private void runAttempt(int attempt) {
        if (!inFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            status.running(attempt, retryMax);
            List<Check> checks = executeChecks();
            boolean allOk = checks.stream().allMatch(Check::ok);
            if (allOk) {
                boolean keepBlocked = everFailed.get();
                status.ok(attempt, retryMax, checks, keepBlocked);
                if (keepBlocked) {
                    log.warn("Startup smoke OK after earlier FAIL — auto/live stay off until operator re-enables. {}",
                            status.get().message());
                } else {
                    log.info("Startup smoke OK (attempt {}/{})", attempt, retryMax);
                }
                return;
            }
            everFailed.set(true);
            softBlockExecution();
            String msg = "Startup smoke FAIL attempt " + attempt + "/" + retryMax;
            status.fail(attempt, retryMax, checks, msg);
            log.error("{} — execution soft-blocked. Checks: {}", msg, checks);
            log.error("Checklist: GET /api/ops/smoke → fix → POST /api/ops/smoke/rerun (or wait for retry)");
            if (attempt < retryMax) {
                scheduler.schedule(() -> runAttempt(attempt + 1), retryDelayMs, TimeUnit.MILLISECONDS);
            } else {
                log.error("Startup smoke exhausted retries — stay blocked. See /api/ops/smoke");
            }
        } finally {
            inFlight.set(false);
        }
    }

    private List<Check> executeChecks() {
        List<Check> out = new ArrayList<>();
        out.add(getOk("actuator.health", "/actuator/health", 200));
        out.add(getOk("view.trend-signal", "/view/trend-signal", 200));
        out.add(getOk("view.trend-positional", "/view/trend-positional", 200));
        out.add(getOk("api.trend.settings", "/api/trend/settings", 200));
        out.add(getNot5xx("api.trend.desk", "/api/trend/desk"));
        if (properties.auth() != null && properties.auth().enabled()) {
            out.add(postUnauthorized("api.trend.settings.post.auth", "/api/trend/settings"));
        } else {
            out.add(new Check("api.trend.settings.post.auth", true, "skipped (imoex.auth.enabled=false)"));
        }
        return out;
    }

    private Check getOk(String name, String path, int expectStatus) {
        try {
            ResponseEntity<String> res = http.exchange(baseUrl + path, HttpMethod.GET,
                    new HttpEntity<>(jsonHeaders()), String.class);
            int code = res.getStatusCode().value();
            boolean ok = code == expectStatus;
            return new Check(name, ok, "HTTP " + code + (ok ? "" : " expected " + expectStatus));
        } catch (RestClientResponseException ex) {
            return new Check(name, false, "HTTP " + ex.getStatusCode().value() + " " + truncate(ex.getResponseBodyAsString()));
        } catch (Exception ex) {
            return new Check(name, false, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private Check getNot5xx(String name, String path) {
        try {
            ResponseEntity<String> res = http.exchange(baseUrl + path, HttpMethod.GET,
                    new HttpEntity<>(jsonHeaders()), String.class);
            int code = res.getStatusCode().value();
            boolean ok = code < 500;
            return new Check(name, ok, "HTTP " + code + (ok ? "" : " server error"));
        } catch (RestClientResponseException ex) {
            int code = ex.getStatusCode().value();
            boolean ok = code < 500;
            return new Check(name, ok, "HTTP " + code + " " + truncate(ex.getResponseBodyAsString()));
        } catch (Exception ex) {
            return new Check(name, false, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private Check postUnauthorized(String name, String path) {
        try {
            HttpHeaders headers = jsonHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            http.exchange(baseUrl + path, HttpMethod.POST,
                    new HttpEntity<>("{}", headers), String.class);
            return new Check(name, false, "HTTP 2xx — POST must require auth when imoex.auth.enabled=true");
        } catch (RestClientResponseException ex) {
            int code = ex.getStatusCode().value();
            boolean ok = code == 401;
            return new Check(name, ok, "HTTP " + code + (ok ? " unauthorized as expected" : " expected 401"));
        } catch (Exception ex) {
            return new Check(name, false, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void softBlockExecution() {
        TrendSettingsService ts = trendSettings.getIfAvailable();
        if (ts == null) {
            return;
        }
        try {
            ts.save(new TrendSettingsService.UpdateRequest(false, false, null, null));
            log.warn("Soft-block: trend autoExecution=false liveExecution=false");
        } catch (Exception ex) {
            log.warn("Soft-block could not update trend settings: {}", ex.getMessage());
        }
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_HTML, MediaType.ALL));
        return h;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }

    private int resolvePort(ApplicationReadyEvent event) {
        try {
            if (event.getApplicationContext() instanceof ServletWebServerApplicationContext swac) {
                return swac.getWebServer().getPort();
            }
            if (event.getApplicationContext() instanceof WebServerApplicationContext wac) {
                return wac.getWebServer().getPort();
            }
        } catch (Exception ex) {
            log.debug("web port resolve: {}", ex.getMessage());
        }
        String local = environment.getProperty("local.server.port");
        if (local != null && !local.isBlank()) {
            try {
                return Integer.parseInt(local.trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return environment.getProperty("server.port", Integer.class, 8080);
    }
}
