package com.moex.trinity.marketdata;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketDataResearchServiceTest {

    @Test
    void noopFeedReportsIdleContour() {
        MarketDataResearchService svc = new MarketDataResearchService(new NoopMarketDataFeed());
        assertEquals(MarketDataProviderId.NOOP, svc.feed().providerId());
        assertFalse(svc.liveReady());
        assertTrue(svc.statusMessage().contains("NOOP"));
        MarketDataResearchService.Status status = svc.status();
        assertFalse(status.streaming());
        assertEquals("NOOP", status.provider());
        assertTrue(status.summary() != null && !status.summary().isBlank());
    }

    @Test
    void tInvestFeedIdleWithoutStart() {
        MarketDataResearchService svc = new MarketDataResearchService(TInvestMarketDataFeed.unconfigured());
        assertEquals(MarketDataProviderId.T_INVEST, svc.feed().providerId());
        assertFalse(svc.liveReady());
        assertTrue(svc.statusMessage().contains("idle") || svc.statusMessage().contains("T-Invest"));
    }

    @Test
    void emptyConcreteBookDoesNotStealNextMonthDom() {
        Instant asOf = Instant.now();
        DomBook empty = new DomBook("BRU6", 50, List.of(), List.of(), asOf, true);
        DomBook live = new DomBook("BRV6", 50,
                List.of(new DomBook.DomLevel(91.36, 10)),
                List.of(new DomBook.DomLevel(91.38, 4)),
                asOf, true);
        MemoryFeed feed = new MemoryFeed(List.of(empty, live));
        MarketDataResearchService svc = new MarketDataResearchService(feed);
        Optional<DomBook> book = svc.resolveBookLocal("BRU6");
        // Concrete SECID must not silently show the next month's book (false-front bug 2026-09-10).
        assertTrue(book.isEmpty() || "BRU6".equalsIgnoreCase(book.get().instrumentId()));
        assertTrue(book.isEmpty() || book.get().bids().isEmpty());
    }

    @Test
    void concreteSecidIsRecognizedForDomPin() {
        assertTrue(MarketDataResearchService.looksLikeConcreteSecid("BRV6"));
        assertTrue(MarketDataResearchService.looksLikeConcreteSecid("SiZ6"));
        assertFalse(MarketDataResearchService.looksLikeConcreteSecid("BR"));
        assertFalse(MarketDataResearchService.looksLikeConcreteSecid(""));
    }

    @Test
    void resolveBookLocalDoesNotNeedRestWhenFamilyBookExists() {
        Instant asOf = Instant.now();
        DomBook live = new DomBook("SIU6", 1,
                List.of(new DomBook.DomLevel(80.10, 2)),
                List.of(new DomBook.DomLevel(80.12, 2)),
                asOf, true);
        MemoryFeed feed = new MemoryFeed(List.of(live));
        MarketDataResearchService svc = new MarketDataResearchService(feed);
        Optional<DomBook> book = svc.resolveBookLocal("SIU6");
        assertTrue(book.isPresent());
        assertEquals("SIU6", book.get().instrumentId());
    }

    private static final class MemoryFeed implements MarketDataFeed {
        private final List<DomBook> books;

        MemoryFeed(List<DomBook> books) {
            this.books = new ArrayList<>(books);
        }

        @Override
        public MarketDataProviderId providerId() {
            return MarketDataProviderId.T_INVEST;
        }

        @Override
        public String statusMessage() {
            return "memory";
        }

        @Override
        public boolean streaming() {
            return true;
        }

        @Override
        public Optional<DomBook> latestBook(String instrumentId) {
            if (instrumentId == null) {
                return Optional.empty();
            }
            return books.stream()
                    .filter(b -> b.instrumentId() != null && b.instrumentId().equalsIgnoreCase(instrumentId))
                    .findFirst();
        }

        @Override
        public List<DomBook> snapshotBooks() {
            return List.copyOf(books);
        }
    }
}
