package com.moex.trinity.trend;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;

/**
 * Scheduled oil/macro event for BR trend robot blackout window.
 */
public record TrendEventEntry(
        LocalDate date,
        String time,
        String type,
        String title,
        List<String> tickers
) {
    public TrendEventEntry {
        if (tickers == null || tickers.isEmpty()) {
            tickers = List.of("*");
        } else {
            tickers = List.copyOf(tickers);
        }
        if (type == null || type.isBlank()) {
            type = "EVENT";
        }
        if (title == null) {
            title = type;
        }
        if (time == null || time.isBlank()) {
            time = "12:00";
        }
    }

    public boolean matchesInstrument(String instrument) {
        if (instrument == null || instrument.isBlank()) {
            return tickers.stream().anyMatch(t -> "*".equals(t));
        }
        String u = instrument.trim().toUpperCase(Locale.ROOT);
        for (String t : tickers) {
            if (t == null) {
                continue;
            }
            String tok = t.trim().toUpperCase(Locale.ROOT);
            if ("*".equals(tok) || tok.equals(u)) {
                return true;
            }
            // BR matches BRU6 / BRQ6…
            if ("BR".equals(tok) && u.startsWith("BR")) {
                return true;
            }
        }
        return false;
    }

    public LocalTime eventTime() {
        try {
            return LocalTime.parse(time.trim());
        } catch (Exception ex) {
            return LocalTime.of(12, 0);
        }
    }
}
