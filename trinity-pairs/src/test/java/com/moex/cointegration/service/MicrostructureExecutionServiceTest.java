package com.moex.cointegration.service;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.config.MicrostructureProperties;
import com.moex.cointegration.model.BookKind;
import com.moex.cointegration.model.Candle;
import com.moex.cointegration.model.TradingSignal;
import com.moex.cointegration.storage.MarketDataStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicrostructureExecutionServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void blocksLowRelativeVolumeOnIntraday() throws Exception {
        ImoexProperties props = ImoexProperties.forTests(
                "https://iss.moex.com/iss", "TQBR", "IMOEX", 5, 0.0005,
                ImoexProperties.CointegrationProperties.of(0.05, 2.0, 0.0, 10),
                ImoexProperties.NewsProperties.withoutRss(false, 10, 10, 1),
                tempDir.toString(), tempDir.resolve("charts").toString()
        );
        MarketDataStorage storage = new MarketDataStorage(props);
        storage.saveHourlyCandles("SBER", hourlyCandles(300, 1_000_000, 10_000));
        storage.saveHourlyCandles("VTBR", hourlyCandles(25, 500_000, 500_000));

        MicrostructureProperties ms = MicrostructureProperties.defaults();
        MicrostructureExecutionService svc = new MicrostructureExecutionService(ms, storage);

        var verdict = svc.evaluateEntry(
                "SBER", "VTBR", TradingSignal.LONG_SPREAD, BookKind.INTRADAY,
                LocalDateTime.of(2026, 7, 28, 12, 0));
        assertFalse(verdict.allowed());
    }

    @Test
    void passesLiquidAlignedLegs() throws Exception {
        ImoexProperties props = ImoexProperties.forTests(
                "https://iss.moex.com/iss", "TQBR", "IMOEX", 5, 0.0005,
                ImoexProperties.CointegrationProperties.of(0.05, 2.0, 0.0, 10),
                ImoexProperties.NewsProperties.withoutRss(false, 10, 10, 1),
                tempDir.toString(), tempDir.resolve("charts").toString()
        );
        MarketDataStorage storage = new MarketDataStorage(props);
        storage.saveHourlyCandles("SBER", hourlyCandlesWithRange(300, 1_000_000, 0.002, 0.8));
        storage.saveHourlyCandles("VTBR", hourlyCandlesWithRange(25, 900_000, 0.002, -0.7));

        MicrostructureProperties ms = new MicrostructureProperties(
                true, true, 0.60, 35.0, 0.15, 5.0, 15, 30, 20, false,
                false, 300_000.0, 25.0, 0.05, 50.0, false, false, 15.0,
                MicrostructureProperties.TrendMicrostructureProperties.defaults());
        MicrostructureExecutionService svc = new MicrostructureExecutionService(ms, storage);

        var verdict = svc.evaluateEntry(
                "SBER", "VTBR", TradingSignal.LONG_SPREAD, BookKind.INTRADAY,
                LocalDateTime.of(2026, 7, 28, 12, 0));
        assertTrue(verdict.allowed());
    }

    @Test
    void dailyBookBypassesGate() throws Exception {
        MicrostructureExecutionService svc = new MicrostructureExecutionService(
                MicrostructureProperties.defaults(), new MarketDataStorage(
                ImoexProperties.forTests(
                        "https://iss.moex.com/iss", "TQBR", "IMOEX", 5, 0.0005,
                        ImoexProperties.CointegrationProperties.of(0.05, 2.0, 0.0, 10),
                        ImoexProperties.NewsProperties.withoutRss(false, 10, 10, 1),
                        tempDir.toString(), tempDir.resolve("charts").toString()
                )));
        assertTrue(svc.evaluateEntry(
                "SBER", "VTBR", TradingSignal.LONG_SPREAD, BookKind.DAILY,
                LocalDateTime.now()).allowed());
    }

    private static List<Candle> hourlyCandles(double close, double volume, double lastVolume) {
        List<Candle> list = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 7, 1);
        for (int i = 0; i < 25; i++) {
            double v = i == 24 ? lastVolume : volume;
            list.add(new Candle(d.atTime(12, 0).plusHours(i), close, close, close, close, v));
        }
        return list;
    }

    private static List<Candle> hourlyCandlesWithRange(
            double close, double volume, double rangePct, double deltaBias
    ) {
        List<Candle> list = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 7, 1);
        for (int i = 0; i < 25; i++) {
            double half = close * rangePct / 2.0;
            double low = close - half;
            double high = close + half;
            double mid = (high + low) / 2.0;
            double c = mid + (high - mid) * Math.max(-1, Math.min(1, deltaBias));
            list.add(new Candle(d.atTime(12, 0).plusHours(i), close, high, low, c, volume));
        }
        return list;
    }
}
