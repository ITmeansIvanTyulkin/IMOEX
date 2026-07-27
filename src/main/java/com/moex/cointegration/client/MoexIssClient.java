package com.moex.cointegration.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.Candle;
import com.moex.cointegration.model.OrderBookSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * HTTP-клиент MOEX ISS API: состав индекса и исторические дневные свечи.
 */
@Component
public class MoexIssClient {

    private static final Logger log = LoggerFactory.getLogger(MoexIssClient.class);
    private static final DateTimeFormatter MOEX_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MOEX_DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int PAGE_SIZE = 500;

    private final RestTemplate restTemplate;
    private final ImoexProperties properties;

    /**
     * @param restTemplate HTTP-клиент Spring
     * @param properties   настройки MOEX из конфигурации
     */
    public MoexIssClient(RestTemplate restTemplate, ImoexProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    /**
     * Загружает актуальный список тикеров, входящих в индекс IMOEX.
     *
     * @return уникальные тикеры в порядке ответа MOEX
     */
    public List<String> fetchImoexTickers() {
        String url = UriComponentsBuilder
                .fromHttpUrl(properties.baseUrl())
                .path("/statistics/engines/stock/markets/index/analytics/{index}/tickers.json")
                .queryParam("iss.meta", "off")
                .buildAndExpand(properties.index())
                .toUriString();

        try {
            JsonNode root = restTemplate.getForObject(url, JsonNode.class);
            return parseTickers(root.path("tickers"));
        } catch (Exception ex) {
            log.warn("Primary IMOEX tickers endpoint failed, trying fallback: {}", ex.getMessage());
            return fetchImoexTickersFallback();
        }
    }

    /**
     * Резервный endpoint состава индекса, если основной URL недоступен.
     */
    private List<String> fetchImoexTickersFallback() {
        String url = properties.baseUrl()
                + "/statistics/engines/stock/markets/index/analytics/"
                + properties.index()
                + ".json?iss.meta=off";

        JsonNode root = restTemplate.getForObject(url, JsonNode.class);
        for (String field : List.of("tickers", "analytics")) {
            if (root.has(field)) {
                return parseTickers(root.path(field));
            }
        }
        throw new IllegalStateException("Unable to parse IMOEX tickers from MOEX fallback response");
    }

    /**
     * Парсит табличный блок ISS-ответа с колонкой {@code ticker} или {@code secid}.
     */
    private List<String> parseTickers(JsonNode tableNode) {
        Set<String> tickers = new LinkedHashSet<>();
        JsonNode columns = tableNode.path("columns");
        JsonNode data = tableNode.path("data");

        int tickerIdx = -1;
        for (int i = 0; i < columns.size(); i++) {
            if ("ticker".equalsIgnoreCase(columns.get(i).asText())
                    || "secid".equalsIgnoreCase(columns.get(i).asText())) {
                tickerIdx = i;
                break;
            }
        }
        if (tickerIdx < 0) {
            throw new IllegalStateException("Ticker column not found in MOEX response");
        }

        for (JsonNode row : data) {
            String ticker = row.get(tickerIdx).asText().trim();
            if (!ticker.isBlank()) {
                tickers.add(ticker);
            }
        }
        return new ArrayList<>(tickers);
    }

    /**
     * Загружает дневные свечи (interval=24) по одному тикеру с постраничной выборкой.
     */
    public List<Candle> fetchDailyCandles(String ticker, LocalDate from, LocalDate till) {
        return fetchShareCandles(ticker, from, till, 24);
    }

    /**
     * Свечи акции TQBR с произвольным interval (24=day, 60=1H, …).
     */
    public List<Candle> fetchShareCandles(String ticker, LocalDate from, LocalDate till, int interval) {
        return fetchCandles(
                "/engines/stock/markets/shares/boards/{board}/securities/{ticker}/candles.json",
                List.of(properties.board(), ticker),
                from, till, interval
        );
    }

    /**
     * Свечи индекса (обычно board SNDX, secid IMOEX).
     */
    public List<Candle> fetchIndexCandles(
            String secid, String indexBoard, LocalDate from, LocalDate till, int interval
    ) {
        return fetchCandles(
                "/engines/stock/markets/index/boards/{board}/securities/{ticker}/candles.json",
                List.of(indexBoard, secid),
                from, till, interval
        );
    }

    private List<Candle> fetchCandles(
            String pathTemplate,
            List<String> pathArgs,
            LocalDate from,
            LocalDate till,
            int interval
    ) {
        List<Candle> candles = new ArrayList<>();
        int start = 0;

        while (true) {
            var builder = UriComponentsBuilder
                    .fromHttpUrl(properties.baseUrl())
                    .path(pathTemplate)
                    .queryParam("from", from.format(MOEX_DATE))
                    .queryParam("till", till.format(MOEX_DATE))
                    .queryParam("interval", interval)
                    .queryParam("start", start)
                    .queryParam("iss.meta", "off");
            String url = builder.buildAndExpand(pathArgs.toArray()).toUriString();

            JsonNode root = restTemplate.getForObject(url, JsonNode.class);
            if (root == null) {
                break;
            }
            JsonNode candlesNode = root.path("candles");
            JsonNode columns = candlesNode.path("columns");
            JsonNode data = candlesNode.path("data");

            if (!data.isArray() || data.isEmpty()) {
                break;
            }

            int openIdx = indexOf(columns, "open");
            int closeIdx = indexOf(columns, "close");
            int highIdx = indexOf(columns, "high");
            int lowIdx = indexOf(columns, "low");
            int volumeIdx = indexOf(columns, "volume");
            int beginIdx = indexOf(columns, "begin");

            for (JsonNode row : data) {
                LocalDateTime begin = parseBarBegin(row.get(beginIdx).asText());
                candles.add(new Candle(
                        begin,
                        row.get(openIdx).asDouble(),
                        row.get(highIdx).asDouble(),
                        row.get(lowIdx).asDouble(),
                        row.get(closeIdx).asDouble(),
                        row.get(volumeIdx).asDouble()
                ));
            }

            if (data.size() < PAGE_SIZE) {
                break;
            }
            start += PAGE_SIZE;
        }

        return candles;
    }

    /**
     * Стакан TQBR (DOM) — top levels для microstructure gate.
     */
    public OrderBookSnapshot fetchOrderBook(String ticker) {
        String url = UriComponentsBuilder
                .fromHttpUrl(properties.baseUrl())
                .path("/engines/stock/markets/shares/boards/{board}/securities/{ticker}/orderbook.json")
                .queryParam("iss.meta", "off")
                .buildAndExpand(properties.board(), ticker.toUpperCase())
                .toUriString();
        try {
            JsonNode root = restTemplate.getForObject(url, JsonNode.class);
            JsonNode table = root.path("orderbook");
            JsonNode columns = table.path("columns");
            JsonNode data = table.path("data");
            if (!columns.isArray() || !data.isArray() || data.isEmpty()) {
                return new OrderBookSnapshot(ticker, 0, 0, 0, 0, Double.POSITIVE_INFINITY);
            }
            int sideIdx = indexOf(columns, "BUYSELL");
            int priceIdx = indexOf(columns, "PRICE");
            int qtyIdx = indexOf(columns, "QUANTITY");
            double bestBid = 0;
            double bestAsk = Double.POSITIVE_INFINITY;
            double bidRub = 0;
            double askRub = 0;
            for (JsonNode row : data) {
                String side = row.get(sideIdx).asText("");
                double price = row.get(priceIdx).asDouble();
                double qty = row.get(qtyIdx).asDouble();
                double rub = price * qty;
                if ("B".equalsIgnoreCase(side)) {
                    if (price > bestBid) {
                        bestBid = price;
                    }
                    bidRub += rub;
                } else if ("S".equalsIgnoreCase(side)) {
                    if (price < bestAsk) {
                        bestAsk = price;
                    }
                    askRub += rub;
                }
            }
            if (bestAsk == Double.POSITIVE_INFINITY) {
                bestAsk = 0;
            }
            double mid = bestBid > 0 && bestAsk > 0 ? (bestBid + bestAsk) / 2.0 : Math.max(bestBid, bestAsk);
            double spreadBps = mid <= 0 ? Double.POSITIVE_INFINITY : (bestAsk - bestBid) / mid * 10_000.0;
            return new OrderBookSnapshot(ticker, bestBid, bestAsk, bidRub, askRub, spreadBps);
        } catch (Exception ex) {
            log.warn("Order book {} unavailable: {}", ticker, ex.getMessage());
            return new OrderBookSnapshot(ticker, 0, 0, 0, 0, Double.POSITIVE_INFINITY);
        }
    }

    private static LocalDateTime parseBarBegin(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Empty candle begin");
        }
        String v = raw.trim();
        if (v.length() >= 19) {
            try {
                return LocalDateTime.parse(v.substring(0, 19), MOEX_DT);
            } catch (DateTimeParseException ignored) {
                // fall through
            }
        }
        return LocalDate.parse(v.substring(0, 10), MOEX_DATE).atStartOfDay();
    }

    /** Возвращает индекс колонки в ISS-таблице по имени (без учёта регистра). */
    private int indexOf(JsonNode columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (name.equalsIgnoreCase(columns.get(i).asText())) {
                return i;
            }
        }
        throw new IllegalStateException("Column not found: " + name);
    }
}
