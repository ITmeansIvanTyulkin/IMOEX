package com.moex.trinity.trend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Oil/macro event blackout for new BR setups (before and after release).
 */
public final class TrendEventCalendar {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<TrendEventEntry> events;
    private final int minutesBefore;
    private final int minutesAfter;

    public TrendEventCalendar(List<TrendEventEntry> events, int minutesBefore, int minutesAfter) {
        this.events = events == null ? List.of() : List.copyOf(events);
        this.minutesBefore = Math.max(0, minutesBefore);
        this.minutesAfter = Math.max(0, minutesAfter);
    }

    public static TrendEventCalendar empty() {
        return new TrendEventCalendar(List.of(), 0, 0);
    }

    public static TrendEventCalendar fromSettings(TrendPlaybookSettings settings) {
        if (settings == null || !settings.eventCalendarEnabled()) {
            return empty();
        }
        Path file = Path.of(settings.eventCalendarFile() == null || settings.eventCalendarFile().isBlank()
                ? "data/trend-event-calendar.json"
                : settings.eventCalendarFile());
        return load(file, settings.eventBlockMinutesBefore(), settings.eventBlockMinutesAfter());
    }

    public static TrendEventCalendar load(Path file, int minutesBefore, int minutesAfter) {
        if (file == null || !Files.isRegularFile(file)) {
            return new TrendEventCalendar(List.of(), minutesBefore, minutesAfter);
        }
        try {
            JsonNode root = MAPPER.readTree(file.toFile());
            if (!root.isArray()) {
                return new TrendEventCalendar(List.of(), minutesBefore, minutesAfter);
            }
            List<TrendEventEntry> list = new ArrayList<>();
            for (JsonNode n : root) {
                LocalDate date = LocalDate.parse(n.path("date").asText());
                String time = n.path("time").asText("12:00");
                String type = n.path("type").asText("EVENT");
                String title = n.path("title").asText(type);
                List<String> tickers = new ArrayList<>();
                JsonNode t = n.path("tickers");
                if (t.isArray()) {
                    t.forEach(x -> tickers.add(x.asText()));
                }
                list.add(new TrendEventEntry(date, time, type, title, tickers));
            }
            return new TrendEventCalendar(list, minutesBefore, minutesAfter);
        } catch (Exception ex) {
            return new TrendEventCalendar(List.of(), minutesBefore, minutesAfter);
        }
    }

    public boolean enabled() {
        return !events.isEmpty() && (minutesBefore > 0 || minutesAfter > 0);
    }

    public int size() {
        return events.size();
    }

    /**
     * @return block reason, or null if tradable
     */
    public String blockReason(LocalDateTime at, String instrument) {
        if (!enabled() || at == null) {
            return null;
        }
        for (TrendEventEntry e : events) {
            if (!e.matchesInstrument(instrument)) {
                continue;
            }
            LocalDateTime eventAt = e.date().atTime(e.eventTime());
            long minutesTo = ChronoUnit.MINUTES.between(at, eventAt);
            // before: at is before event, minutesTo in (0..before]
            if (minutesTo > 0 && minutesTo <= minutesBefore) {
                return "event edge: " + e.type() + " «" + e.title() + "» in " + minutesTo
                        + " min (block " + minutesBefore + "m before)";
            }
            // after: at is after event, minutesFrom in [0..after]
            long minutesFrom = ChronoUnit.MINUTES.between(eventAt, at);
            if (minutesFrom >= 0 && minutesFrom <= minutesAfter) {
                return "event edge: " + e.type() + " «" + e.title() + "» +" + minutesFrom
                        + " min (block " + minutesAfter + "m after)";
            }
        }
        return null;
    }
}
