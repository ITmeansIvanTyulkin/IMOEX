package com.moex.cointegration.service;

import com.moex.cointegration.config.SessionProperties;
import com.moex.cointegration.model.EventCalendarEntry;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventCalendarRiskServiceTest {

    @Test
    void blocksEntryInsideFlattenWindow() {
        SessionProperties session = new SessionProperties(
                "DUAL", "18:30", "18:45", "10:00", "18:45", true,
                "paper-journal-intraday.json", 60, 7, 90, 48, 7, 0.25, 3.0,
                true, 60, "data/event-calendar.json"
        );
        EventCalendarEntry event = new EventCalendarEntry(
                LocalDate.of(2026, 7, 28),
                "14:00",
                "EARNINGS",
                "SBER отчёт",
                List.of("SBER")
        );
        EventCalendarRiskService svc = new EventCalendarRiskService(session, List.of(event));

        assertTrue(svc.shouldBlockNewEntry("SBER", "LKOH", LocalDateTime.of(2026, 7, 28, 13, 30)));
        assertFalse(svc.shouldBlockNewEntry("SBER", "LKOH", LocalDateTime.of(2026, 7, 28, 10, 0)));
        assertFalse(svc.shouldBlockNewEntry("GAZP", "ROSN", LocalDateTime.of(2026, 7, 28, 13, 30)));
    }
}
