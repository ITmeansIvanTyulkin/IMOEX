package com.moex.cointegration.service;

import com.moex.cointegration.client.RssNewsClient;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.RssHeadline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Кэш RSS для страницы «Итог + новости». Короткий HTTP-таймаут — страница не ждёт вечно.
 */
@Service
public class RssHeadlineService {

    private static final Logger log = LoggerFactory.getLogger(RssHeadlineService.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(15);

    private final ImoexProperties properties;
    private final RestTemplate shortTimeoutTemplate;
    private final AtomicReference<Snapshot> cache = new AtomicReference<>();

    public RssHeadlineService(
            ImoexProperties properties,
            RestTemplateBuilder restTemplateBuilder
    ) {
        this.properties = properties;
        this.shortTimeoutTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(5))
                .build();
    }

    public Snapshot current() {
        ImoexProperties.NewsProperties news = properties.news();
        if (news == null || !news.rssEnabledFlag()) {
            return Snapshot.disabled();
        }
        Snapshot cached = cache.get();
        if (cached != null && cached.fresh(CACHE_TTL)) {
            return cached;
        }
        try {
            Snapshot fresh = fetchNow(news);
            cache.set(fresh);
            return fresh;
        } catch (Exception ex) {
            log.warn("RSS headline refresh failed: {}", ex.getMessage());
            if (cached != null) {
                return cached.withStatus("Кэш: лента временно недоступна (" + ex.getMessage() + ")");
            }
            return Snapshot.failed(ex.getMessage());
        }
    }

    private Snapshot fetchNow(ImoexProperties.NewsProperties news) {
        // короткий RestTemplate только для UI-fetch; парсер — тот же RssNewsClient
        RssNewsClient uiClient = new RssNewsClient(shortTimeoutTemplate, properties);
        List<RssHeadline> items = uiClient.fetchHeadlines(
                Math.max(1, news.lookbackDays()),
                news.rssMaxItemsOrDefault()
        );
        String status;
        if (items.isEmpty()) {
            status = "Лента пуста или источники не ответили за 5 с — контекст FA без свежих заголовков.";
        } else {
            status = "Контекст для FA-слоя (не торговый сигнал). Источники: "
                    + news.resolvedRssFeeds().stream()
                    .map(ImoexProperties.NewsProperties.RssFeed::name)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("RSS");
        }
        return new Snapshot(true, !items.isEmpty(), status, Instant.now(), items);
    }

    /**
     * Снимок ленты для HTML.
     *
     * @param enabled  rss-enabled
     * @param ok       есть хотя бы один заголовок
     * @param status   человекочитаемый статус
     * @param fetchedAt время получения (null если disabled)
     * @param items    карточки
     */
    public record Snapshot(
            boolean enabled,
            boolean ok,
            String status,
            Instant fetchedAt,
            List<RssHeadline> items
    ) {
        public Snapshot {
            if (items == null) {
                items = List.of();
            }
            if (status == null) {
                status = "";
            }
        }

        public static Snapshot disabled() {
            return new Snapshot(false, false,
                    "RSS выключен (imoex.news.rss-enabled=false). Блок можно включить в конфиге.",
                    null, List.of());
        }

        public static Snapshot failed(String reason) {
            String msg = reason == null || reason.isBlank() ? "неизвестная ошибка" : reason;
            return new Snapshot(true, false,
                    "Не удалось загрузить RSS: " + msg + ". Страница работает без новостного контекста.",
                    Instant.now(), List.of());
        }

        boolean fresh(Duration ttl) {
            return fetchedAt != null && fetchedAt.plus(ttl).isAfter(Instant.now());
        }

        Snapshot withStatus(String newStatus) {
            return new Snapshot(enabled, ok, newStatus, fetchedAt, items);
        }
    }
}
