package com.moex.cointegration.service;

import com.moex.cointegration.client.MoexIssClient;
import com.moex.cointegration.client.MoexNewsClient;
import com.moex.cointegration.client.RssNewsClient;
import com.moex.cointegration.config.CapitalProperties;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.config.SessionProperties;
import com.moex.cointegration.model.BookKind;
import com.moex.cointegration.model.FinalTradeDecision;
import com.moex.cointegration.model.FinalTradeRecommendation;
import com.moex.cointegration.model.NewsItem;
import com.moex.cointegration.model.NewsRiskLevel;
import com.moex.cointegration.model.NewsTriggerHit;
import com.moex.cointegration.model.NewsTriggerType;
import com.moex.cointegration.model.PairNewsAssessment;
import com.moex.cointegration.model.SecurityTradingStatus;
import com.moex.cointegration.model.TradingRecommendation;
import com.moex.cointegration.model.TradingSignal;
import com.moex.cointegration.news.NewsTriggerMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Фундаментальный / новостной safety-layer ПОСЛЕ технического сигнала.
 * Только multi-day (DAILY). В INTRADAY пропускается — новости запаздывают.
 * Порядок пайплайна: техника → этот фильтр → итоговые рекомендации → paper.
 */
@Service
public class NewsRiskAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(NewsRiskAnalysisService.class);

    private final MoexNewsClient newsClient;
    private final MoexIssClient moexIssClient;
    private final NewsTriggerMatcher triggerMatcher;
    private final ImoexProperties properties;
    private final SessionProperties sessionProperties;
    private final RssNewsClient rssNewsClient;
    private final EventCalendarRiskService eventCalendarRiskService;
    private final RecommendationRationaleService rationaleService;

    @Autowired
    public NewsRiskAnalysisService(
            MoexNewsClient newsClient,
            MoexIssClient moexIssClient,
            NewsTriggerMatcher triggerMatcher,
            ImoexProperties properties,
            SessionProperties sessionProperties,
            RssNewsClient rssNewsClient,
            EventCalendarRiskService eventCalendarRiskService,
            RecommendationRationaleService rationaleService
    ) {
        this.newsClient = newsClient;
        this.moexIssClient = moexIssClient;
        this.triggerMatcher = triggerMatcher;
        this.properties = properties;
        this.sessionProperties = sessionProperties;
        this.rssNewsClient = rssNewsClient;
        this.eventCalendarRiskService = eventCalendarRiskService;
        this.rationaleService = rationaleService;
    }

    /** Тесты без Spring: DAILY, RSS off. */
    public NewsRiskAnalysisService(
            MoexNewsClient newsClient,
            MoexIssClient moexIssClient,
            NewsTriggerMatcher triggerMatcher,
            ImoexProperties properties
    ) {
        this(newsClient, moexIssClient, triggerMatcher, properties,
                SessionProperties.defaults(),
                new RssNewsClient(new RestTemplate(), properties),
                new EventCalendarRiskService(SessionProperties.defaults(), List.of()),
                new RecommendationRationaleService(new RiskPolicyService(properties), CapitalProperties.defaults()));
    }

    /**
     * Оценивает новости/структурный риск по списку технических рекомендаций
     * и возвращает итоговые решения ENTER / REDUCE / WATCH / BLOCK.
     * Для {@link BookKind#INTRADAY} фундамент пропускается (новости запаздывают).
     */
    public List<FinalTradeRecommendation> analyze(List<TradingRecommendation> technical) {
        return analyze(technical, BookKind.DAILY);
    }

    public List<FinalTradeRecommendation> analyze(List<TradingRecommendation> technical, BookKind book) {
        if (book == BookKind.INTRADAY) {
            log.info("Fundamental filter skipped: INTRADAY book (tech-only + event calendar)");
            return technical.stream()
                    .map(r -> intradayWithEventOverlay(r))
                    .toList();
        }

        // legacy exclusive mode — тоже без FA
        if (sessionProperties.intradayMode()) {
            log.info("Fundamental filter skipped: session mode INTRADAY (tech-only)");
            return technical.stream()
                    .map(r -> passthrough(r,
                            "Фундаментальный фильтр пропущен: режим INTRADAY (только техника)."))
                    .toList();
        }

        ImoexProperties.NewsProperties newsCfg = properties.news();
        if (newsCfg == null || !newsCfg.enabled()) {
            return technical.stream()
                    .map(r -> passthrough(r, "Новостной фильтр выключен в конфигурации."))
                    .toList();
        }

        int lookback = Math.max(1, newsCfg.lookbackDays());
        int staleDays = Math.max(1, newsCfg.staleCandleDays());
        int maxPages = Math.max(1, newsCfg.maxNewsPages());

        List<NewsItem> news = new ArrayList<>(newsClient.fetchSiteNews(lookback, maxPages));
        news.addAll(rssNewsClient.fetchConfiguredFeeds(lookback));

        Set<String> indexTickers = new HashSet<>(moexIssClient.fetchImoexTickers());

        Set<String> tickersNeeded = new HashSet<>();
        for (TradingRecommendation r : technical) {
            tickersNeeded.add(r.tickerY().toUpperCase());
            tickersNeeded.add(r.tickerX().toUpperCase());
        }

        Map<String, SecurityTradingStatus> statusByTicker = new HashMap<>();
        for (String ticker : tickersNeeded) {
            statusByTicker.put(ticker, newsClient.fetchTradingStatus(ticker));
        }

        List<FinalTradeRecommendation> result = new ArrayList<>();
        for (TradingRecommendation rec : technical) {
            PairNewsAssessment assessment = assessPair(rec, news, statusByTicker, indexTickers, lookback, staleDays);
            FinalTradeDecision decision = decide(rec.signal(), assessment.riskLevel());
            result.add(new FinalTradeRecommendation(
                    rec,
                    assessment,
                    decision,
                    decisionSummary(decision, rec, assessment),
                    beginnerGuide(decision, rec, assessment),
                    rationaleService.build(rec, decision, assessment, BookKind.DAILY)
            ));
        }

        result.sort(Comparator
                .comparingInt((FinalTradeRecommendation f) -> decisionRank(f.decision()))
                .thenComparing(f -> Math.abs(f.technical().currentZScore()), Comparator.reverseOrder()));

        long enter = result.stream().filter(f -> f.decision() == FinalTradeDecision.ENTER).count();
        long reduce = result.stream().filter(f -> f.decision() == FinalTradeDecision.REDUCE_SIZE).count();
        long block = result.stream().filter(f -> f.decision() == FinalTradeDecision.BLOCK).count();
        log.info("Fundamental filter (after tech, multi-day): {} pairs → ENTER={}, REDUCE={}, BLOCK={}, other={}",
                result.size(), enter, reduce, block, result.size() - enter - reduce - block);

        return result;
    }

    private PairNewsAssessment assessPair(
            TradingRecommendation rec,
            List<NewsItem> news,
            Map<String, SecurityTradingStatus> statusByTicker,
            Set<String> indexTickers,
            int lookback,
            int staleDays
    ) {
        List<NewsTriggerHit> hits = new ArrayList<>();
        String y = rec.tickerY().toUpperCase();
        String x = rec.tickerX().toUpperCase();

        SecurityTradingStatus statusY = statusByTicker.getOrDefault(y, SecurityTradingStatus.missing(y));
        SecurityTradingStatus statusX = statusByTicker.getOrDefault(x, SecurityTradingStatus.missing(x));

        hits.addAll(structuralHits(rec, statusY, statusX, indexTickers, staleDays));
        hits.addAll(triggerMatcher.match(y, statusY.shortName(), statusY.secName(), news));
        hits.addAll(triggerMatcher.match(x, statusX.shortName(), statusX.secName(), news));

        if (hits.isEmpty()) {
            return PairNewsAssessment.none(lookback);
        }

        NewsRiskLevel level = hits.stream()
                .map(NewsTriggerHit::severity)
                .max(Comparator.comparingInt(this::riskRank))
                .orElse(NewsRiskLevel.LOW);

        boolean asymmetric = isAsymmetric(hits, y, x);
        String summary = buildNewsSummary(level, asymmetric, hits);

        hits.sort(Comparator.comparingInt((NewsTriggerHit h) -> -riskRank(h.severity())));
        return new PairNewsAssessment(level, asymmetric, summary, List.copyOf(hits), lookback);
    }

    private List<NewsTriggerHit> structuralHits(
            TradingRecommendation rec,
            SecurityTradingStatus statusY,
            SecurityTradingStatus statusX,
            Set<String> indexTickers,
            int staleDays
    ) {
        List<NewsTriggerHit> hits = new ArrayList<>();
        LocalDate now = LocalDate.now();

        if (rec.asOfDate() != null && rec.asOfDate().isBefore(now.minusDays(staleDays))) {
            hits.add(new NewsTriggerHit(
                    rec.tickerY() + "/" + rec.tickerX(),
                    NewsTriggerType.STALE_PRICE_DATA,
                    NewsRiskLevel.BLOCK,
                    "Последняя общая свеча: " + rec.asOfDate(),
                    now.atStartOfDay(),
                    "Данные пары старше " + staleDays + " дней — возможен делистинг/уход с торгов одной ноги.",
                    true
            ));
        }

        addTradableHit(hits, statusY);
        addTradableHit(hits, statusX);

        if (!indexTickers.isEmpty()) {
            if (!indexTickers.contains(statusY.ticker())) {
                hits.add(new NewsTriggerHit(
                        statusY.ticker(),
                        NewsTriggerType.NOT_IN_INDEX,
                        NewsRiskLevel.MEDIUM,
                        statusY.ticker() + " сейчас не в составе " + properties.index(),
                        now.atStartOfDay(),
                        "Бумага не в актуальном индексе — выше риск «устаревшей» пары.",
                        true
                ));
            }
            if (!indexTickers.contains(statusX.ticker())) {
                hits.add(new NewsTriggerHit(
                        statusX.ticker(),
                        NewsTriggerType.NOT_IN_INDEX,
                        NewsRiskLevel.MEDIUM,
                        statusX.ticker() + " сейчас не в составе " + properties.index(),
                        now.atStartOfDay(),
                        "Бумага не в актуальном индексе — выше риск «устаревшей» пары.",
                        true
                ));
            }
        }

        return hits;
    }

    private void addTradableHit(List<NewsTriggerHit> hits, SecurityTradingStatus status) {
        if (status.tradable()) {
            return;
        }
        String detail = !status.found()
                ? "Бумага не найдена на board " + properties.board()
                : "STATUS=" + status.status() + ", TRADINGSTATUS=" + status.tradingStatus();
        hits.add(new NewsTriggerHit(
                status.ticker(),
                NewsTriggerType.NOT_TRADABLE,
                NewsRiskLevel.BLOCK,
                detail,
                LocalDate.now().atStartOfDay(),
                "Инструмент недоступен для нормальной торговли — парный вход запрещён.",
                true
        ));
    }

    private boolean isAsymmetric(List<NewsTriggerHit> hits, String y, String x) {
        boolean hitY = hits.stream().anyMatch(h ->
                y.equalsIgnoreCase(h.ticker()) || h.ticker().toUpperCase().startsWith(y + "/"));
        boolean hitX = hits.stream().anyMatch(h ->
                x.equalsIgnoreCase(h.ticker()) || h.ticker().toUpperCase().endsWith("/" + x));
        if (hitY && hitX) {
            return false;
        }
        return hitY || hitX;
    }

    private String buildNewsSummary(NewsRiskLevel level, boolean asymmetric, List<NewsTriggerHit> hits) {
        NewsTriggerHit top = hits.get(0);
        String asym = asymmetric ? "асимметрия по ногам" : "похоже на общий фон";
        return level.name() + ": " + top.type() + " — " + top.explanation() + " (" + asym + "). Всего триггеров: " + hits.size();
    }

    private FinalTradeDecision decide(TradingSignal signal, NewsRiskLevel risk) {
        boolean actionable = signal == TradingSignal.LONG_SPREAD || signal == TradingSignal.SHORT_SPREAD;
        return switch (risk) {
            case BLOCK -> FinalTradeDecision.BLOCK;
            // HIGH: жёсткий конфликт с техсигналом → не открываем (CONFLICT)
            case HIGH -> actionable ? FinalTradeDecision.BLOCK : FinalTradeDecision.WATCH;
            case MEDIUM -> actionable ? FinalTradeDecision.REDUCE_SIZE : FinalTradeDecision.WATCH;
            case LOW -> {
                if (actionable) {
                    yield FinalTradeDecision.ENTER;
                }
                if (signal == TradingSignal.WATCH) {
                    yield FinalTradeDecision.WATCH;
                }
                yield FinalTradeDecision.WATCH;
            }
        };
    }

    private String decisionSummary(FinalTradeDecision decision, TradingRecommendation rec, PairNewsAssessment news) {
        boolean actionable = rec.signal() == TradingSignal.LONG_SPREAD
                || rec.signal() == TradingSignal.SHORT_SPREAD;
        boolean conflict = actionable
                && news.riskLevel() != NewsRiskLevel.LOW
                && (decision == FinalTradeDecision.BLOCK || decision == FinalTradeDecision.REDUCE_SIZE);

        return switch (decision) {
            case ENTER -> "ИТОГ: ВХОД разрешён — техника подтверждена, фундаментальных блокеров нет.";
            case REDUCE_SIZE -> conflict
                    ? "CONFLICT: техника vs фундамент — вход только уменьшенным размером ("
                    + news.riskLevel() + "). " + shortFundHint(news)
                    : "ИТОГ: вход только уменьшенным размером — caution (" + news.riskLevel() + ").";
            case WATCH -> regimeWatchSummary(rec, news);
            case BLOCK -> conflict
                    ? "CONFLICT: техника vs фундамент — вход ЗАПРЕЩЁН. " + shortFundHint(news)
                    : "ИТОГ: ВХОД ЗАПРЕЩЁН — структурный/новостной блокер.";
        };
    }

    private String shortFundHint(PairNewsAssessment news) {
        if (news.hits().isEmpty()) {
            return news.summary();
        }
        NewsTriggerHit top = news.hits().get(0);
        return top.type() + ": " + top.explanation();
    }

    private String regimeWatchSummary(TradingRecommendation rec, PairNewsAssessment news) {
        String s = rec.summary() == null ? "" : rec.summary();
        if (s.contains("тренд") || s.contains("TREND") || s.contains("боковик")) {
            return "ИТОГ: НЕ ТОРГОВАТЬ — выявлен тренд, стратегия только боковик.";
        }
        boolean actionable = rec.signal() == TradingSignal.LONG_SPREAD
                || rec.signal() == TradingSignal.SHORT_SPREAD;
        if (actionable && news.riskLevel() != NewsRiskLevel.LOW) {
            return "CONFLICT: техника vs фундамент — наблюдаем, не входим. " + shortFundHint(news);
        }
        return "ИТОГ: не входить — наблюдать (техника=" + rec.signal()
                + ", фундамент=" + news.riskLevel() + ").";
    }

    private String beginnerGuide(FinalTradeDecision decision, TradingRecommendation rec, PairNewsAssessment news) {
        StringBuilder sb = new StringBuilder();
        sb.append(decisionSummary(decision, rec, news)).append("\n\n");
        sb.append("Техника: ").append(rec.summary()).append("\n");
        sb.append("Фундамент/новости: ").append(news.summary()).append("\n");
        if (!news.hits().isEmpty()) {
            sb.append("\nЧто сработало:\n");
            int i = 1;
            for (NewsTriggerHit hit : news.hits()) {
                if (i > 5) {
                    sb.append("… и ещё ").append(news.hits().size() - 5).append(" триггер(ов)\n");
                    break;
                }
                sb.append(i++).append(") [").append(hit.severity()).append("] ")
                        .append(hit.ticker()).append(": ").append(hit.title()).append("\n");
            }
        }
        sb.append("\nПорядок: сначала техника, затем фундамент (только multi-day / DAILY), ")
                .append("и только потом рекомендация и paper. ")
                .append("ENTER = можно разбирать размер. ")
                .append("REDUCE = CONFLICT с осторожностью. ")
                .append("BLOCK = CONFLICT / блокер — не открывать, даже если стрелки красивые.");
        return sb.toString();
    }

    private FinalTradeRecommendation intradayWithEventOverlay(TradingRecommendation rec) {
        if (eventCalendarRiskService != null
                && eventCalendarRiskService.shouldBlockNewEntry(rec, java.time.LocalDateTime.now())) {
            String reason = eventCalendarRiskService.eventReason(rec.tickerY(), rec.tickerX(),
                            java.time.LocalDateTime.now())
                    .orElse("Событие в календаре — вход запрещён");
            PairNewsAssessment news = new PairNewsAssessment(
                    NewsRiskLevel.BLOCK, true, reason, List.of(), 0);
            FinalTradeDecision decision = (rec.signal() == TradingSignal.LONG_SPREAD
                    || rec.signal() == TradingSignal.SHORT_SPREAD)
                    ? FinalTradeDecision.BLOCK
                    : FinalTradeDecision.WATCH;
            return new FinalTradeRecommendation(rec, news, decision,
                    decisionSummary(decision, rec, news), beginnerGuide(decision, rec, news),
                    rationaleService.build(rec, decision, news, BookKind.INTRADAY));
        }
        return passthrough(rec, "Фундаментальный фильтр пропущен: книга INTRADAY (только техника).",
                BookKind.INTRADAY);
    }

    private FinalTradeRecommendation passthrough(TradingRecommendation rec, String reason) {
        return passthrough(rec, reason, BookKind.DAILY);
    }

    private FinalTradeRecommendation passthrough(TradingRecommendation rec, String reason, BookKind book) {
        PairNewsAssessment news = new PairNewsAssessment(NewsRiskLevel.LOW, false, reason, List.of(), 0);
        FinalTradeDecision decision = decide(rec.signal(), NewsRiskLevel.LOW);
        return new FinalTradeRecommendation(rec, news, decision,
                decisionSummary(decision, rec, news), beginnerGuide(decision, rec, news),
                rationaleService.build(rec, decision, news, book));
    }

    private int riskRank(NewsRiskLevel level) {
        return switch (level) {
            case LOW -> 0;
            case MEDIUM -> 1;
            case HIGH -> 2;
            case BLOCK -> 3;
        };
    }

    private int decisionRank(FinalTradeDecision decision) {
        return switch (decision) {
            case ENTER -> 0;
            case REDUCE_SIZE -> 1;
            case WATCH -> 2;
            case BLOCK -> 3;
        };
    }
}
