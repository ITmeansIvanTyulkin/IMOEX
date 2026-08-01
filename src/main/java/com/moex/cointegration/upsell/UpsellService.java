package com.moex.cointegration.upsell;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.config.UpsellProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Tracks operator actions and suggests a soft Full Core / calendar-arbitrage upsell.
 * Research/decision-support copy only — no billing, no return promises.
 */
@Service
public class UpsellService {

    public static final String FEATURE_CALENDAR_ARB = "CALENDAR_ARB";
    public static final String PROMPT_ID_CALENDAR_ARB = "upsell-calendar-arb-v1";
    public static final String TARGET_TIER_FULL_CORE = "FULL_CORE";

    private static final Logger log = LoggerFactory.getLogger(UpsellService.class);
    private static final int MAX_EVENTS = 500;

    private final UpsellProperties properties;
    private final Path storeFile;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final UpsellStore store = new UpsellStore();

    @Autowired
    public UpsellService(UpsellProperties properties, ImoexProperties imoexProperties) {
        this.properties = properties != null ? properties : UpsellProperties.defaults();
        this.storeFile = Path.of(imoexProperties.dataDir(), "upsell-events.json");
    }

    /** Test / in-memory constructor (no file I/O). */
    UpsellService(UpsellProperties properties) {
        this.properties = properties != null ? properties : UpsellProperties.defaults();
        this.storeFile = null;
    }

    @PostConstruct
    void load() {
        if (storeFile == null || !Files.exists(storeFile)) {
            return;
        }
        try {
            UpsellStore loaded = objectMapper.readValue(storeFile.toFile(), UpsellStore.class);
            if (loaded != null) {
                store.setEvents(loaded.getEvents());
                store.setDismissals(loaded.getDismissals());
            }
        } catch (Exception ex) {
            log.warn("Could not load upsell store {}: {}", storeFile, ex.getMessage());
        }
    }

    public synchronized UpsellEvent recordEvent(UpsellEventRequest request) {
        if (!properties.enabledFlag()) {
            return null;
        }
        String action = blankTo(request != null ? request.action() : null, "unknown");
        String page = blankTo(request != null ? request.page() : null, "");
        String tierHint = request != null && request.tierHint() != null && !request.tierHint().isBlank()
                ? request.tierHint().trim()
                : null;
        UpsellEvent event = new UpsellEvent(action, page, Instant.now(), tierHint);
        store.getEvents().add(event);
        trimEvents();
        persist();
        return event;
    }

    public synchronized Optional<UpsellPrompt> suggestPrompt() {
        return suggestPrompt(Instant.now());
    }

    /** Package-visible for unit tests with a fixed clock. */
    synchronized Optional<UpsellPrompt> suggestPrompt(Instant now) {
        if (!properties.enabledFlag()) {
            return Optional.empty();
        }
        if (isOnCooldown(FEATURE_CALENDAR_ARB, now)) {
            return Optional.empty();
        }
        if (store.getEvents().size() < properties.minEventsBeforePrompt()) {
            return Optional.empty();
        }
        if (!heuristicTriggered()) {
            return Optional.empty();
        }
        return Optional.of(calendarArbPrompt());
    }

    public synchronized void dismiss(UpsellDismissRequest request) {
        if (!properties.enabledFlag()) {
            return;
        }
        String featureKey = blankTo(
                request != null ? request.featureKey() : null,
                FEATURE_CALENDAR_ARB
        );
        store.getDismissals().put(featureKey, Instant.now());
        persist();
    }

    UpsellPrompt calendarArbPrompt() {
        String price = formatRub(properties.fullPriceRub());
        return new UpsellPrompt(
                PROMPT_ID_CALENDAR_ARB,
                "Full Core · календарный арбитраж",
                "На Full Core доступны calendar arbitrage (strategy 3) и более глубокий research-контур — "
                        + price + " ₽/мес. Research / decision-support, без обещания доходности.",
                "Подробнее о стратегии",
                "/view/strategy",
                TARGET_TIER_FULL_CORE,
                FEATURE_CALENDAR_ARB
        );
    }

    boolean heuristicTriggered() {
        int dashboardViews = 0;
        boolean ranAnalysis = false;
        boolean openedSettings = false;
        boolean viewedPaper = false;

        for (UpsellEvent e : store.getEvents()) {
            String action = e.action() != null ? e.action() : "";
            String page = normalizePage(e.page());

            if ("page_view".equals(action)) {
                if (isDashboard(page)) {
                    dashboardViews++;
                } else if (page.startsWith("/view/settings")) {
                    openedSettings = true;
                } else if (page.startsWith("/view/paper")) {
                    viewedPaper = true;
                }
            } else if ("run-fast".equals(action) || "run-full".equals(action)
                    || "analysis_run".equals(action)) {
                ranAnalysis = true;
            } else if ("open_settings".equals(action)) {
                openedSettings = true;
            } else if ("view_paper".equals(action)) {
                viewedPaper = true;
            }
        }

        return dashboardViews >= properties.minDashboardViews()
                || ranAnalysis
                || openedSettings
                || viewedPaper;
    }

    boolean isOnCooldown(String featureKey, Instant now) {
        Instant dismissedAt = store.getDismissals().get(featureKey);
        if (dismissedAt == null) {
            return false;
        }
        Duration cool = Duration.ofHours(properties.cooldownHours());
        return dismissedAt.plus(cool).isAfter(now);
    }

    /** Visible for tests. */
    synchronized void seedEvent(UpsellEvent event) {
        store.getEvents().add(event);
        trimEvents();
    }

    synchronized void seedDismissal(String featureKey, Instant at) {
        store.getDismissals().put(featureKey, at);
    }

    private void trimEvents() {
        var events = store.getEvents();
        if (events.size() > MAX_EVENTS) {
            store.setEvents(new java.util.ArrayList<>(
                    events.subList(events.size() - MAX_EVENTS, events.size())
            ));
        }
    }

    private void persist() {
        if (storeFile == null) {
            return;
        }
        try {
            Files.createDirectories(storeFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(storeFile.toFile(), store);
        } catch (Exception ex) {
            log.warn("Could not persist upsell store {}: {}", storeFile, ex.getMessage());
        }
    }

    private static boolean isDashboard(String page) {
        return page.equals("/view") || page.equals("/view/") || page.isEmpty() || page.equals("/");
    }

    private static String normalizePage(String page) {
        if (page == null || page.isBlank()) {
            return "";
        }
        String p = page.trim();
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String formatRub(int amount) {
        NumberFormat nf = NumberFormat.getInstance(new Locale("ru", "RU"));
        nf.setGroupingUsed(true);
        nf.setMaximumFractionDigits(0);
        return nf.format(amount);
    }
}
