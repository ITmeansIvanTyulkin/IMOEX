package com.moex.cointegration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moex.cointegration.config.CapitalProperties;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.config.MicrostructureProperties;
import com.moex.cointegration.config.SessionProperties;
import com.moex.cointegration.model.BookKind;
import com.moex.cointegration.model.Candle;
import com.moex.cointegration.model.ClusterReviewReport;
import com.moex.cointegration.model.FinalTradeDecision;
import com.moex.cointegration.model.FinalTradeRecommendation;
import com.moex.cointegration.model.MarketRegimeSnapshot;
import com.moex.cointegration.model.NewsRiskLevel;
import com.moex.cointegration.model.PairAnalysisResult;
import com.moex.cointegration.model.PairCashStats;
import com.moex.cointegration.model.PairNewsAssessment;
import com.moex.cointegration.model.PaperTradeEntry;
import com.moex.cointegration.model.PortfolioHistoricalReplayReport;
import com.moex.cointegration.model.PriceSeries;
import com.moex.cointegration.model.TradingRecommendation;
import com.moex.cointegration.model.TradingSignal;
import com.moex.cointegration.storage.MarketDataStorage;
import com.moex.cointegration.universe.ResearchPairWhitelist;
import com.moex.cointegration.universe.TierOneCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Портфельный bar-by-bar replay: один счёт, live-отбор (FDR + quality + ADX), слоты CapitalAllocator.
 */
