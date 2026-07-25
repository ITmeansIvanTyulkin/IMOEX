package com.moex.cointegration.service;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.Candle;
import com.moex.cointegration.model.PricePoint;
import com.moex.cointegration.model.PriceSeries;
import com.moex.cointegration.storage.MarketDataStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UniverseFilterServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsIlliquidPennyAndPreferred() throws Exception {
        ImoexProperties props = props(true, 50_000_000, 5.0, true);
        MarketDataStorage storage = new MarketDataStorage(props);
        storage.saveCandles("SBER", liquidCandles(300, 1_000_000));      // ADV ~ 300M
        storage.saveCandles("SAGO", liquidCandles(2.0, 50_000));         // thin + penny-ish
        storage.saveCandles("OGKB", liquidCandles(0.2, 50_000_000));     // price < 5
        storage.saveCandles("SBERP", liquidCandles(280, 800_000));       // preferred

        UniverseFilterService filter = new UniverseFilterService(storage, props);
        Map<String, PriceSeries> in = new LinkedHashMap<>();
        in.put("SBER", series("SBER"));
        in.put("SAGO", series("SAGO"));
        in.put("OGKB", series("OGKB"));
        in.put("SBERP", series("SBERP"));

        Map<String, PriceSeries> out = filter.filter(in);
        assertEquals(1, out.size());
        assertTrue(out.containsKey("SBER"));
        assertFalse(out.containsKey("SBERP"));
    }

    @Test
    void preferredDetection() {
        assertTrue(UniverseFilterService.isPreferredShare("SBERP"));
        assertTrue(UniverseFilterService.isPreferredShare("SNGSP"));
        assertFalse(UniverseFilterService.isPreferredShare("SBER"));
        assertFalse(UniverseFilterService.isPreferredShare("T"));
    }

    private ImoexProperties props(boolean enabled, double minAdv, double minPrice, boolean exclPref) {
        return new ImoexProperties(
                "https://iss.moex.com/iss", "TQBR", "IMOEX", 5, 0.0005,
                ImoexProperties.CointegrationProperties.of(0.05, 2.0, 0.0, 10),
                new ImoexProperties.NewsProperties(false, 10, 10, 1),
                tempDir.toString(),
                tempDir.resolve("charts").toString(),
                ImoexProperties.RiskProperties.defaults(),
                ImoexProperties.WalkForwardProperties.defaults(),
                ImoexProperties.PaperProperties.defaults(),
                new ImoexProperties.UniverseProperties(enabled, 60, minAdv, minPrice, 0.15, exclPref),
                ImoexProperties.AuthProperties.defaults()
        );
    }

    private static List<Candle> liquidCandles(double close, double volume) {
        List<Candle> list = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 60; i++) {
            list.add(new Candle(d.plusDays(i), close, close, close, close, volume));
        }
        return list;
    }

    private static PriceSeries series(String ticker) {
        return new PriceSeries(ticker, List.of(new PricePoint(LocalDate.of(2026, 3, 1), 100)));
    }
}
