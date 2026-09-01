package com.moex.trinity.marketdata;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

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
    void pickLiveMonthSkipsEmptyFrontWhenNextHasDom() {
        var u = new TInvestBrokerMarketData.FrontMonth("BRU6", "figi-u", LocalDate.of(2026, 9, 1));
        var v = new TInvestBrokerMarketData.FrontMonth("BRV6", "figi-v", LocalDate.of(2026, 10, 15));
        var picked = TInvestBrokerMarketData.pickLiveMonth(List.of(u, v), t -> "BRV6".equals(t));
        assertTrue(picked.isPresent());
        assertTrue(picked.get().ticker().equals("BRV6"));
    }

    @Test
    void pickLiveMonthKeepsFrontWhenItHasDom() {
        var u = new TInvestBrokerMarketData.FrontMonth("BRU6", "figi-u", LocalDate.of(2026, 9, 1));
        var v = new TInvestBrokerMarketData.FrontMonth("BRV6", "figi-v", LocalDate.of(2026, 10, 15));
        var picked = TInvestBrokerMarketData.pickLiveMonth(List.of(u, v), t -> true);
        assertTrue(picked.isPresent());
        assertTrue(picked.get().ticker().equals("BRU6"));
    }

    @Test
    void pickLiveMonthFallsBackToFirstWhenAllEmpty() {
        var u = new TInvestBrokerMarketData.FrontMonth("BRU6", "figi-u", LocalDate.of(2026, 9, 1));
        var picked = TInvestBrokerMarketData.pickLiveMonth(List.of(u), t -> false);
        assertTrue(picked.isPresent());
        assertTrue(picked.get().ticker().equals("BRU6"));
    }
}
