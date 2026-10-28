package com.moex.cointegration.service;

import com.moex.cointegration.config.ImoexProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class InvestChartHistoryStoreTest {

    @TempDir
    Path dir;

    @Test
    void canonicalDayCollapsesIssMidnightAndTInvest0300() {
        assertEquals("2026-10-22 00:00:00",
                InvestChartHistoryStore.canonicalBarTime("D1", "2026-10-22 00:00:00"));
        assertEquals("2026-10-22 00:00:00",
                InvestChartHistoryStore.canonicalBarTime("D1", "2026-10-22T03:00"));
        assertEquals("2026-10-22 12:00:00",
                InvestChartHistoryStore.canonicalBarTime("H1", "2026-10-22T12:00"));
        assertEquals("2026-10-22 12:00:00",
                InvestChartHistoryStore.canonicalBarTime("H1", "2026-10-22 12:00:00"));
    }

    @Test
    void saveKeepsExchangeDailyWhenTInvestTwinDiffers() {
        InvestChartHistoryStore store = new InvestChartHistoryStore(
                ImoexProperties.forTests("http://localhost", "TQBR", "IMOEX", 1, 0, null, null,
                        dir.toString(), null));
        store.init();
        try {
            store.save("NVTK", "D1", "ISS", List.of(
                    bar("2026-10-22 00:00:00", 1154.4, 1161.0, 1133.8, 1140.0, 1_566_187),
                    bar("2026-10-22T03:00", 1154.4, 1161.0, 1140.1, 1141.1, 1_223_687),
                    bar("2026-10-23 00:00:00", 1142.1, 1187.9, 1138.6, 1181.8, 4_831_680)
            ));
            InvestChartHistoryStore.SeriesSnapshot snap = store.load("NVTK", "D1", 1);
            assertEquals(2, snap.bars().size());
            Map<String, Object> d22 = snap.bars().get(0);
            assertEquals("2026-10-22 00:00:00", d22.get("time"));
            assertEquals(1133.8, (Double) d22.get("low"), 1e-6);
            assertEquals(1140.0, (Double) d22.get("close"), 1e-6);
            assertFalse(String.valueOf(snap.bars().get(1).get("time")).contains("T"));
        } finally {
            store.shutdown();
        }
    }

    private static Map<String, Object> bar(
            String time, double open, double high, double low, double close, double volume
    ) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time", time);
        m.put("open", open);
        m.put("high", high);
        m.put("low", low);
        m.put("close", close);
        m.put("volume", volume);
        return m;
    }
}
