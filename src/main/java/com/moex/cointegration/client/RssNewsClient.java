package com.moex.cointegration.client;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.NewsItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Опциональные RSS (Interfax, RBC) → те же {@link NewsItem} для триггер-матчера.
 * Ошибки сети не валят анализ: пустой список + warn в лог.
 */
@Component
public class RssNewsClient {

    private static final Logger log = LoggerFactory.getLogger(RssNewsClient.class);
    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");

    private static final Pattern ITEM = Pattern.compile(
            "<item\\b[^>]*>(.*?)</item>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile(
            "<title\\b[^>]*>(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?</title>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern PUB_DATE = Pattern.compile(
            "<pubDate\\b[^>]*>(.*?)</pubDate>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern GUID = Pattern.compile(
            "<guid\\b[^>]*>(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?</guid>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final RestTemplate restTemplate;
    private final ImoexProperties properties;

    public RssNewsClient(RestTemplate restTemplate, ImoexProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    public List<NewsItem> fetchConfiguredFeeds(int lookbackDays) {
        ImoexProperties.NewsProperties news = properties.news();
        if (news == null || !news.rssEnabledFlag()) {
            return List.of();
        }
        List<NewsItem> all = new ArrayList<>();
        all.addAll(fetchFeed(news.rssInterfaxUrl(), "Interfax", lookbackDays));
        all.addAll(fetchFeed(news.rssRbcUrl(), "RBC", lookbackDays));
        log.info("RSS news loaded: {} items (lookback {}d)", all.size(), lookbackDays);
        return all;
    }

    List<NewsItem> fetchFeed(String url, String source, int lookbackDays) {
        if (url == null || url.isBlank()) {
            return List.of();
        }
        try {
            String xml = restTemplate.getForObject(url, String.class);
            if (xml == null || xml.isBlank()) {
                return List.of();
            }
            return parseRss(xml, source, lookbackDays);
        } catch (Exception ex) {
            log.warn("RSS {} failed ({}): {}", source, url, ex.getMessage());
            return List.of();
        }
    }

    List<NewsItem> parseRss(String xml, String source, int lookbackDays) {
        LocalDateTime cutoff = LocalDate.now().minusDays(Math.max(1, lookbackDays)).atStartOfDay();
        List<NewsItem> items = new ArrayList<>();
        Matcher itemMatcher = ITEM.matcher(xml);
        long syntheticId = source.hashCode() * 1_000_000L;
        while (itemMatcher.find()) {
            String block = itemMatcher.group(1);
            String title = unwrap(first(TITLE, block));
            if (title == null || title.isBlank()) {
                continue;
            }
            LocalDateTime published = parsePubDate(first(PUB_DATE, block));
            if (published == null) {
                published = LocalDateTime.now();
            }
            if (published.isBefore(cutoff)) {
                continue;
            }
            String guid = unwrap(first(GUID, block));
            long id = guid != null ? Math.abs(guid.hashCode()) + syntheticId : syntheticId + items.size();
            items.add(new NewsItem(id, title.trim(), published, source));
        }
        return items;
    }

    private static String first(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String unwrap(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").trim();
    }

    private static LocalDateTime parsePubDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = raw.trim();
        try {
            return OffsetDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .atZoneSameInstant(MSK)
                    .toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return OffsetDateTime.parse(t).atZoneSameInstant(MSK).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(t, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException ex) {
            log.debug("Unparseable pubDate '{}'", t);
            return null;
        }
    }
}
