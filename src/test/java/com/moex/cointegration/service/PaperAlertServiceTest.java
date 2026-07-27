package com.moex.cointegration.service;

import com.moex.cointegration.model.PaperTradeEntry;
import com.moex.cointegration.model.TradingSignal;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperAlertServiceTest {

    @Test
    void recordsAndReturnsRecentOpens() {
        PaperAlertService service = new PaperAlertService();
        String id = UUID.randomUUID().toString();
        service.recordNewOpens(List.of(new PaperTradeEntry(
                id,
                LocalDateTime.now(),
                java.time.LocalDate.now(),
                "SBER", "LKOH",
                TradingSignal.LONG_SPREAD,
                com.moex.cointegration.model.FinalTradeDecision.ENTER,
                -2.4, 0.8,
                30000, 24000, 1.0,
                "OPEN",
                null, null, null, null,
                -2.4, 0.0, 0.0,
                java.time.LocalDate.now(),
                "test",
                -2.4, false, 1.0, 0.0,
                null, null, null, null,
                "INTRADAY"
        )));

        assertEquals(1, service.recentAlerts().size());
        assertEquals(id, service.recentAlerts().get(0).id());
        assertTrue(service.recentAlerts().get(0).summary().contains("SBER"));
    }
}
