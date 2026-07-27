package com.moex.cointegration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moex.cointegration.config.CapitalProperties;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.config.MicrostructureProperties;
import com.moex.cointegration.config.SessionProperties;
import com.moex.cointegration.model.BookKind;
import com.moex.cointegration.model.Candle;
import com.moex.cointegration.model.FinalTradeDecision;
import com.moex.cointegration.model.FinalTradeRecommendation;
import com.moex.cointegration.model.HistoricalReplayReport;
import com.moex.cointegration.model.NewsRiskLevel;
import com.moex.cointegration.model.PairNewsAssessment;
import com.moex.cointegration.model.PaperTradeEntry;
import com.moex.cointegration.model.TradingRecommendation;
import com.moex.cointegration.model.TradingSignal;
import com.moex.cointegration.quant.SignalRules;
import com.moex.cointegration.storage.MarketDataStorage;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Исторический прогон paper-пайплайна bar-by-bar: на каждом баре доступны только свечи ≤ as-of.
 */
@Service
public class HistoricalReplayService {

    private static final Logger log = LoggerFactory.getLogger(HistoricalReplayService.class);

    private final ImoexProperties properties;
    private final CapitalProperties capitalProperties;
    private final SessionProperties sessionProperties;
    private final PreprocessingService preprocessingService;
    private final RiskPolicyService riskPolicyService;
    private final EventCalendarRiskService eventCalendarRiskService;
    private final MicrostructureProperties microstructureProperties;
    private final MarketDataStorage storage;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public HistoricalReplayService(
            ImoexProperties properties,
            CapitalProperties capitalProperties,
            SessionProperties sessionProperties,
            PreprocessingService preprocessingService,
            RiskPolicyService riskPolicyService,
            EventCalendarRiskService eventCalendarRiskService,
            MicrostructureProperties microstructureProperties,
            MarketDataStorage storage
    ) {
        this.properties = properties;
        this.capitalProperties = capitalProperties;
        this.sessionProperties = sessionProperties;
        this.preprocessingService = preprocessingService;
        this.riskPolicyService = riskPolicyService;
        this.eventCalendarRiskService = eventCalendarRiskService;
        this.microstructureProperties = microstructureProperties;
        this.storage = storage;
    }

    public HistoricalReplayReport replayFromStorage(
            String tickerY,
            String tickerX,
            LocalDate from,
            LocalDate to,
            BookKind book
    ) throws IOException {
        PairLookupService.AlignedCandles aligned = alignFromStorage(tickerY, tickerX);
        List<Integer> indices = barIndices(aligned.dates(), from, to);
        if (indices.size() < 80) {
            throw new IllegalArgumentException(
                    "Недостаточно баров для replay " + tickerY + "/" + tickerX + " (" + indices.size() + ")");
        }
        return replayAligned(tickerY, tickerX, aligned, indices, book);
    }

    /** Синтетика / фикстура для тестов. */
    public HistoricalReplayReport replaySynthetic(
            String tickerY,
            String tickerX,
            double[] logY,
            double[] logX,
            LocalDate[] dates,
            int fromIndex,
            int toIndex,
            BookKind book
    ) throws IOException {
        List<Candle> cy = new ArrayList<>();
        List<Candle> cx = new ArrayList<>();
        List<LocalDate> dateList = new ArrayList<>();
        for (int i = 0; i < dates.length; i++) {
            dateList.add(dates[i]);
            double py = Math.exp(logY[i]);
            double px = Math.exp(logX[i]);
            cy.add(new Candle(dates[i], py, py, py, py, 1_000_000.0));
            cx.add(new Candle(dates[i], px, px, px, px, 1_000_000.0));
        }
        List<Integer> indices = new ArrayList<>();
        for (int i = fromIndex; i <= toIndex && i < dates.length; i++) {
            indices.add(i);
        }
        return replayAligned(tickerY, tickerX,
                new PairLookupService.AlignedCandles(dateList, cy, cx), indices, book);
    }

