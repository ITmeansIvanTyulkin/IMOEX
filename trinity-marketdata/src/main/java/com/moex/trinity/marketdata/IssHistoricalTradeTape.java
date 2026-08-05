package com.moex.trinity.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MOEX ISS historical prints for FORTS (research / day-replay VAP).
 */
public final class IssHistoricalTradeTape {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private IssHistoricalTradeTape() {
    }

    public static List<TradePrint> fetchDay(String secid, LocalDate day) throws Exception {
        return fetchDay(secid, day, Integer.MAX_VALUE);
    }

    public static List<TradePrint> fetchDay(String secid, LocalDate day, int maxPrints) throws Exception {
        String id = secid == null || secid.isBlank() ? "BRU6" : secid.trim();
        List<TradePrint> out = new ArrayList<>();
        int start = 0;
        while (out.size() < maxPrints) {
            String url = String.format(Locale.ROOT,
                    "https://iss.moex.com/iss/engines/futures/markets/forts/securities/%s/trades.json"
                            + "?date=%s&iss.meta=off&iss.only=trades&start=%d",
                    id, day.format(DAY), start);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(90))
                    .header("User-Agent", "trinity-marketdata")
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new IllegalStateException("ISS trades HTTP " + resp.statusCode() + " for " + url);
            }
            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode trades = root.path("trades");
            JsonNode data = trades.path("data");
            if (!data.isArray() || data.isEmpty()) {
                break;
            }
            Map<String, Integer> ci = colIndex(trades.path("columns"));
            int matched = 0;
            int skippedDate = 0;
            for (JsonNode row : data) {
                if (out.size() >= maxPrints) {
                    break;
                }
                String tradeDate = ci.containsKey("TRADEDATE")
                        ? row.get(ci.get("TRADEDATE")).asText("")
                        : (ci.containsKey("TRADE_SESSION_DATE")
                        ? row.get(ci.get("TRADE_SESSION_DATE")).asText("")
                        : "");
                if (!day.format(DAY).equals(tradeDate)) {
                    skippedDate++;
                    continue;
                }
                out.add(parseRow(id, day, row, ci));
                matched++;
            }
            int n = data.size();
            // Public ISS /trades often ignores ?date= and returns the live session only.
            if (matched == 0 && skippedDate > 0 && start == 0) {
                throw new IllegalStateException(
                        "ISS /trades returned prints for another day (requested " + day
                                + "). Public ISS has no historical FORTS tape — use M1 synthetic or T-Invest GetLastTrades.");
            }
            start += n;
            if (n < 5000) {
                break;
            }
            Thread.sleep(150);
        }
        out.sort(Comparator.comparing(TradePrint::time, Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    private static TradePrint parseRow(String secid, LocalDate day, JsonNode row, Map<String, Integer> ci) {
        String time = row.get(ci.get("TRADETIME")).asText("00:00:00");
        LocalTime lt = LocalTime.parse(time.length() == 5 ? time + ":00" : time);
        LocalDateTime ldt = LocalDateTime.of(day, lt);
        Instant instant = ldt.atZone(MSK).toInstant();
        double price = row.get(ci.get("PRICE")).asDouble();
        long qty = row.get(ci.get("QUANTITY")).asLong(0);
        String bs = ci.containsKey("BUYSELL") ? row.get(ci.get("BUYSELL")).asText("") : "";
        TradePrint.TradeSide side = "B".equalsIgnoreCase(bs) ? TradePrint.TradeSide.BUY
                : "S".equalsIgnoreCase(bs) ? TradePrint.TradeSide.SELL : TradePrint.TradeSide.UNKNOWN;
        return new TradePrint(secid, price, qty, instant, side);
    }

    private static Map<String, Integer> colIndex(JsonNode columns) {
        java.util.LinkedHashMap<String, Integer> m = new java.util.LinkedHashMap<>();
        if (columns != null && columns.isArray()) {
            for (int i = 0; i < columns.size(); i++) {
                m.put(columns.get(i).asText(), i);
            }
        }
        return m;
    }
}
