package com.moex.cointegration.client;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.NewsItem;
import com.moex.cointegration.model.RssHeadline;
import com.moex.cointegration.universe.SectorCatalog;
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
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Опциональные RSS (Interfax, RBC и др.) → {@link NewsItem} для триггер-матчера
 * и {@link RssHeadline} для UI «Итог + новости».
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
    private static final Pattern LINK = Pattern.compile(
            "<link\\b[^>]*>(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?</link>",
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
        for (ImoexProperties.NewsProperties.RssFeed feed : news.resolvedRssFeeds()) {
            all.addAll(fetchFeed(feed.url(), feed.name(), lookbackDays));
        }
        log.info("RSS news loaded: {} items (lookback {}d)", all.size(), lookbackDays);
        return all;
    }

    /**
     * Заголовки для UI: с ссылками и опциональным тикер-hint. Не бросает наружу.
     */
    public List<RssHeadline> fetchHeadlines(int lookbackDays, int maxItems) {
        ImoexProperties.NewsProperties news = properties.news();
        if (news == null || !news.rssEnabledFlag()) {
            return List.of();
        }
        List<RssHeadline> all = new ArrayList<>();
        for (ImoexProperties.NewsProperties.RssFeed feed : news.resolvedRssFeeds()) {
            all.addAll(fetchHeadlineFeed(feed.url(), feed.name(), lookbackDays));
        }
        all.sort(Comparator.comparing(RssHeadline::publishedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        int limit = Math.max(1, maxItems);
        if (all.size() > limit) {
            return List.copyOf(all.subList(0, limit));
        }
        return List.copyOf(all);
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

    List<RssHeadline> fetchHeadlineFeed(String url, String source, int lookbackDays) {
        if (url == null || url.isBlank()) {
            return List.of();
        }
        try {
            String xml = restTemplate.getForObject(url, String.class);
            if (xml == null || xml.isBlank()) {
                return List.of();
            }
            return parseRssHeadlines(xml, source, lookbackDays);
        } catch (Exception ex) {
            log.warn("RSS UI {} failed ({}): {}", source, url, ex.getMessage());
            return List.of();
        }
    }

    List<NewsItem> parseRss(String xml, String source, int lookbackDays) {
        List<NewsItem> items = new ArrayList<>();
        for (ParsedItem p : parseItems(xml, lookbackDays)) {
            long syntheticId = source.hashCode() * 1_000_000L;
            long id = p.guid() != null
                    ? Math.abs(p.guid().hashCode()) + syntheticId
                    : syntheticId + items.size();
            items.add(new NewsItem(id, p.title(), p.published(), source));
        }
        return items;
    }

    List<RssHeadline> parseRssHeadlines(String xml, String source, int lookbackDays) {
        List<RssHeadline> items = new ArrayList<>();
        for (ParsedItem p : parseItems(xml, lookbackDays)) {
            items.add(new RssHeadline(
                    p.title(),
                    source,
                    p.published(),
                    blankToNull(p.link()),
                    tickerHint(p.title())
            ));
        }
        return items;
    }

    private List<ParsedItem> parseItems(String xml, int lookbackDays) {
        LocalDateTime cutoff = LocalDate.now().minusDays(Math.max(1, lookbackDays)).atStartOfDay();
        List<ParsedItem> items = new ArrayList<>();
        Matcher itemMatcher = ITEM.matcher(xml);
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
            String link = unwrap(first(LINK, block));
            items.add(new ParsedItem(title.trim(), published, guid, link));
        }
        return items;
    }

    static String tickerHint(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String upper = title.toUpperCase(Locale.ROOT);
        Set<String> hits = new LinkedHashSet<>();
        for (String t : SectorCatalog.knownTickers()) {
            if (t.length() < 2) {
                continue;
            }
            // слово целиком / в скобках / рядом с тикером
            Pattern p = Pattern.compile("(?:^|[^A-Z0-9])" + Pattern.quote(t) + "(?:[^A-Z0-9]|$)");
            if (p.matcher(upper).find()) {
                hits.add(t);
            }
            if (hits.size() >= 3) {
                break;
            }
        }
        // частые русские имена → тикер
        addAlias(hits, upper, "СБЕР", "SBER");
        addAlias(hits, upper, "ГАЗПРОМ", "GAZP");
        addAlias(hits, upper, "ЛУКОЙЛ", "LKOH");
        addAlias(hits, upper, "НОРНИКЕЛ", "GMKN");
        addAlias(hits, upper, "РОСНЕФТ", "ROSN");
        addAlias(hits, upper, "ЯНДЕКС", "YDEX");
        addAlias(hits, upper, "YANDEX", "YDEX");
        if (hits.isEmpty()) {
            return null;
        }
        return String.join(", ", hits);
    }

    private static void addAlias(Set<String> hits, String upper, String needle, String ticker) {
        if (hits.size() >= 3) {
            return;
        }
        if (upper.contains(needle) && SectorCatalog.knownTickers().contains(ticker)) {
            hits.add(ticker);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
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

    private record ParsedItem(String title, LocalDateTime published, String guid, String link) {
    }
}