    private HistoricalReplayReport replayAligned(
            String tickerY,
            String tickerX,
            PairLookupService.AlignedCandles aligned,
            List<Integer> barIndices,
            BookKind book
    ) throws IOException {
        Path tempDir = Files.createTempDirectory("trinity-replay-");
        Path candlesDir = tempDir.resolve("candles");
        Files.createDirectories(candlesDir);
        ImoexProperties replayProps = replayProperties(tempDir);
        MarketDataStorage replayStorage = new MarketDataStorage(replayProps);
        PairLookupService pairLookup = new PairLookupService(replayStorage, preprocessingService, replayProps);
        MicrostructureExecutionService replayMicro = new MicrostructureExecutionService(
                microstructureProperties, sessionProperties, replayStorage);
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

        var alloc = capitalProperties.allocation();
        int maxPairs = book == BookKind.DAILY ? alloc.dailyMaxPairs() : alloc.intradayMaxPairs();
        double grossCap = book == BookKind.DAILY ? alloc.dailyGrossCap() : alloc.intradayGrossCap();

        int tradesBefore = paper.getJournal(book).size();
        for (int idx : barIndices) {
            if (idx < 60) {
                continue;
            }
            writeSlice(candlesDir, tickerY, aligned.candlesY(), idx);
            writeSlice(candlesDir, tickerX, aligned.candlesX(), idx);

            Optional<com.moex.cointegration.model.PairAnalysisResult> pairOpt =
                    pairLookup.analyzeFromCandles(tickerY, tickerX);
            if (pairOpt.isEmpty()) {
                continue;
            }
            var pair = pairOpt.get();
            var zSeries = pair.zScoreSeries();
            if (zSeries.size() < 2) {
                continue;
            }
            double zCur = zSeries.get(zSeries.size() - 1).value();
            double zPrev = zSeries.get(zSeries.size() - 2).value();
            LocalDate asOf = aligned.dates().get(idx);
            TradingRecommendation tech = buildTech(tickerY, tickerX, zPrev, zCur, asOf, pair);
            FinalTradeRecommendation fin = toFinal(tech, book);
            LocalDateTime at = asOf.atTime(book == BookKind.INTRADAY ? 12 : 19, 5);
            PaperTradingService.runAt(at, () -> {
                try {
                    paper.sync(List.of(fin), List.of(tech), book, maxPairs, grossCap);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        }

        List<PaperTradeEntry> journal = paper.getJournal(book);
        var summary = paper.summary(book);
        double net = summary.realizedPnlRub() + summary.unrealizedPnlRub();
        int opened = journal.size() - tradesBefore;
        int closed = (int) journal.stream().filter(e -> "CLOSED".equals(e.status())).count();

        log.info("Replay {} [{}]: bars={}, opened={}, closed={}, net≈{} ₽",
                tickerY + "/" + tickerX, book, barIndices.size(), opened, closed,
                String.format("%.0f", net));

        return new HistoricalReplayReport(
                tickerY,
                tickerX,
                book,
                aligned.dates().get(barIndices.get(0)),
                aligned.dates().get(barIndices.get(barIndices.size() - 1)),
                barIndices.size(),
                opened,
                closed,
                net,
                summary.realizedPnlRub(),
                maxDrawdown(journal),
                winRate(journal),
                capitalProperties.equityRub(),
                capitalProperties.equityRub() + net,
                LocalDateTime.now(),
                List.copyOf(journal)
        );
    }

    private TradingRecommendation buildTech(
            String y,
            String x,
            double zPrev,
            double zCur,
            LocalDate asOf,
            com.moex.cointegration.model.PairAnalysisResult pair
    ) {
        double entry = properties.cointegration().zScoreEntry();
        boolean rev = properties.cointegration().entryReversalRequired();
        TradingSignal signal;
        if (SignalRules.confirmLongEntry(zPrev, zCur, entry, rev)) {
            signal = TradingSignal.LONG_SPREAD;
        } else if (SignalRules.confirmShortEntry(zPrev, zCur, entry, rev)) {
            signal = TradingSignal.SHORT_SPREAD;
        } else if (Math.abs(zCur) >= entry * 0.75) {
            signal = TradingSignal.WATCH;
        } else {
            signal = TradingSignal.HOLD;
        }
        double spread = pair.spreadSeries().isEmpty() ? 0.0
                : pair.spreadSeries().get(pair.spreadSeries().size() - 1).value();
        return new TradingRecommendation(
                y, x, signal, zCur, asOf, spread, pair.hedgeRatio(),
                pair.halfLifeDays(), pair.sharpeRatio(), pair.pValue(),
                "replay " + signal, "historical bar"
        );
    }

    private void writeSlice(Path candlesDir, String ticker, List<Candle> all, int idx) throws IOException {
        List<Candle> slice = all.subList(0, idx + 1);
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(candlesDir.resolve(ticker + ".json").toFile(), slice);
    }

    private ImoexProperties replayProperties(Path tempDir) {
        var paper = properties.paper();
        var replayPaper = new ImoexProperties.PaperProperties(
                true,
                paper.notionalPerLeg(),
                "replay-paper.json",
                false,
                paper.dailyCron(),
                false,
                paper.intradayCron(),
                paper.slippageBps(),
                paper.applyBorrow(),
                paper.notionalPerLegPct(),
                paper.slippageBpsDaily(),
                paper.slippageBpsIntraday()
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

    private FinalTradeRecommendation toFinal(TradingRecommendation tech, BookKind book) {
        LocalDateTime at = tech.asOfDate().atTime(book == BookKind.INTRADAY ? 12 : 19, 5);
        if (book == BookKind.INTRADAY
                && eventCalendarRiskService != null
                && eventCalendarRiskService.shouldBlockNewEntry(tech, at)) {
            var news = new PairNewsAssessment(NewsRiskLevel.BLOCK, true, "event window", List.of(), 0);
            return new FinalTradeRecommendation(tech, news, FinalTradeDecision.BLOCK, "BLOCK event", "replay");
        }
        boolean actionable = tech.signal() == TradingSignal.LONG_SPREAD
                || tech.signal() == TradingSignal.SHORT_SPREAD;
        FinalTradeDecision decision = actionable ? FinalTradeDecision.ENTER : FinalTradeDecision.WATCH;
        var news = new PairNewsAssessment(NewsRiskLevel.LOW, false, "replay", List.of(), 0);
        return new FinalTradeRecommendation(tech, news, decision, decision.name(), "replay");
    }

    private PairLookupService.AlignedCandles alignFromStorage(String y, String x) throws IOException {
        List<Candle> rawY = storage.loadCandles(y).stream().sorted(Comparator.comparing(Candle::date)).toList();
        List<Candle> rawX = storage.loadCandles(x).stream().sorted(Comparator.comparing(Candle::date)).toList();
        Map<LocalDate, Candle> yMap = new HashMap<>();
        for (Candle c : rawY) {
            yMap.put(c.date(), c);
        }
        Map<LocalDate, Candle> xMap = new HashMap<>();
        for (Candle c : rawX) {
            xMap.put(c.date(), c);
        }
        List<LocalDate> dates = yMap.keySet().stream().filter(xMap::containsKey).sorted().toList();
        return new PairLookupService.AlignedCandles(
                dates,
                dates.stream().map(yMap::get).toList(),
                dates.stream().map(xMap::get).toList()
        );
    }

    private static List<Integer> barIndices(List<LocalDate> dates, LocalDate from, LocalDate to) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < dates.size(); i++) {
            LocalDate d = dates.get(i);
            if (!d.isBefore(from) && !d.isAfter(to)) {
                out.add(i);
            }
        }
        return out;
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