@Service
public class PortfolioHistoricalReplayService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioHistoricalReplayService.class);
    private static final int MIN_DAILY_BARS = 100;
    private static final int MIN_HOURLY_BARS = 80;
    private static final String INDEX_CACHE_KEY = "_INDEX_IMOEX";

    private final ImoexProperties properties;
    private final CapitalProperties capitalProperties;
    private final SessionProperties sessionProperties;
    private final PreprocessingService preprocessingService;
    private final UniverseFilterService universeFilterService;
    private final PairUniverseScanService pairUniverseScanService;
    private final RiskPolicyService riskPolicyService;
    private final MarketRegimeService marketRegimeService;
    private final EventCalendarRiskService eventCalendarRiskService;
    private final MicrostructureProperties microstructureProperties;
    private final MarketDataStorage sourceStorage;
    private final MonthlyClusterReviewService monthlyClusterReviewService;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private List<ResearchPairWhitelist.Pair> pairWhitelist = List.of();

    public PortfolioHistoricalReplayService(
            ImoexProperties properties,
            CapitalProperties capitalProperties,
            SessionProperties sessionProperties,
            PreprocessingService preprocessingService,
            UniverseFilterService universeFilterService,
            PairUniverseScanService pairUniverseScanService,
            RiskPolicyService riskPolicyService,
            MarketRegimeService marketRegimeService,
            EventCalendarRiskService eventCalendarRiskService,
            MicrostructureProperties microstructureProperties,
            MarketDataStorage sourceStorage,
            MonthlyClusterReviewService monthlyClusterReviewService
    ) {
        this.properties = properties;
        this.capitalProperties = capitalProperties;
        this.sessionProperties = sessionProperties;
        this.preprocessingService = preprocessingService;
        this.universeFilterService = universeFilterService;
        this.pairUniverseScanService = pairUniverseScanService;
        this.riskPolicyService = riskPolicyService;
        this.marketRegimeService = marketRegimeService;
        this.eventCalendarRiskService = eventCalendarRiskService;
        this.microstructureProperties = microstructureProperties;
        this.sourceStorage = sourceStorage;
        this.monthlyClusterReviewService = monthlyClusterReviewService;
    }

    /** Research-only: фиксированный набор пар вместо FDR-скана. */
    public void setPairWhitelist(List<ResearchPairWhitelist.Pair> pairs) {
        this.pairWhitelist = pairs == null ? List.of() : List.copyOf(pairs);
    }

    public PortfolioHistoricalReplayReport replayAndSave(
            String label,
            BookKind book,
            LocalDate from,
            LocalDate to,
            String outputFileName
    ) throws IOException {
        PortfolioHistoricalReplayReport report = replay(label, book, from, to);
        Path out = Path.of(properties.dataDir(), outputFileName);
        Files.createDirectories(out.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), report);
        log.info("Portfolio replay {} saved → {} (net≈{} ₽, closed={})",
                label, out, String.format(Locale.ROOT, "%.0f", report.netPnlRub()), report.tradesClosed());
        return report;
    }

    public PortfolioHistoricalReplayReport replay(String label, BookKind book, LocalDate from, LocalDate to)
            throws IOException {
        if (book == BookKind.INTRADAY) {
            return replayIntraday(label, from, to);
        }
        return replayDaily(label, from, to);
    }

    private PortfolioHistoricalReplayReport replayDaily(String label, LocalDate from, LocalDate to) throws IOException {
        List<Candle> indexCandles = loadIndexCandles();
        Map<String, List<Candle>> allCandles = loadDailyCandles();
        List<LocalDate> calendar = indexCandles.stream()
                .map(Candle::date)
                .filter(d -> !d.isBefore(from) && !d.isAfter(to))
                .distinct()
                .sorted()
                .toList();
        if (calendar.size() < 30) {
            throw new IllegalArgumentException("Недостаточно торговых дней для replay " + from + " — " + to);
        }

        ReplayContext ctx = newReplayContext(BookKind.DAILY);
        PairScanParams scanParams = PairScanParams.daily(properties);
        int maxPairs = capitalProperties.allocation().dailyMaxPairs();
        double grossCap = capitalProperties.allocation().dailyGrossCap();
        int tradesBefore = ctx.paper.getJournal(BookKind.DAILY).size();
        int barsWithFdr = 0;
        int currentMonth = -1;
        List<PairAnalysisResult> monthTopPairs = List.of();

        for (int bar = 0; bar < calendar.size(); bar++) {
            LocalDate asOf = calendar.get(bar);
            int indexIdx = indexIndexOnOrBefore(indexCandles, asOf);
            Map<String, List<Candle>> slices = slicesAsOfDaily(allCandles, asOf, MIN_DAILY_BARS);
            if (slices.size() < 5) {
                continue;
            }
            writeDailySlices(ctx.replayStorage, slices);
            marketRegimeService.publish(marketRegimeService.evaluateAt(indexCandles, indexIdx));

            int monthKey = asOf.getYear() * 100 + asOf.getMonthValue();
            if (monthKey != currentMonth) {
                currentMonth = monthKey;
                monthTopPairs = resolveActivePairs(ctx, slices, scanParams, BookKind.DAILY, asOf);
                log.info("  DAILY {}: month {} — top {} pairs{}",
                        label, asOf, monthTopPairs.size(),
                        pairWhitelist.isEmpty() ? "" : " (whitelist)");
            }
            if (!monthTopPairs.isEmpty()) {
                barsWithFdr++;
            }
            List<PairAnalysisResult> pairsToday = refreshPairsForBar(ctx, monthTopPairs, BookKind.DAILY);
            List<TradingRecommendation> tech = ctx.recommendations.analyzeAndPrint(
                    pairsToday, BookKind.DAILY, 1.0, null, null);
            List<FinalTradeRecommendation> finals = toFinals(tech, BookKind.DAILY);
            LocalDateTime at = asOf.atTime(19, 5);
            PaperTradingService.runAt(at, () -> {
                try {
                    ctx.paper.sync(finals, tech, BookKind.DAILY, maxPairs, grossCap);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });

            if (bar > 0 && bar % 50 == 0) {
                log.info("  DAILY replay {}: {}/{} days, open={}",
                        label, bar, calendar.size(), ctx.paper.getOpenTrades(BookKind.DAILY).size());
            }
        }

        return buildReport(label, BookKind.DAILY, from, to, calendar.size(), barsWithFdr,
                tradesBefore, maxPairs, grossCap, ctx.paper);
    }

    private PortfolioHistoricalReplayReport replayIntraday(String label, LocalDate from, LocalDate to)
            throws IOException {
        List<Candle> indexCandles = loadIndexCandles();
        Map<String, List<Candle>> allCandles = loadHourlyCandles();
        List<LocalDateTime> calendar = buildHourlyCalendar(allCandles, from, to);
        if (calendar.size() < MIN_HOURLY_BARS) {
            throw new IllegalArgumentException("Недостаточно часовых баров для replay " + from + " — " + to);
        }

        ReplayContext ctx = newReplayContext(BookKind.INTRADAY);
        PairScanParams scanParams = PairScanParams.intraday(properties, sessionProperties);
        int maxPairs = capitalProperties.allocation().intradayMaxPairs();
        double grossCap = capitalProperties.allocation().intradayGrossCap();
        int tradesBefore = ctx.paper.getJournal(BookKind.INTRADAY).size();
        int barsWithFdr = 0;
        int warmup = Math.max(MIN_HOURLY_BARS, sessionProperties.intradayRollingZWindow());
        int currentWeek = -1;
        List<PairAnalysisResult> weekTopPairs = List.of();

        for (int bar = warmup; bar < calendar.size(); bar++) {
            LocalDateTime asOf = calendar.get(bar);
            Map<String, List<Candle>> slices = slicesAsOfHourly(allCandles, asOf, MIN_HOURLY_BARS);
            if (slices.size() < 5) {
                continue;
            }
            writeHourlySlices(ctx.replayStorage, slices);
            int indexIdx = indexIndexOnOrBefore(indexCandles, asOf.toLocalDate());
            marketRegimeService.publish(marketRegimeService.evaluateAt(indexCandles, indexIdx));

            LocalDate day = asOf.toLocalDate();
            int weekKey = day.getYear() * 100 + day.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear());
            if (weekKey != currentWeek) {
                currentWeek = weekKey;
                weekTopPairs = resolveActivePairs(ctx, slices, scanParams, BookKind.INTRADAY, day);
                log.info("  INTRADAY {}: week {} — top {} pairs{}",
                        label, day, weekTopPairs.size(),
                        pairWhitelist.isEmpty() ? "" : " (whitelist)");
            }
            if (!weekTopPairs.isEmpty()) {
                barsWithFdr++;
            }
            List<PairAnalysisResult> pairsNow = refreshPairsForBar(ctx, weekTopPairs, BookKind.INTRADAY);
            List<TradingRecommendation> tech = ctx.recommendations.analyzeAndPrint(
                    pairsNow,
                    BookKind.INTRADAY,
                    sessionProperties.hoursPerSession(),
                    sessionProperties.intradayMinHalfLifeDays(),
                    sessionProperties.intradayTradeMaxHalfLifeDays()
            );
            List<FinalTradeRecommendation> finals = toFinals(tech, BookKind.INTRADAY);
            PaperTradingService.runAt(asOf, () -> {
                try {
                    ctx.paper.sync(finals, tech, BookKind.INTRADAY, maxPairs, grossCap);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });

            if (bar > warmup && (bar - warmup) % 200 == 0) {
                log.info("  INTRADAY replay {}: {}/{} bars, open={}",
                        label, bar, calendar.size(), ctx.paper.getOpenTrades(BookKind.INTRADAY).size());
            }
        }

        return buildReport(label, BookKind.INTRADAY, from, to, calendar.size() - warmup, barsWithFdr,
                tradesBefore, maxPairs, grossCap, ctx.paper);
    }

    private ReplayContext newReplayContext(BookKind book) throws IOException {
        Path tempDir = Files.createTempDirectory("trinity-portfolio-replay-");
        ImoexProperties replayProps = replayProperties(tempDir);
        MarketDataStorage replayStorage = new MarketDataStorage(replayProps);
        PairLookupService pairLookup = new PairLookupService(replayStorage, preprocessingService, replayProps);
        MicrostructureExecutionService replayMicro = new MicrostructureExecutionService(
                microstructureProperties, sessionProperties, replayStorage);
        TradingRecommendationService recommendations = new TradingRecommendationService(
                replayProps, riskPolicyService, replayMicro);
        PaperTradingService paper = new PaperTradingService(
                replayProps,
                capitalProperties,
                sessionProperties,
                riskPolicyService,
                pairLookup,
                replayStorage,
                null,
                eventCalendarRiskService,
                replayMicro
        );
        return new ReplayContext(replayStorage, paper, recommendations, pairLookup);
    }

    private record ReplayContext(
            MarketDataStorage replayStorage,
            PaperTradingService paper,
            TradingRecommendationService recommendations,
            PairLookupService pairLookup
    ) {
    }

    private List<PairAnalysisResult> resolveActivePairs(
            ReplayContext ctx,
            Map<String, List<Candle>> slices,
            PairScanParams scanParams,
            BookKind book,
            LocalDate asOf
    ) throws IOException {
        List<PairAnalysisResult> selected = !pairWhitelist.isEmpty()
                ? resolveWhitelistPairs(ctx, slices, book)
                : scanTopPairs(slices, scanParams, book);
        if (book != BookKind.DAILY) {
            return selected;
        }
        List<PaperTradeEntry> journal = ctx.paper.getJournal(BookKind.DAILY);
        ClusterReviewReport review = monthlyClusterReviewService.review(journal, BookKind.DAILY, asOf);
        return monthlyClusterReviewService.filterPairs(selected, review, journal, BookKind.DAILY, asOf);
    }

    private List<PairAnalysisResult> resolveWhitelistPairs(
            ReplayContext ctx,
            Map<String, List<Candle>> slices,
            BookKind book
    ) throws IOException {
        List<PairAnalysisResult> out = new ArrayList<>();
        for (ResearchPairWhitelist.Pair pair : pairWhitelist) {
            if (!slices.containsKey(pair.tickerY()) || !slices.containsKey(pair.tickerX())) {
                continue;
            }
            ctx.pairLookup.analyzeFromCandles(pair.tickerY(), pair.tickerX(), book)
                    .ifPresent(out::add);
        }
        double barsPerDay = book == BookKind.INTRADAY ? sessionProperties.hoursPerSession() : 1.0;
        Double minHl = book == BookKind.INTRADAY ? sessionProperties.intradayMinHalfLifeDays() : null;
        Double maxHl = book == BookKind.INTRADAY ? sessionProperties.intradayTradeMaxHalfLifeDays() : null;
        return out.stream()
                .filter(p -> riskPolicyService.passesQualityFilters(p, barsPerDay, minHl, maxHl))
                .sorted(Comparator.comparingDouble(PairAnalysisResult::sharpeRatio).reversed())
                .limit(Math.max(1, properties.cointegration().topN()))
                .toList();
    }

    private List<PairAnalysisResult> scanTopPairs(
            Map<String, List<Candle>> slices,
            PairScanParams scanParams,
            BookKind book
    ) throws IOException {
        Map<String, PriceSeries> filtered = universeFilterService.filterFromCandles(slices, book);
        Map<String, PriceSeries> processed = preprocessingService.preprocess(filtered);
        List<PairAnalysisResult> fdrPairs = pairUniverseScanService.scan(processed, slices, scanParams);
        double barsPerDay = book == BookKind.INTRADAY ? sessionProperties.hoursPerSession() : 1.0;
        Double minHl = book == BookKind.INTRADAY ? sessionProperties.intradayMinHalfLifeDays() : null;
        Double maxHl = book == BookKind.INTRADAY ? sessionProperties.intradayTradeMaxHalfLifeDays() : null;
        return fdrPairs.stream()
                .filter(p -> riskPolicyService.passesQualityFilters(p, barsPerDay, minHl, maxHl))
                .sorted(Comparator.comparingDouble(PairAnalysisResult::sharpeRatio).reversed())
                .limit(properties.cointegration().topN())
                .toList();
    }

    /** Пересчёт Z/spread на as-of баре для пар, отобранных в начале месяца. */
    private List<PairAnalysisResult> refreshPairsForBar(
            ReplayContext ctx,
            List<PairAnalysisResult> monthTop,
            BookKind book
    ) throws IOException {
        if (monthTop.isEmpty()) {
            return List.of();
        }
        List<PairAnalysisResult> fresh = new ArrayList<>();
        for (PairAnalysisResult p : monthTop) {
            ctx.pairLookup.analyzeFromCandles(p.tickerY(), p.tickerX(), book)
                    .ifPresent(fresh::add);
        }
        return fresh;
    }

    private List<PairAnalysisResult> topBySharpe(List<PairAnalysisResult> pairs) {
        return pairs.stream()
                .sorted(Comparator.comparingDouble(PairAnalysisResult::sharpeRatio).reversed())
                .limit(properties.cointegration().topN())
                .toList();
    }

    private List<FinalTradeRecommendation> toFinals(List<TradingRecommendation> tech, BookKind book) {
        List<FinalTradeRecommendation> out = new ArrayList<>();
        for (TradingRecommendation t : tech) {
            LocalDateTime at = t.asOfDate().atTime(book == BookKind.INTRADAY ? 12 : 19, 5);
            if (book == BookKind.INTRADAY
                    && eventCalendarRiskService != null
                    && eventCalendarRiskService.shouldBlockNewEntry(t, at)) {
                var news = new PairNewsAssessment(NewsRiskLevel.BLOCK, true, "event window", List.of(), 0);
                out.add(new FinalTradeRecommendation(t, news, FinalTradeDecision.BLOCK, "BLOCK event", "replay", "replay"));
                continue;
            }
            boolean actionable = t.signal() == TradingSignal.LONG_SPREAD
                    || t.signal() == TradingSignal.SHORT_SPREAD;
            FinalTradeDecision decision = actionable ? FinalTradeDecision.ENTER : FinalTradeDecision.WATCH;
            var news = new PairNewsAssessment(NewsRiskLevel.LOW, false, "replay", List.of(), 0);
            out.add(new FinalTradeRecommendation(t, news, decision, decision.name(), "replay", "replay"));
        }
        return out;
    }

    private PortfolioHistoricalReplayReport buildReport(
            String label,
            BookKind book,
            LocalDate from,
            LocalDate to,
            int barsProcessed,
            int barsWithFdr,
            int tradesBefore,
            int maxPairs,
            double grossCap,
            PaperTradingService paper
    ) {
        List<PaperTradeEntry> journal = paper.getJournal(book);
        var summary = paper.summary(book);
        double realized = summary.realizedPnlRub() == null ? 0.0 : summary.realizedPnlRub();
        double unrealized = summary.unrealizedPnlRub() == null ? 0.0 : summary.unrealizedPnlRub();
        double net = realized + unrealized;
        int opened = journal.size() - tradesBefore;
        int closed = (int) journal.stream().filter(e -> "CLOSED".equals(e.status())).count();
        double equityStart = capitalProperties.equityRub();

        log.info("Portfolio replay {} [{}]: bars={}, fdrBars={}, opened={}, closed={}, net≈{} ₽",
                label, book, barsProcessed, barsWithFdr, opened, closed, String.format(Locale.ROOT, "%.0f", net));

        CashStats cash = cashStats(journal);
        List<PairCashStats> pairs = pairBreakdown(journal);

        return new PortfolioHistoricalReplayReport(
                label,
                validationProfile(),
                book,
                from,
                to,
                equityStart,
                equityStart + net,
                net,
                realized,
                unrealized,
                maxDrawdown(journal),
                cash.expectancyRub(),
                cash.avgWinRub(),
                cash.avgLossRub(),
                cash.profitFactor(),
                barsProcessed,
                barsWithFdr,
                opened,
                closed,
                winRate(journal),
                maxPairs,
                grossCap,
                LocalDateTime.now(),
                pairs,
                List.copyOf(journal)
        );
    }

    private String validationProfile() {
        if (!pairWhitelist.isEmpty()) {
            return "WHITELIST_RESEARCH";
        }
        var coint = properties.cointegration();
        var risk = properties.risk();
        if (!coint.entryReversalRequired() && coint.zScoreExit() >= 0.4) {
            return "OSS_RESEARCH";
        }
        boolean research = risk.minHalfLifeDays() < 1.0 - 1e-9
                || risk.minCoveragePercent() < 85.0 - 1e-9;
        return research ? "RESEARCH" : "LIVE";
    }

    private record CashStats(double expectancyRub, double avgWinRub, double avgLossRub, double profitFactor) {
    }

    private static CashStats cashStats(List<PaperTradeEntry> journal) {
        List<Double> pnls = journal.stream()
                .filter(e -> "CLOSED".equals(e.status()) && e.pnlRub() != null)
                .map(PaperTradeEntry::pnlRub)
                .toList();
        if (pnls.isEmpty()) {
            return new CashStats(0, 0, 0, 0);
        }
        double sum = pnls.stream().mapToDouble(Double::doubleValue).sum();
        double expectancy = sum / pnls.size();
        List<Double> wins = pnls.stream().filter(p -> p > 0).toList();
        List<Double> losses = pnls.stream().filter(p -> p < 0).toList();
        double avgWin = wins.isEmpty() ? 0 : wins.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double avgLoss = losses.isEmpty() ? 0 : losses.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double grossWin = wins.stream().mapToDouble(Double::doubleValue).sum();
        double grossLossAbs = Math.abs(losses.stream().mapToDouble(Double::doubleValue).sum());
        double pf = grossLossAbs < 1e-9 ? (grossWin > 0 ? Double.POSITIVE_INFINITY : 0.0) : grossWin / grossLossAbs;
        return new CashStats(expectancy, avgWin, avgLoss, pf);
    }

    private static List<PairCashStats> pairBreakdown(List<PaperTradeEntry> journal) {
        Map<String, List<PaperTradeEntry>> byPair = new LinkedHashMap<>();
        for (PaperTradeEntry e : journal) {
            String key = e.tickerY() + "/" + e.tickerX();
            byPair.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }
        List<PairCashStats> out = new ArrayList<>();
        for (Map.Entry<String, List<PaperTradeEntry>> e : byPair.entrySet()) {
            List<PaperTradeEntry> trades = e.getValue();
            String[] parts = e.getKey().split("/", 2);
            int opened = trades.size();
            List<PaperTradeEntry> closed = trades.stream().filter(t -> "CLOSED".equals(t.status())).toList();
            double net = closed.stream()
                    .filter(t -> t.pnlRub() != null)
                    .mapToDouble(PaperTradeEntry::pnlRub)
                    .sum();
            double avg = closed.isEmpty() ? 0.0 : net / closed.size();
            out.add(new PairCashStats(
                    parts[0],
                    parts.length > 1 ? parts[1] : "?",
                    opened,
                    closed.size(),
                    net,
                    winRate(closed),
                    avg,
                    maxDrawdown(closed)
            ));
        }
        out.sort(Comparator.comparingDouble(PairCashStats::netPnlRub).reversed());
        return out;
    }

    private Map<String, List<Candle>> loadDailyCandles() throws IOException {
        Map<String, List<Candle>> out = new LinkedHashMap<>();
        for (String ticker : sourceStorage.listStoredTickers()) {
            if (ticker.startsWith("_INDEX_")) {
                continue;
            }
            List<Candle> candles = sourceStorage.loadCandles(ticker).stream()
                    .sorted(Comparator.comparing(Candle::date))
                    .toList();
            if (candles.size() >= MIN_DAILY_BARS) {
                out.put(ticker, candles);
            }
        }
        log.info("Portfolio replay: loaded {} daily tickers", out.size());
        return out;
    }

    private Map<String, List<Candle>> loadHourlyCandles() throws IOException {
        Map<String, List<Candle>> out = new LinkedHashMap<>();
        java.util.LinkedHashSet<String> tickers = new java.util.LinkedHashSet<>(TierOneCatalog.tickers());
        tickers.addAll(ResearchPairWhitelist.tickersOf(pairWhitelist));
        for (String ticker : tickers) {
            List<Candle> candles = sourceStorage.loadHourlyCandles(ticker).stream()
                    .sorted(Comparator.comparing(Candle::begin))
                    .toList();
            if (candles.size() >= MIN_HOURLY_BARS) {
                out.put(ticker, candles);
            }
        }
        log.info("Portfolio replay: loaded {} hourly tickers (tier1+whitelist)", out.size());
        return out;
    }

    private List<Candle> loadIndexCandles() throws IOException {
        List<Candle> candles = sourceStorage.loadCandles(INDEX_CACHE_KEY).stream()
                .sorted(Comparator.comparing(Candle::date))
                .toList();
        if (candles.size() < 80) {
            throw new IllegalStateException("Нет индексных свечей " + INDEX_CACHE_KEY + " для ADX replay");
        }
        return candles;
    }

    private static List<LocalDateTime> buildHourlyCalendar(
            Map<String, List<Candle>> allCandles,
            LocalDate from,
            LocalDate to
    ) {
        Map<LocalDateTime, Boolean> times = new HashMap<>();
        for (List<Candle> candles : allCandles.values()) {
            for (Candle c : candles) {
                LocalDate d = c.begin().toLocalDate();
                if (!d.isBefore(from) && !d.isAfter(to)) {
                    times.put(c.begin(), Boolean.TRUE);
                }
            }
        }
        return times.keySet().stream().sorted().toList();
    }

    private static Map<String, List<Candle>> slicesAsOfDaily(
            Map<String, List<Candle>> all,
            LocalDate asOf,
            int minBars
    ) {
        Map<String, List<Candle>> slices = new LinkedHashMap<>();
        for (Map.Entry<String, List<Candle>> e : all.entrySet()) {
            int idx = lastIndexOnOrBeforeDate(e.getValue(), asOf);
            if (idx >= minBars - 1) {
                slices.put(e.getKey(), e.getValue().subList(0, idx + 1));
            }
        }
        return slices;
    }

    private static Map<String, List<Candle>> slicesAsOfHourly(
            Map<String, List<Candle>> all,
            LocalDateTime asOf,
            int minBars
    ) {
        Map<String, List<Candle>> slices = new LinkedHashMap<>();
        for (Map.Entry<String, List<Candle>> e : all.entrySet()) {
            int idx = lastIndexOnOrBeforeBegin(e.getValue(), asOf);
            if (idx >= minBars - 1) {
                slices.put(e.getKey(), e.getValue().subList(0, idx + 1));
            }
        }
        return slices;
    }

    private static int indexIndexOnOrBefore(List<Candle> candles, LocalDate date) {
        int idx = lastIndexOnOrBeforeDate(candles, date);
        return Math.max(0, idx);
    }

    private static int lastIndexOnOrBeforeDate(List<Candle> candles, LocalDate date) {
        int lo = 0;
        int hi = candles.size() - 1;
        int result = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            LocalDate d = candles.get(mid).date();
            if (!d.isAfter(date)) {
                result = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return result;
    }

    private static int lastIndexOnOrBeforeBegin(List<Candle> candles, LocalDateTime begin) {
        int lo = 0;
        int hi = candles.size() - 1;
        int result = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            LocalDateTime b = candles.get(mid).begin();
            if (!b.isAfter(begin)) {
                result = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return result;
    }

    private void writeDailySlices(MarketDataStorage replayStorage, Map<String, List<Candle>> slices)
            throws IOException {
        for (Map.Entry<String, List<Candle>> e : slices.entrySet()) {
            replayStorage.saveCandles(e.getKey(), e.getValue());
        }
    }

    private void writeHourlySlices(MarketDataStorage replayStorage, Map<String, List<Candle>> slices)
            throws IOException {
        for (Map.Entry<String, List<Candle>> e : slices.entrySet()) {
            replayStorage.saveHourlyCandles(e.getKey(), e.getValue());
        }
    }

    private ImoexProperties replayProperties(Path tempDir) {
        var paper = properties.paper();
        var replayPaper = new ImoexProperties.PaperProperties(
                true,
                paper.notionalPerLeg(),
                "replay-portfolio-paper.json",
                false,
                paper.dailyCron(),
                false,
                paper.intradayCron(),
                paper.slippageBps(),
                paper.applyBorrow(),
                paper.notionalPerLegPct(),
                paper.slippageBpsDaily(),
                paper.slippageBpsIntraday(),
                false
        );
        return new ImoexProperties(
                properties.baseUrl(),
                properties.board(),
                properties.index(),
                properties.historyYears(),
                properties.commissionRate(),
                properties.cointegration(),
                properties.news(),
                tempDir.toString(),
                tempDir.resolve("charts").toString(),
                properties.risk(),
                properties.walkForward(),
                replayPaper,
                properties.universe(),
                properties.portfolio(),
                properties.auth()
        );
    }

    private static double winRate(List<PaperTradeEntry> journal) {
        long wins = journal.stream()
                .filter(e -> "CLOSED".equals(e.status()) && e.pnlRub() != null && e.pnlRub() > 0)
                .count();
        long closed = journal.stream().filter(e -> "CLOSED".equals(e.status())).count();
        return closed == 0 ? 0.0 : (double) wins / closed;
    }

    private static double maxDrawdown(List<PaperTradeEntry> journal) {
        double cum = 0;
        double peak = 0;
        double maxDd = 0;
        for (PaperTradeEntry e : journal) {
            if ("CLOSED".equals(e.status()) && e.pnlRub() != null) {
                cum += e.pnlRub();
                peak = Math.max(peak, cum);
                maxDd = Math.max(maxDd, peak - cum);
            }
        }
        return maxDd;
    }
}
