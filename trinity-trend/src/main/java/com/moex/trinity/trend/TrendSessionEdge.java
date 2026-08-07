package com.moex.trinity.trend;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * BR FORTS session edge: new setups only in the main session window
 * (default 10:00–19:00 MSK), not evening thin tape.
 */
public final class TrendSessionEdge {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("H:mm");

    private TrendSessionEdge() {
    }

    /**
     * @return block reason, or null if time is tradable for new arms
     */
    public static String blockReason(LocalDateTime at, TrendPlaybookSettings settings) {
        if (at == null || settings == null) {
            return null;
        }
        LocalTime t = at.toLocalTime();
        LocalTime open = parse(settings.tradeSessionOpen(), LocalTime.of(9, 0));
        LocalTime close = parse(settings.tradeSessionClose(), LocalTime.of(23, 50));
        int afterOpen = Math.max(0, settings.noTradeAfterOpenMinutes());
        int beforeClose = Math.max(0, settings.noTradeBeforeCloseMinutes());

        LocalTime tradeFrom = open.plusMinutes(afterOpen);
        LocalTime tradeUntil = close.minusMinutes(beforeClose);
        if (tradeUntil.isBefore(tradeFrom) || tradeUntil.equals(tradeFrom)) {
            return "session edge: invalid open/close window";
        }

        if (t.isBefore(tradeFrom)) {
            if (t.isBefore(open)) {
                return "session edge: before open " + open + " (no trade until " + tradeFrom + ")";
            }
            return "session edge: first " + afterOpen + " min after open " + open
                    + " (no trade until " + tradeFrom + ")";
        }
        if (!t.isBefore(tradeUntil)) {
            return "session edge: last " + beforeClose + " min before close " + close
                    + " (flat new setups from " + tradeUntil + ")";
        }
        return null;
    }

    public static boolean isTradable(LocalDateTime at, TrendPlaybookSettings settings) {
        return blockReason(at, settings) == null;
    }

    static LocalTime parse(String raw, LocalTime fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalTime.parse(raw.trim(), HM);
        } catch (DateTimeParseException ex) {
            try {
                return LocalTime.parse(raw.trim());
            } catch (DateTimeParseException ex2) {
                return fallback;
            }
        }
    }
}
