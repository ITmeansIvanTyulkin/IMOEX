package com.moex.cointegration.controller;

import com.moex.trinity.marketdata.DomBook;
import com.moex.trinity.marketdata.MarketDataResearchService;
import com.moex.trinity.marketdata.TradePrint;
import com.moex.trinity.trend.TapeToM5Aggregator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/marketdata")
public class MarketDataController {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final int DOM_DEPTH = 50;

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
                    "bids", List.of(),
                    "asks", List.of(),
                    "tapeByPrice", Map.of(),
                    "summary", "Marketdata выключен"
            ));
        }
        String id = instrument == null || instrument.isBlank() ? svc.defaultInstrument() : instrument.trim();
        // Never block UI on unary GetOrderBook — stream/archive now, REST async.
        Optional<DomBook> book = svc.resolveBookForHttp(id);
        if (book.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("instrumentId", id);
            empty.put("bids", List.of());
            empty.put("asks", List.of());
            empty.put("tapeByPrice", tapeByPrice(svc, id));
            empty.put("summary", "Нет DOM (стрим/архив пуст)");
            return ResponseEntity.ok(empty);
        }
        DomBook b = book.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instrumentId", b.instrumentId());
        m.put("depth", b.depth());
        m.put("asOf", b.asOf() == null ? null : b.asOf().toString());
        m.put("consistent", b.consistent());
        List<DomBook.DomLevel> bids = b.bids() == null ? List.of() : b.bids();
        List<DomBook.DomLevel> asks = b.asks() == null ? List.of() : b.asks();
        m.put("bids", bids.stream().limit(DOM_DEPTH).map(l -> Map.of("p", l.price(), "q", l.quantityLots())).toList());
        m.put("asks", asks.stream().limit(DOM_DEPTH).map(l -> Map.of("p", l.price(), "q", l.quantityLots())).toList());
        m.put("tapeByPrice", tapeByPrice(svc, id));
        return ResponseEntity.ok(m);
    }

    private static Map<String, Map<String, Long>> tapeByPrice(MarketDataResearchService svc, String instrument) {
        try {
            List<TradePrint> prints = List.of();
            if (svc.feed() != null) {
                var live = svc.feed().recentTrades(instrument);
                if (live != null && !live.isEmpty()) {
                    prints = live;
                }
            }
            if (prints.isEmpty()) {
                TapeToM5Aggregator agg = new TapeToM5Aggregator(svc.archive());
                prints = agg.loadRecentPrints(instrument, LocalDate.now(MSK));
            }
            Instant cutoff = Instant.now().minusSeconds(90 * 60L);
            List<TradePrint> recent = new ArrayList<>();
            for (TradePrint p : prints) {
                if (p != null && p.time() != null && !p.time().isBefore(cutoff)) {
                    recent.add(p);
                }
            }
            if (recent.isEmpty()) {
                recent = prints;
            }
            return com.moex.cointegration.service.TrendDeskService.aggregateTapeByPrice(recent, 0.01);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    /** Last TQBR print for equity pairs (ISS has no operator websocket). */
    @GetMapping("/iss-last")
    public ResponseEntity<?> issLast(@RequestParam("secid") String secid) {
        String id = secid == null ? "" : secid.trim();
        if (id.isEmpty() || !id.matches("[A-Za-z0-9._-]{1,20}")) {
            return ResponseEntity.badRequest().body(Map.of("error", "bad secid"));
        }
        String url = "https://iss.moex.com/iss/engines/stock/markets/shares/boards/TQBR/securities/"
                + id + ".json?iss.meta=off&iss.only=marketdata"
                + "&marketdata.columns=SECID,LAST,LASTCHANGE,LASTTOPREVPRICE,UPDATETIME";
        try {
            java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofMillis(1200))
                    .build();
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofMillis(1800))
                    .GET()
                    .build();
            var res = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                return ResponseEntity.ok(Map.of("secid", id, "px", 0, "ok", false));
            }
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(res.body());
            var data = root.path("marketdata").path("data");
            if (!data.isArray() || data.isEmpty()) {
                return ResponseEntity.ok(Map.of("secid", id, "px", 0, "ok", false));
            }
            var row = data.get(0);
            double px = row.path(1).asDouble(0);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("secid", id);
            out.put("px", px);
            out.put("change", row.path(2).asDouble(0));
            out.put("ok", px > 0);
            return ResponseEntity.ok(out);
        } catch (Exception ex) {
            return ResponseEntity.ok(Map.of("secid", id, "px", 0, "ok", false));
        }
    }
}
