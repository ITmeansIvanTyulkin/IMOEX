package com.moex.cointegration.quant;

import com.moex.cointegration.config.MicrostructureProperties;
import com.moex.cointegration.model.Candle;
import com.moex.cointegration.quant.microstructure.CandleMicrostructureAnalyzer;
import com.moex.cointegration.quant.microstructure.VolumeProfileCalculator;
import com.moex.cointegration.quant.trend.OrderFlowAnalyzer;
import com.moex.cointegration.quant.trend.TrendAbsorptionDetector;
import com.moex.cointegration.quant.trend.TrendMicrostructureFacade;
import com.moex.cointegration.quant.trend.ValueAreaBreakoutDetector;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicrostructureQuantTest {

    @Test
    void volumeProfileFindsPoc() {
        List<Candle> candles = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 20; i++) {
            candles.add(new Candle(d.plusDays(i), 100, 101, 99, 100, 1000));
        }
        candles.add(new Candle(d.plusDays(20), 200, 201, 199, 200, 50_000));
        var profile = VolumeProfileCalculator.fromCandles(candles, 21);
        assertTrue(Math.abs(profile.pocPrice() - 200) < 0.01);
        assertTrue(profile.containsPrice(200));
    }

    @Test
    void relativeVolumeFlagsDeadBar() {
        List<Candle> candles = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 20; i++) {
            candles.add(new Candle(d.plusDays(i), 100, 101, 99, 100, 1_000_000));
        }
        candles.add(new Candle(d.plusDays(20), 100, 101, 99, 100, 10_000));
        var ms = CandleMicrostructureAnalyzer.analyze(candles, 20);
        assertTrue(ms.relativeVolume() < 0.1);
    }

    @Test
    void trendFacadeDetectsBreakoutUp() {
        List<Candle> candles = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 30; i++) {
            candles.add(new Candle(d.plusDays(i), 100, 101, 99, 100, 1_000_000));
        }
        for (int i = 0; i < 3; i++) {
            candles.add(new Candle(d.plusDays(30 + i), 110 + i, 112 + i, 109 + i, 111 + i, 2_000_000));
        }
        var cfg = new MicrostructureProperties.TrendMicrostructureProperties(
                true, 0.40, 3, 2.0, 50.0, 0.30, 24);
        var snap = TrendMicrostructureFacade.snapshot(candles, cfg);
        assertTrue(snap.breakout() == ValueAreaBreakoutDetector.BreakoutDirection.UP
                || snap.deltaMomentum() > 0.3);
    }

    @Test
    void absorptionRequiresHighVolumeTightRange() {
        List<Candle> candles = baseVolumeSeries(100, 1_000_000);
        candles.add(new Candle(LocalDate.of(2026, 2, 1), 100, 100.05, 99.95, 100, 5_000_000));
        assertTrue(TrendAbsorptionDetector.absorptionAtLevel(candles, 20, 2.0, 20));
    }

    @Test
    void orderFlowMomentumPositiveOnGreenCloses() {
        List<Candle> candles = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 10; i++) {
            candles.add(new Candle(d.plusDays(i), 100, 105, 99, 104, 1_000_000));
        }
        assertTrue(OrderFlowAnalyzer.bullishMomentum(candles, 10, 0.3));
        assertFalse(OrderFlowAnalyzer.bearishMomentum(candles, 10, 0.3));
    }

    private static List<Candle> baseVolumeSeries(double close, double volume) {
        List<Candle> candles = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 25; i++) {
            candles.add(new Candle(d.plusDays(i), close, close + 1, close - 1, close, volume));
        }
        return candles;
    }
}
