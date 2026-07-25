package com.moex.cointegration.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.NewsItem;
import com.moex.cointegration.model.SecurityTradingStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Клиент новостей и статуса бумаг MOEX ISS (для дневного safety-layer, не intraday).
 */
@Component
public class MoexNewsClient {

    private static final Logger log = LoggerFactory.getLogger(MoexNewsClient.class);
    private static final DateTimeFormatter MOEX_DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int PAGE_SIZE = 50;

    private final RestTemplate restTemplate;
    private final ImoexProperties properties;

    public MoexNewsClient(RestTemplate restTemplate, ImoexProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    /**
     * Загружает новости сайта MOEX за последние {@code lookbackDays} дней.
     */
    public List<NewsItem> fetchSiteNews(int lookbackDays, int maxPages) {
        LocalDateTime cutoff = LocalDate.now().minusDays(lookbackDays).atStartOfDay();
        List<NewsItem> items = new ArrayList<>();
        int start = 0;

        for (int page = 0; page < Math.max(1, maxPages); page++) {
            String url = UriComponentsBuilder
                    .fromHttpUrl(properties.baseUrl())
                    .path("/sitenews.json")
                    .queryParam("iss.meta", "off")
                    .queryParam("start", start)
                    .toUriString();

            try {
                JsonNode root = restTemplate.getForObject(url, JsonNode.class);
                if (root == null) {
                    break;
                }
                JsonNode table = root.path("sitenews");
                JsonNode columns = table.path("columns");
                JsonNode data = table.path("data");
                if (!data.isArray() || data.isEmpty()) {
                    break;
                }

                int idIdx = indexOf(columns, "id");
                int titleIdx = indexOf(columns, "title");
                int publishedIdx = indexOf(columns, "published_at");

                boolean reachedCutoff = false;
                for (JsonNode row : data) {
                    LocalDateTime published = parseDateTime(row.get(publishedIdx).asText());
                    if (published.isBefore(cutoff)) {
                        reachedCutoff = true;
                        break;
                    }
                    items.add(new NewsItem(
                            row.get(idIdx).asLong(),
                            row.get(titleIdx).asText(),
                            published,
                            "MOEX_SITENEWS"
                    ));
                }

                if (reachedCutoff || data.size() < PAGE_SIZE) {
                    break;
                }
                start += PAGE_SIZE;
            } catch (Exception ex) {
                log.warn("Failed to fetch MOEX sitenews page start={}: {}", start, ex.getMessage());
                break;
            }
        }

        log.info("Loaded {} MOEX sitenews items for last {} days", items.size(), lookbackDays);
        return items;
    }

    /** Статус бумаги на выбранном board (TQBR). */
    public SecurityTradingStatus fetchTradingStatus(String ticker) {
        String url = UriComponentsBuilder
                .fromHttpUrl(properties.baseUrl())
                .path("/engines/stock/markets/shares/boards/{board}/securities/{ticker}.json")
                .queryParam("iss.meta", "off")
                .queryParam("iss.only", "securities,marketdata")
                .buildAndExpand(properties.board(), ticker)
                .toUriString();

        try {
            JsonNode root = restTemplate.getForObject(url, JsonNode.class);
            if (root == null) {
                return SecurityTradingStatus.missing(ticker);
            }

            JsonNode secTable = root.path("securities");
            JsonNode secData = secTable.path("data");
            if (!secData.isArray() || secData.isEmpty()) {
                return SecurityTradingStatus.missing(ticker);
            }

            JsonNode secCols = secTable.path("columns");
            JsonNode secRow = secData.get(0);
            String status = textAt(secRow, indexOfOptional(secCols, "STATUS"));
            String shortName = textAt(secRow, indexOfOptional(secCols, "SHORTNAME"));
            String secName = textAt(secRow, indexOfOptional(secCols, "SECNAME"));

            String tradingStatus = null;
            JsonNode mdTable = root.path("marketdata");
            JsonNode mdData = mdTable.path("data");
            if (mdData.isArray() && !mdData.isEmpty()) {
                JsonNode mdCols = mdTable.path("columns");
                tradingStatus = textAt(mdData.get(0), indexOfOptional(mdCols, "TRADINGSTATUS"));
            }

            return new SecurityTradingStatus(ticker, true, status, tradingStatus, shortName, secName);
        } catch (Exception ex) {
            log.warn("Failed to fetch trading status for {}: {}", ticker, ex.getMessage());
            return SecurityTradingStatus.missing(ticker);
        }
    }

    private LocalDateTime parseDateTime(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() >= 19) {
            return LocalDateTime.parse(value.substring(0, 19), MOEX_DT);
        }
        if (value.length() >= 10) {
            return LocalDate.parse(value.substring(0, 10)).atStartOfDay();
        }
        return LocalDateTime.MIN;
    }

    private String textAt(JsonNode row, int idx) {
        if (idx < 0 || row == null || idx >= row.size() || row.get(idx).isNull()) {
            return null;
        }
        return row.get(idx).asText();
    }

    private int indexOf(JsonNode columns, String name) {
        int idx = indexOfOptional(columns, name);
        if (idx < 0) {
            throw new IllegalStateException("Column not found: " + name);
        }
        return idx;
    }

    private int indexOfOptional(JsonNode columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (name.equalsIgnoreCase(columns.get(i).asText())) {
                return i;
            }
        }
        return -1;
    }
}
