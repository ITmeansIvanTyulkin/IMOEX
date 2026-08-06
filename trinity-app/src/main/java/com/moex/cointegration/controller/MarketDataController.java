package com.moex.cointegration.controller;

import com.moex.trinity.marketdata.DomBook;
import com.moex.trinity.marketdata.MarketDataResearchService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/marketdata")
public class MarketDataController {

    private final ObjectProvider<MarketDataResearchService> marketData;

    public MarketDataController(ObjectProvider<MarketDataResearchService> marketData) {
        this.marketData = marketData;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        MarketDataResearchService svc = marketData.getIfAvailable();
        if (svc == null) {
            Map<String, Object> idle = new LinkedHashMap<>();
            idle.put("provider", "NOOP");
            idle.put("streaming", false);
            idle.put("summary", "Marketdata выключен (imoex.marketdata.enabled=false)");
            return ResponseEntity.ok(idle);
        }
        return ResponseEntity.ok(svc.status());
    }

    @GetMapping("/book")
    public ResponseEntity<?> book(@RequestParam(value = "instrument", required = false) String instrument) {
        MarketDataResearchService svc = marketData.getIfAvailable();
        if (svc == null) {
            return ResponseEntity.ok(Map.of(
                    "instrumentId", instrument == null ? "" : instrument,
                    "bids", java.util.List.of(),
                    "asks", java.util.List.of(),
                    "summary", "Marketdata выключен"
            ));
        }
        String id = instrument == null || instrument.isBlank() ? svc.defaultInstrument() : instrument.trim();
        Optional<DomBook> book = svc.resolveBook(id);
        if (book.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("instrumentId", id);
            empty.put("bids", java.util.List.of());
            empty.put("asks", java.util.List.of());
            empty.put("summary", "Нет DOM (стрим/архив пуст)");
            return ResponseEntity.ok(empty);
        }
        DomBook b = book.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instrumentId", b.instrumentId());
        m.put("depth", b.depth());
        m.put("asOf", b.asOf() == null ? null : b.asOf().toString());
        m.put("consistent", b.consistent());
        m.put("bids", b.bids().stream().limit(15).map(l -> Map.of("p", l.price(), "q", l.quantityLots())).toList());
        m.put("asks", b.asks().stream().limit(15).map(l -> Map.of("p", l.price(), "q", l.quantityLots())).toList());
        return ResponseEntity.ok(m);
    }
}
