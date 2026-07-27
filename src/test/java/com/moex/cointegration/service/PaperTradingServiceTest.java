package com.moex.cointegration.service;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.FinalTradeDecision;
import com.moex.cointegration.model.FinalTradeRecommendation;
import com.moex.cointegration.model.NewsRiskLevel;
import com.moex.cointegration.model.PairNewsAssessment;
import com.moex.cointegration.model.PaperTradeEntry;
import com.moex.cointegration.model.TradingRecommendation;
import com.moex.cointegration.model.TradingSignal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaperTradingServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void opensPaperTradeOnEnterSignal() throws Exception {
        PaperTradingService service = newService(mockLookup());

        List<PaperTradeEntry> opened = service.syncFromFinals(List.of(finalRec(
                TradingSignal.LONG_SPREAD, FinalTradeDecision.ENTER, -2.4, LocalDate.of(2026, 7, 20)
        )));
        assertEquals(1, opened.size());
        assertEquals("OPEN", opened.get(0).status());
        assertFalse(service.getJournal().isEmpty());
    }

    @Test
    void autoClosesOnMeanReversionWithPseudoPnl() throws Exception {
        PaperTradingService service = newService(mockLookup());
        LocalDate entryDay = LocalDate.of(2026, 7, 10);
        service.syncFromFinals(List.of(finalRec(
                TradingSignal.LONG_SPREAD, FinalTradeDecision.ENTER, -2.5, entryDay
        )));
        assertEquals(1, service.getOpenTrades().size());

        // Z вернулся к 0 на НОВОЙ свече → закрытие + прибыль (long: −2.5 → 0)
        TradingRecommendation flat = tech(TradingSignal.HOLD, 0.05, LocalDate.of(2026, 7, 18));
        FinalTradeRecommendation stillListed = new FinalTradeRecommendation(
                flat,
                new PairNewsAssessment(NewsRiskLevel.LOW, false, "ok", List.of(), 10),
                FinalTradeDecision.WATCH,
                "skip",
                "guide",
                ""
        );
        service.sync(List.of(stillListed), List.of(flat));

        assertTrue(service.getOpenTrades().isEmpty());
        PaperTradeEntry closed = service.getJournal().get(0);
        assertEquals("CLOSED", closed.status());
        assertTrue(closed.pnlPct() > 0);
        assertTrue(closed.pnlRub() > 0);
        assertTrue(closed.notes().contains("AUTO CLOSE"));
        assertEquals("mean-reversion", closed.closeComment());
    }

    @Test
    void doesNotStopOnSameCandleReanalysis() throws Exception {
        PaperTradingService service = newService(mockLookup());
        LocalDate sameDay = LocalDate.of(2026, 7, 24);
        service.syncFromFinals(List.of(finalRec(
                TradingSignal.SHORT_SPREAD, FinalTradeDecision.ENTER, 2.8, sameDay
        )));

        // Тот же asOfDate, Z улетел за stop — не закрываем (шум пересчёта)
        TradingRecommendation worse = tech(TradingSignal.SHORT_SPREAD, 4.0, sameDay);
        FinalTradeRecommendation listed = new FinalTradeRecommendation(
                worse,
                new PairNewsAssessment(NewsRiskLevel.LOW, false, "ok", List.of(), 10),
                FinalTradeDecision.WATCH,
                "w",
                "g",
                ""
        );
        service.sync(List.of(listed), List.of(worse));

        assertEquals(1, service.getOpenTrades().size());
        assertEquals("OPEN", service.getOpenTrades().get(0).status());
    }

    @Test
    void skipsOpenWhenTooCloseToStop() throws Exception {
        PaperTradingService service = newService(mockLookup());
        List<PaperTradeEntry> opened = service.syncFromFinals(List.of(finalRec(
                TradingSignal.SHORT_SPREAD, FinalTradeDecision.ENTER, 3.3, LocalDate.of(2026, 7, 24)
        )));
        assertTrue(opened.isEmpty());
        assertTrue(service.getOpenTrades().isEmpty());
    }

    @Test
    void marksUnrealizedWhileHolding() throws Exception {
        PaperTradingService service = newService(mockLookup());
        service.syncFromFinals(List.of(finalRec(
                TradingSignal.SHORT_SPREAD, FinalTradeDecision.ENTER, 2.6, LocalDate.of(2026, 7, 10)
        )));

        TradingRecommendation stillShort = tech(TradingSignal.SHORT_SPREAD, 2.1, LocalDate.of(2026, 7, 12));
        FinalTradeRecommendation hold = new FinalTradeRecommendation(
                stillShort,
                new PairNewsAssessment(NewsRiskLevel.LOW, false, "ok", List.of(), 10),
                FinalTradeDecision.WATCH,
                "skip",
                "guide",
                ""
        );
        service.sync(List.of(hold), List.of(stillShort));

        PaperTradeEntry open = service.getOpenTrades().get(0);
        assertEquals("OPEN", open.status());
        assertEquals(2.1, open.markZ(), 1e-9);
        assertTrue(open.unrealizedPnlPct() > 0); // short: 2.6 → 2.1 = profit
        assertTrue(service.summary().unrealizedPnlRub() > 0);
    }

    @Test
    void closesEvenWhenPortfolioAtCapacity() throws Exception {
        ImoexProperties props = propsWithMaxOpen(1);
        PaperTradingService service = new PaperTradingService(props, new RiskPolicyService(props), mockLookup());

        service.syncFromFinals(List.of(finalRec(
                "SBER", "LKOH", TradingSignal.LONG_SPREAD, FinalTradeDecision.ENTER, -2.5,
                LocalDate.of(2026, 7, 10)
        )));
        assertEquals(1, service.getOpenTrades().size());

        TradingRecommendation flat = tech("SBER", "LKOH", TradingSignal.HOLD, 0.0, LocalDate.of(2026, 7, 15));
        // Новый ENTER по другой паре не откроется (capacity), но старая должна закрыться
        FinalTradeRecommendation otherEnter = finalRec(
                "GAZP", "ROSN", TradingSignal.SHORT_SPREAD, FinalTradeDecision.ENTER, 2.4,
                LocalDate.of(2026, 7, 15)
        );
        service.sync(
                List.of(
                        new FinalTradeRecommendation(
                                flat,
                                new PairNewsAssessment(NewsRiskLevel.LOW, false, "ok", List.of(), 10),
                                FinalTradeDecision.WATCH, "skip", "g", ""
                        ),
                        otherEnter
                ),
                List.of(flat, otherEnter.technical())
        );

        assertTrue(service.getJournal().stream().anyMatch(e ->
                "SBER".equals(e.tickerY()) && "CLOSED".equals(e.status())));
        assertEquals(1, service.getOpenTrades().size());
        assertEquals("GAZP", service.getOpenTrades().get(0).tickerY());
    }

    @Test
    void respectsPerBookOpenCapFromAllocator() throws Exception {
        PaperTradingService service = newService(mockLookup());
        var alloc = com.moex.cointegration.config.CapitalProperties.defaults().allocation();
        // 100k → dailyMax=1
        assertEquals(1, alloc.dailyMaxPairs());

        service.sync(List.of(
                finalRec("SBER", "LKOH", TradingSignal.LONG_SPREAD, FinalTradeDecision.ENTER, -2.5,
                        LocalDate.of(2026, 7, 10)),
                finalRec("GAZP", "ROSN", TradingSignal.SHORT_SPREAD, FinalTradeDecision.ENTER, 2.4,
                        LocalDate.of(2026, 7, 10))
        ), List.of(), com.moex.cointegration.model.BookKind.DAILY,
                alloc.dailyMaxPairs(), alloc.dailyGrossCap());

        assertEquals(1, service.getOpenTrades(com.moex.cointegration.model.BookKind.DAILY).size());
    }

    private PaperTradingService newService(PairLookupService lookup) {
        ImoexProperties props = propsWithMaxOpen(5);
        return new PaperTradingService(props, new RiskPolicyService(props), lookup);
    }

    private ImoexProperties propsWithMaxOpen(int maxOpen) {
        return new ImoexProperties(
                "https://iss.moex.com/iss", "TQBR", "IMOEX", 5, 0.0005,
                ImoexProperties.CointegrationProperties.of(0.05, 2.0, 0.0, 10),
                new ImoexProperties.NewsProperties(false, 10, 10, 1),
                tempDir.toString(),
                tempDir.resolve("charts").toString(),
                new ImoexProperties.RiskProperties(3.5, 40, 0.5, maxOpen, 1.0, 0.0, 90.0, 1.0, 0.08,
                        true, 0.02, 0.25, 1.5, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null),
                ImoexProperties.WalkForwardProperties.defaults(),
                new ImoexProperties.PaperProperties(true, 50_000, "paper.json", false, null, false, null, 0.0, false, 0.30, 20.0, 40.0, false),
                ImoexProperties.UniverseProperties.defaults(),
                ImoexProperties.PortfolioProperties.defaults(),
                ImoexProperties.AuthProperties.defaults()
        );
    }

    private static PairLookupService mockLookup() throws Exception {
        PairLookupService lookup = mock(PairLookupService.class);
        when(lookup.requirePair(anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("no pair"));
        return lookup;
    }

    private static FinalTradeRecommendation finalRec(
            TradingSignal signal, FinalTradeDecision decision, double z, LocalDate asOf
    ) {
        return finalRec("SBER", "LKOH", signal, decision, z, asOf);
    }

    private static FinalTradeRecommendation finalRec(
            String y, String x, TradingSignal signal, FinalTradeDecision decision, double z, LocalDate asOf
    ) {
        return new FinalTradeRecommendation(
                tech(y, x, signal, z, asOf),
                new PairNewsAssessment(NewsRiskLevel.LOW, false, "ok", List.of(), 10),
                decision,
                decision.name().toLowerCase(),
                "guide",
                "test rationale"
        );
    }

    private static TradingRecommendation tech(TradingSignal signal, double z, LocalDate asOf) {
        return tech("SBER", "LKOH", signal, z, asOf);
    }

    private static TradingRecommendation tech(
            String y, String x, TradingSignal signal, double z, LocalDate asOf
    ) {
        return new TradingRecommendation(
                y, x, signal, z, asOf, -0.1, 0.8, 10, 1.1, 0.01, "sum", "details", null, null
        );
    }
}
