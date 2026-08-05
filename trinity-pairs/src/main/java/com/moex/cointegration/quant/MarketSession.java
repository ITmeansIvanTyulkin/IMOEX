package com.moex.cointegration.quant;

import com.moex.cointegration.config.SessionProperties;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Сессия MOEX для intraday flatten.
 */
public final class MarketSession {

    public enum Phase { CLOSED, OPEN, PRE_CLOSE, OVERNIGHT }

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("H:mm");

    private MarketSession() {
    }

    public static Phase current(LocalDateTime now, SessionProperties session) {
        LocalTime t = now.toLocalTime();
        LocalTime open = parse(session.sessionOpen(), LocalTime.of(10, 0));
        LocalTime preStart = parse(session.preCloseStart(), LocalTime.of(18, 30));
        LocalTime preEnd = parse(session.preCloseEnd(), LocalTime.of(18, 45));

        if (t.isBefore(open)) {
            return Phase.CLOSED;
        }
        if (t.isBefore(preStart)) {
            return Phase.OPEN;
        }
        if (!t.isAfter(preEnd)) {
            return Phase.PRE_CLOSE;
        }
        return Phase.OVERNIGHT;
    }

    public static boolean isWeekend(LocalDate date) {
        return date.getDayOfWeek().getValue() >= 6;
    }

    private static LocalTime parse(String text, LocalTime fallback) {
        try {
            return LocalTime.parse(text.trim(), HM);
        } catch (Exception ex) {
            return fallback;
        }
    }
}
