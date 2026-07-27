package com.moex.cointegration.service;

import com.moex.cointegration.config.CapitalProperties;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.config.SessionProperties;
import com.moex.cointegration.model.BookKind;
import com.moex.cointegration.model.EventCalendarEntry;
import com.moex.cointegration.model.HistoricalReplayReport;
import com.moex.cointegration.storage.MarketDataStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoricalReplayIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void replaysSyntheticCointegratedPairThroughPaperPipeline() throws Exception {
        int n = 400;
        Random random = new Random(42);
        double[] logX = new double[n];
        double[] logY = new double[n];
        LocalDate[] dates = new LocalDate[n];
        double eps = 0.0;
        logX[0] = 4.0;
        logY[0] = 2.0 + 1.5 * logX[0];
        dates[0] = LocalDate.of(2024, 1, 2);
        for (int t = 1; t < n; t++) {
            dates[t] = dates[t - 1].plusDays(1);
            logX[t] = logX[t - 1] + random.nextGaussian() * 0.02;
            eps = 0.5 * eps + random.nextGaussian() * 0.03;
            logY[t] = 2.0 + 1.5 * logX[t] + eps;
        }

        ImoexProperties props = ImoexProperties.forTests(
                "https://iss.moex.com/iss", "TQBR", "IMOEX", 5, 0.0005,
                ImoexProperties.CointegrationProperties.of(0.05, 2.0, 0.0, 10),
                new ImoexProperties.NewsProperties(false, 10, 10, 1),
                tempDir.toString(),
                tempDir.resolve("charts").toString()
        );
        CapitalProperties capital = new CapitalProperties(100_000.0, 1_000_000.0, 1.0, 0.40, 0.60);
        SessionProperties session = SessionProperties.defaults();
        PreprocessingService preprocessing = new PreprocessingService();
        RiskPolicyService risk = new RiskPolicyService(props, capital,
                new com.moex.cointegration.config.RegimeProperties(false, 14, 20.0, 25.0, 0.5, "SNDX"), null);
        EventCalendarRiskService events = new EventCalendarRiskService(session, List.of());
        MarketDataStorage storage = new MarketDataStorage(props);

        HistoricalReplayService replay = new HistoricalReplayService(
                props, capital, session, preprocessing, risk, events,
                com.moex.cointegration.config.MicrostructureProperties.defaults(), storage);

        HistoricalReplayReport report = replay.replaySynthetic(
                "AAA", "BBB", logY, logX, dates, 120, n - 1, BookKind.DAILY);

        assertNotNull(report);
        assertTrue(report.barsProcessed() > 50);
        assertTrue(report.entries() != null);
    }
}
