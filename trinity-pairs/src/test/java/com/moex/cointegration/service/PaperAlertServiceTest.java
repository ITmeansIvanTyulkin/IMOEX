package com.moex.cointegration.service;

import com.moex.cointegration.model.PaperTradeAlert;
import com.moex.cointegration.model.PaperTradeEntry;
import com.moex.cointegration.model.TradingSignal;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperAlertServiceTest {

    @Test
    void recordsAndReturnsRecentOpensWithPotential() {
        PaperAlertService service = new PaperAlertService();
        String id = UUID.randomUUID().toString();
        PaperTradeEntry open = sampleOpen(id, -2.4, 30000);
        service.recordNewOpens(List.of(open));

        assertEquals(1, service.recentAlerts().size());
        PaperTradeAlert alert = service.recentAlerts().get(0);
        assertEquals(id, alert.id());
        assertEquals("OPEN", alert.kind());
        assertTrue(alert.summary().contains("SBER"));
        assertNotNull(alert.potentialPnlRub());
        // LONG_SPREAD: exitZ 0 - entry -2.4 = +2.4 → pct 0.024 → 30000*0.024 = 720
        assertEquals(720.0, alert.potentialPnlRub(), 1e-6);
    }

    @Test
    void recordsClosesWithDistinctIdAndPnl() {
        PaperAlertService service = new PaperAlertService();
        String id = UUID.randomUUID().toString();
        LocalDateTime closedAt = LocalDateTime.now();
        PaperTradeEntry closed = sampleOpen(id, -2.4, 30000)
                .withClose(closedAt, 0.1, 0.02, 1850.0, "TP");
        service.recordCloses(List.of(closed));

        assertEquals(1, service.recentAlerts().size());
        PaperTradeAlert alert = service.recentAlerts().get(0);
        assertEquals("CLOSE", alert.kind());
        assertTrue(alert.id().startsWith(id + ":close:"));
        assertEquals(1850.0, alert.pnlRub(), 1e-6);
        assertTrue(alert.summary().contains("выход"));
    }

    @Test
    void openAndCloseDoNotCollideInDedupeIds() {
        PaperAlertService service = new PaperAlertService();
        String id = UUID.randomUUID().toString();
        service.recordNewOpens(List.of(sampleOpen(id, -2.0, 10000)));
        service.recordCloses(List.of(
                sampleOpen(id, -2.0, 10000).withClose(LocalDateTime.now(), 0, 0.01, 100.0, "exit")
        ));
        assertEquals(2, service.recentAlerts().size());
    }

    private static PaperTradeEntry sampleOpen(String id, double entryZ, double notionalY) {
        return new PaperTradeEntry(
                id,
                LocalDateTime.now(),
                java.time.LocalDate.now(),
                "SBER", "LKOH",
                TradingSignal.LONG_SPREAD,
                com.moex.cointegration.model.FinalTradeDecision.ENTER,
                entryZ, 0.8,
                notionalY, 24000, 1.0,
                "OPEN",
                null, null, null, null,
                entryZ, 0.0, 0.0,
                java.time.LocalDate.now(),
                "test",
                entryZ, false, 1.0, 0.0,
                null, null, null, null,
                null,
                "DAILY"
        );
    }
}
