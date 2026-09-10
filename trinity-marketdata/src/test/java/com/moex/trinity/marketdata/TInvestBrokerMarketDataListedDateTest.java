package com.moex.trinity.marketdata;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TInvestBrokerMarketDataListedDateTest {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");

    @Test
    void yesterdayMskLastTradeIsNotFrontEvenIfWithin24h() {
        Instant now = LocalDate.of(2026, 9, 1).atTime(10, 0).atZone(MSK).toInstant();
        Instant ltd = LocalDate.of(2026, 8, 31).atTime(21, 0).atZone(MSK).toInstant();
        assertTrue(now.toEpochMilli() - ltd.toEpochMilli() < 86_400_000L);
        assertFalse(TInvestBrokerMarketData.listedOnMskDate(ltd, now));
    }

    @Test
    void todayMskLastTradeStaysFront() {
        Instant now = LocalDate.of(2026, 9, 1).atTime(10, 0).atZone(MSK).toInstant();
        Instant ltd = LocalDate.of(2026, 9, 1).atTime(0, 0).atZone(MSK).toInstant();
        assertTrue(TInvestBrokerMarketData.listedOnMskDate(ltd, now));
    }

    @Test
    void nullsAreNotListed() {
        assertFalse(TInvestBrokerMarketData.listedOnMskDate(null, Instant.now()));
        assertFalse(TInvestBrokerMarketData.listedOnMskDate(Instant.now(), null));
    }

    @Test
    void midLifeEmptyDomKeepsCalendarFront() {
        // 2026-09-10: BRV6 still listed (LTD Oct), empty DOM must NOT jump to BRX6
        var v = new TInvestBrokerMarketData.FrontMonth("BRV6", "figi-v", LocalDate.of(2026, 10, 1));
        var x = new TInvestBrokerMarketData.FrontMonth("BRX6", "figi-x", LocalDate.of(2026, 11, 2));
        LocalDate today = LocalDate.of(2026, 9, 10);
        var picked = TInvestBrokerMarketData.pickLiveMonth(List.of(v, x), t -> "BRX6".equals(t), today);
        assertTrue(picked.isPresent());
        assertEquals("BRV6", picked.get().ticker());
    }

    @Test
    void pickLiveMonthKeepsFrontWhenItHasDom() {
        var u = new TInvestBrokerMarketData.FrontMonth("BRU6", "figi-u", LocalDate.of(2026, 9, 15));
        var v = new TInvestBrokerMarketData.FrontMonth("BRV6", "figi-v", LocalDate.of(2026, 10, 15));
        var picked = TInvestBrokerMarketData.pickLiveMonth(
                List.of(u, v), t -> true, LocalDate.of(2026, 9, 1));
        assertTrue(picked.isPresent());
        assertEquals("BRU6", picked.get().ticker());
    }

    @Test
    void pickLiveMonthFallsBackToFirstWhenAllEmpty() {
        var u = new TInvestBrokerMarketData.FrontMonth("BRU6", "figi-u", LocalDate.of(2026, 9, 15));
        var picked = TInvestBrokerMarketData.pickLiveMonth(
                List.of(u), t -> false, LocalDate.of(2026, 9, 1));
        assertTrue(picked.isPresent());
        assertEquals("BRU6", picked.get().ticker());
    }

    @Test
    void lastTradeDayRollsToNextMonth() {
        var v = new TInvestBrokerMarketData.FrontMonth("BRV6", "figi-v", LocalDate.of(2026, 10, 1));
        var x = new TInvestBrokerMarketData.FrontMonth("BRX6", "figi-x", LocalDate.of(2026, 11, 2));
        var picked = TInvestBrokerMarketData.pickLiveMonth(
                List.of(v, x), t -> false, LocalDate.of(2026, 10, 1));
        assertTrue(picked.isPresent());
        assertEquals("BRX6", picked.get().ticker());
    }

    @Test
    void resolveFrontMonthUsesPickLiveMonthOnLtdDay() {
        var v = new TInvestBrokerMarketData.FrontMonth("BRV6", "figi-v", LocalDate.of(2026, 10, 1));
        var x = new TInvestBrokerMarketData.FrontMonth("BRX6", "figi-x", LocalDate.of(2026, 11, 2));
        // pickLiveMonth is what resolveFrontMonth now delegates to
        var picked = TInvestBrokerMarketData.pickLiveMonth(
                List.of(v, x), t -> true, LocalDate.of(2026, 10, 1));
        assertTrue(picked.isPresent());
        assertEquals("BRX6", picked.get().ticker());
    }
}
