package com.moex.cointegration.storage;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.AnalysisReport;
import com.moex.cointegration.model.Candle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketDataStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void savesAndLoadsCandlesAndReport() throws Exception {
        ImoexProperties props = ImoexProperties.forTests(
                "https://iss.moex.com/iss", "TQBR", "IMOEX", 5, 0.0005,
                ImoexProperties.CointegrationProperties.of(0.05, 2.0, 0.0, 10),
                ImoexProperties.NewsProperties.withoutRss(false, 10, 10, 1),
                tempDir.toString(),
                tempDir.resolve("charts").toString()
        );
        MarketDataStorage storage = new MarketDataStorage(props);

        storage.saveCandles("SBER", List.of(
                new Candle(LocalDate.of(2024, 1, 2), 1, 2, 0.5, 1.5, 100)
        ));
        assertEquals(1, storage.loadCandles("SBER").size());
        assertEquals(List.of("SBER"), storage.listStoredTickers());

        AnalysisReport report = new AnalysisReport(LocalDate.now(), 1, 0, 0, List.of());
        storage.saveReport(report);
        assertTrue(storage.loadReport().isPresent());
        assertEquals(1, storage.loadReport().get().tickersAnalyzed());
    }
}
