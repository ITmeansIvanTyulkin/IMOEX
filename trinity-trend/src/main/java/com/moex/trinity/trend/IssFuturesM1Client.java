package com.moex.trinity.trend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MOEX ISS 1-minute FORTS candles (warmup when broker tape is thin).
 */
public final class IssFuturesM1Client {

    private static final DateTimeFormatter MOEX_DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IssFuturesM1Client() {
    }

    public static List<TrendBar> fetchM1(String secid, LocalDate from, LocalDate till) throws Exception {
        if (secid == null || secid.isBlank() || from == null || till == null) {
            return List.of();
        }
        List<TrendBar> out = new ArrayList<>();
        int start = 0;
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        while (true) {
            String url = String.format(Locale.ROOT,
                    "https://iss.moex.com/iss/engines/futures/markets/forts/boards/RFUD/securities/%s/candles.json"
                            + "?from=%s&till=%s&interval=1&start=%d&iss.meta=off",
                    secid.trim().toUpperCase(Locale.ROOT), from, till, start);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(40))
                    .header("User-Agent", "TRINITY-IssFuturesM1/1.0")
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                break;
            }
            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode candles = root.path("candles");
            JsonNode data = candles.path("data");
            if (!data.isArray() || data.isEmpty()) {
                break;
            }
            Map<String, Integer> ci = colIndex(candles.path("columns"));
            for (JsonNode row : data) {
                LocalDateTime t = LocalDateTime.parse(row.get(ci.get("begin")).asText(), MOEX_DT);
                out.add(new TrendBar(
                        t,
                        row.get(ci.get("open")).asDouble(),
                        row.get(ci.get("high")).asDouble(),
                        row.get(ci.get("low")).asDouble(),
                        row.get(ci.get("close")).asDouble(),
                        row.get(ci.get("volume")).asDouble()
                ));
            }
            if (data.size() < 500) {
                break;
            }
            start += 500;
        }
        out.sort(Comparator.comparing(TrendBar::time));
        return out;
    }

    public static List<TrendBar> fetchM5Warmup(String secid, LocalDate from, LocalDate till) throws Exception {
        return BarAggregator.aggregateM5(fetchM1(secid, from, till));
    }

    private static Map<String, Integer> colIndex(JsonNode columns) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            m.put(columns.get(i).asText(), i);
        }
        return m;
    }
}
