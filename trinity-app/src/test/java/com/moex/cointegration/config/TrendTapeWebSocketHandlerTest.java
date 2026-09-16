package com.moex.cointegration.config;

import com.moex.trinity.marketdata.DomBook;
import com.moex.trinity.marketdata.TradePrint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrendTapeWebSocketHandlerTest {

    @Test
    void matchesFrontMonthFamily() {
        assertTrue(TrendTapeWebSocketHandler.sameTapeInstrument("BRV6", "BRV6"));
        assertTrue(TrendTapeWebSocketHandler.sameTapeInstrument("BR", "BRV6"));
        assertTrue(TrendTapeWebSocketHandler.sameTapeInstrument("BRV6", "BRU6"));
        assertTrue(TrendTapeWebSocketHandler.sameTapeInstrument("SiZ6", "SiH7"));
        assertFalse(TrendTapeWebSocketHandler.sameTapeInstrument("BRV6", "SiZ6"));
        assertFalse(TrendTapeWebSocketHandler.sameTapeInstrument("", "BRV6"));
    }

    @Test
    void subscribeListAndWildcard() {
        TrendTapeWebSocketHandler.Client c = new TrendTapeWebSocketHandler.Client(null);
        TrendTapeWebSocketHandler.applySubscribe(c, "{\"instruments\":[\"BRV6\",\"SiZ6\"]}");
        assertTrue(c.wants("BRV6"));
        assertTrue(c.wants("BRX6"));
        assertTrue(c.wants("SiH7"));
        assertFalse(c.wants("GDZ6"));
        TrendTapeWebSocketHandler.applySubscribe(c, "{\"all\":true}");
        assertTrue(c.wants("GDZ6"));
        assertTrue(c.wants("MXZ6"));
    }

    @Test
    void jsonIsTradeTick() {
        String json = TrendTapeWebSocketHandler.toJson(
                new TradePrint("BRV6", 106.635, 3, Instant.now(), TradePrint.TradeSide.SELL));
        assertTrue(json.contains("\"t\":\"trade\""));
        assertTrue(json.contains("\"instrument\":\"BRV6\""));
        assertTrue(json.contains("106.635"));
        assertFalse(json.contains("\n"));
    }

    @Test
    void bookJsonHasLevels() {
        DomBook book = new DomBook(
                "BRV6",
                10,
                List.of(new DomBook.DomLevel(106.63, 4)),
                List.of(new DomBook.DomLevel(106.64, 2)),
                Instant.now(),
                true
        );
        String json = TrendTapeWebSocketHandler.toBookJson(book);
        assertTrue(json.contains("\"t\":\"book\""));
        assertTrue(json.contains("\"p\":106.63"));
        assertTrue(json.contains("\"q\":4"));
    }
}
