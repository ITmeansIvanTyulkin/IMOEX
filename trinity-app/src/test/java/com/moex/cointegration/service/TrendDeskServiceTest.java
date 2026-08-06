package com.moex.cointegration.service;

import com.moex.trinity.trend.TrendBar;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrendDeskServiceTest {

    @Test
    void mergePreferTapeOverwritesIssBucket() {
        TrendBar iss = new TrendBar(LocalDateTime.of(2026, 8, 6, 10, 0), 80, 81, 79, 80.5, 100);
        TrendBar tape = new TrendBar(LocalDateTime.of(2026, 8, 6, 10, 0), 80.1, 80.3, 80.0, 80.2, 12);
        TrendBar laterIss = new TrendBar(LocalDateTime.of(2026, 8, 6, 10, 5), 80.5, 80.6, 80.4, 80.55, 50);
        List<TrendBar> merged = TrendDeskService.mergePreferTape(List.of(iss, laterIss), List.of(tape));
        assertEquals(2, merged.size());
        assertEquals(80.1, merged.get(0).open(), 1e-9);
        assertEquals(12.0, merged.get(0).volume(), 1e-9);
        assertEquals(80.5, merged.get(1).open(), 1e-9);
    }

    @Test
    void mergeEmptyTapeKeepsIss() {
        List<TrendBar> iss = List.of(
                new TrendBar(LocalDateTime.of(2026, 8, 6, 9, 0), 1, 1, 1, 1, 1)
        );
        assertEquals(1, TrendDeskService.mergePreferTape(iss, List.of()).size());
    }
}
