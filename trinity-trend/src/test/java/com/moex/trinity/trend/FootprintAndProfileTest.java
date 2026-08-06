package com.moex.trinity.trend;

import org.junit.jupiter.api.Test;

import com.moex.trinity.marketdata.TradePrint;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FootprintAndProfileTest {

    @Test
    void profileLevelsFromBars() {
        VolumeAtPriceBuilder vap = new VolumeAtPriceBuilder(TrendInstrumentSpec.br(15, 20, 15, 25, 7.0));
        List<TrendBar> bars = new ArrayList<>();
        LocalDateTime t = LocalDateTime.of(2026, 8, 6, 10, 0);
        for (int i = 0; i < 30; i++) {
            double c = 80 + (i % 5) * 0.01;
            bars.add(new TrendBar(t.plusMinutes(i * 5L), c, c + 0.05, c - 0.05, c, 1000));
        }
        var levels = vap.profileLevels(bars, 30);
        assertFalse(levels.isEmpty());
        assertTrue(levels.stream().anyMatch(l -> l.strength() >= 0.99));
    }

    @Test
    void footprintSplitsBuySell() {
        FootprintAggregator fp = new FootprintAggregator(0.01);
        Instant t0 = LocalDateTime.of(2026, 8, 6, 10, 1).toInstant(ZoneOffset.ofHours(3));
        List<TradePrint> prints = List.of(
                new TradePrint("BRU6", 80.10, 5, t0, TradePrint.TradeSide.BUY),
                new TradePrint("BRU6", 80.10, 3, t0.plusSeconds(1), TradePrint.TradeSide.SELL),
                new TradePrint("BRU6", 80.11, 2, t0.plusSeconds(2), TradePrint.TradeSide.BUY)
        );
        var bars = fp.build(prints, 10);
        assertFalse(bars.isEmpty());
        assertTrue(bars.get(0).levels().stream().anyMatch(l -> l.buyLots() >= 5 && l.sellLots() >= 3));
    }
}
