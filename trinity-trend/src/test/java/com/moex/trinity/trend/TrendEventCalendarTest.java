package com.moex.trinity.trend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrendEventCalendarTest {

    @Test
    void blocksBeforeAndAfterRelease() {
        TrendEventCalendar cal = new TrendEventCalendar(
                List.of(new TrendEventEntry(
                        java.time.LocalDate.of(2026, 8, 5),
                        "17:30",
                        "OIL_INVENTORY",
                        "EIA",
                        List.of("BR")
                )),
                45,
                30
        );
        assertNotNull(cal.blockReason(LocalDateTime.of(2026, 8, 5, 16, 50), "BRU6"));
        assertNotNull(cal.blockReason(LocalDateTime.of(2026, 8, 5, 17, 30), "BRU6"));
        assertNotNull(cal.blockReason(LocalDateTime.of(2026, 8, 5, 17, 55), "BRU6"));
        assertNull(cal.blockReason(LocalDateTime.of(2026, 8, 5, 16, 40), "BRU6"));
        assertNull(cal.blockReason(LocalDateTime.of(2026, 8, 5, 18, 5), "BRU6"));
        assertNull(cal.blockReason(LocalDateTime.of(2026, 8, 5, 17, 30), "SiU6"));
    }

    @Test
    void loadsFromFile(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("events.json");
        Files.writeString(f, """
                [{"date":"2026-08-05","time":"17:30","type":"OIL_INVENTORY","title":"EIA","tickers":["BR"]}]
                """);
        TrendEventCalendar cal = TrendEventCalendar.load(f, 45, 30);
        assertTrue(cal.enabled());
        assertNotNull(cal.blockReason(LocalDateTime.of(2026, 8, 5, 17, 0), "BRU6"));
    }
}
