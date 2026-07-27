package com.moex.cointegration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.config.SessionProperties;
import com.moex.cointegration.model.EventCalendarEntry;
import com.moex.cointegration.model.TradingRecommendation;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Календарь событий для INTRADAY: блок новых входов и принудительный flatten перед макро/отчётностью.
 */
@Service
public class EventCalendarRiskService {

    private static final Logger log = LoggerFactory.getLogger(EventCalendarRiskService.class);

    private final SessionProperties sessionProperties;
    private final ImoexProperties properties;
    private final ObjectMapper objectMapper;
    private List<EventCalendarEntry> events = List.of();

    public EventCalendarRiskService(SessionProperties sessionProperties, ImoexProperties properties) {
        this.sessionProperties = sessionProperties;
        this.properties = properties;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /** Тесты без файла. */
    EventCalendarRiskService(SessionProperties sessionProperties, List<EventCalendarEntry> seed) {
        this.sessionProperties = sessionProperties;
        this.properties = ImoexProperties.forTests(
                "https://iss.moex.com/iss", "TQBR", "IMOEX", 5, 0.0005,
                ImoexProperties.CointegrationProperties.of(0.05, 2.0, 0.0, 10),
                new ImoexProperties.NewsProperties(false, 10, 10, 1),
                "data", "data/charts"
        );
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        this.events = seed == null ? List.of() : List.copyOf(seed);
    }

    @PostConstruct
    void load() {
        reload();
    }

    public void reload() {
        if (!sessionProperties.isEventCalendarEnabled()) {
            events = List.of();
            return;
        }
        Path file = Path.of(sessionProperties.eventCalendarFile());
        if (!Files.isRegularFile(file)) {
            log.info("Event calendar file not found ({}), INTRADAY event overlay off", file);
            events = List.of();
            return;
        }
        try {
            EventCalendarEntry[] loaded = objectMapper.readValue(file.toFile(), EventCalendarEntry[].class);
            events = List.of(loaded);
            log.info("Loaded {} event-calendar entries from {}", loaded.length, file);
        } catch (IOException ex) {
            log.warn("Could not load event calendar {}: {}", file, ex.getMessage());
            events = List.of();
        }
    }

    public boolean enabled() {
        return sessionProperties.isEventCalendarEnabled() && !events.isEmpty();
    }

    public boolean shouldBlockNewEntry(TradingRecommendation rec, LocalDateTime at) {
        return shouldBlockNewEntry(rec.tickerY(), rec.tickerX(), at);
    }

    public boolean shouldBlockNewEntry(String tickerY, String tickerX, LocalDateTime at) {
        if (!enabled()) {
            return false;
        }
        return findActiveEvent(tickerY, tickerX, at).isPresent();
    }

    public boolean shouldFlattenNow(String tickerY, String tickerX, LocalDateTime at) {
        return shouldBlockNewEntry(tickerY, tickerX, at);
    }

    public Optional<String> eventReason(String tickerY, String tickerX, LocalDateTime at) {
        return findActiveEvent(tickerY, tickerX, at)
                .map(e -> "Событие " + e.type() + ": " + e.title() + " (" + e.date() + ")");
    }

    private Optional<EventCalendarEntry> findActiveEvent(String tickerY, String tickerX, LocalDateTime at) {
        if (!enabled()) {
            return Optional.empty();
        }
        int window = sessionProperties.eventFlattenMinutesBefore();
        for (EventCalendarEntry e : events) {
            if (!matchesTicker(e, tickerY, tickerX)) {
                continue;
            }
            LocalDateTime eventAt = eventDateTime(e);
            long minutes = ChronoUnit.MINUTES.between(at, eventAt);
            if (minutes >= 0 && minutes <= window) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    private static LocalDateTime eventDateTime(EventCalendarEntry e) {
        LocalTime t = LocalTime.of(12, 0);
        if (e.time() != null && !e.time().isBlank()) {
            try {
                t = LocalTime.parse(e.time().trim());
            } catch (Exception ignored) {
                // default noon
            }
        }
        return e.date().atTime(t);
    }

    private static boolean matchesTicker(EventCalendarEntry e, String y, String x) {
        for (String t : e.tickers()) {
            if ("*".equals(t)) {
                return true;
            }
            String u = t.toUpperCase(Locale.ROOT);
            if (u.equalsIgnoreCase(y) || u.equalsIgnoreCase(x)) {
                return true;
            }
        }
        return false;
    }

    /** Встроенные примеры для тестов / пустого файла. */
    public static List<EventCalendarEntry> sampleEntries() {
        return new ArrayList<>(Arrays.asList(
                new EventCalendarEntry(LocalDate.of(2026, 1, 1), "12:00", "MACRO", "Пример: ключевая ставка", List.of("*"))
        ));
    }
}
