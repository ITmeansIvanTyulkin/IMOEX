package com.moex.cointegration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.BookKind;
import com.moex.cointegration.model.PairAnalysisResult;
import com.moex.cointegration.model.SpreadPoint;
import com.moex.cointegration.model.TradingRecommendation;
import com.moex.cointegration.model.TradingSignal;
import com.moex.cointegration.quant.SignalRules;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Формирует список торговых рекомендаций из результатов коинтеграционного анализа
 * и выводит их в консоль.
 */
@Service
public class TradingRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(TradingRecommendationService.class);
    private static final double WATCH_Z_THRESHOLD = 1.5;

    private final ImoexProperties properties;
    private final RiskPolicyService riskPolicyService;
    private final MicrostructureExecutionService microstructureExecutionService;
    private final ObjectMapper objectMapper;
    private final List<TradingRecommendation> lastRecommendations = new CopyOnWriteArrayList<>();
    private final List<TradingRecommendation> lastIntradayRecommendations = new CopyOnWriteArrayList<>();
    private final ThreadLocal<QualityCtx> qualityCtx = ThreadLocal.withInitial(QualityCtx::daily);

    private record QualityCtx(double barsPerDay, Double minHlDays, Double tradeMaxHlDays, BookKind book) {
        static QualityCtx daily() {
            return new QualityCtx(1.0, null, null, BookKind.DAILY);
        }
    }

    @Autowired
    public TradingRecommendationService(
            ImoexProperties properties,
            RiskPolicyService riskPolicyService,
            MicrostructureExecutionService microstructureExecutionService
    ) {
        this.properties = properties;
        this.riskPolicyService = riskPolicyService;
        this.microstructureExecutionService = microstructureExecutionService;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /** Тесты без microstructure gate. */
    public TradingRecommendationService(ImoexProperties properties, RiskPolicyService riskPolicyService) {
        this(properties, riskPolicyService, null);
    }

    /** Подгружает сохранённые рекомендации после рестарта приложения. */
    @PostConstruct
    void loadFromDisk() {
        loadRecommendationsFile(recommendationsFile(), lastRecommendations);
        loadRecommendationsFile(
                Path.of(properties.dataDir(), "trading-recommendations-intraday.json"),
                lastIntradayRecommendations
        );
    }

    private void loadRecommendationsFile(Path file, List<TradingRecommendation> target) {
        if (!Files.exists(file)) {
            return;
        }
        try {
            TradingRecommendation[] loaded = objectMapper.readValue(file.toFile(), TradingRecommendation[].class);
            target.clear();
            target.addAll(List.of(loaded));
            log.info("Loaded {} trading recommendations from {}", loaded.length, file);
        } catch (Exception ex) {
            log.warn("Could not load recommendations from {}: {}", file, ex.getMessage());
        }
    }

    public List<TradingRecommendation> analyzeAndPrint(List<PairAnalysisResult> cointegratedPairs)
            throws IOException {
        return analyzeAndPrint(cointegratedPairs, BookKind.DAILY, 1.0, null, null);
    }

    public List<TradingRecommendation> analyzeAndPrint(
            List<PairAnalysisResult> cointegratedPairs,
            BookKind book,
            double barsPerDay,
            Double minHalfLifeDays,
            Double tradeMaxHalfLifeDays
    ) throws IOException {
        qualityCtx.set(new QualityCtx(barsPerDay, minHalfLifeDays, tradeMaxHalfLifeDays, book));
        try {
            List<TradingRecommendation> recommendations = new ArrayList<>();
            for (PairAnalysisResult pair : cointegratedPairs) {
                recommendations.add(buildRecommendation(pair));
            }
            recommendations.sort(recommendationPriority());

            if (book == BookKind.INTRADAY) {
                lastIntradayRecommendations.clear();
                lastIntradayRecommendations.addAll(recommendations);
                saveToFile(recommendations, "trading-recommendations-intraday.json");
            } else {
                lastRecommendations.clear();
                lastRecommendations.addAll(recommendations);
                saveToFile(recommendations, "trading-recommendations.json");
            }
            printToConsole(recommendations, cointegratedPairs.size());
            return List.copyOf(recommendations);
        } finally {
            qualityCtx.set(QualityCtx.daily());
        }
    }

    public List<TradingRecommendation> getLastIntradayRecommendations() {
        return List.copyOf(lastIntradayRecommendations);
    }

    public List<TradingRecommendation> getLastRecommendations() {
        return List.copyOf(lastRecommendations);
    }

    public List<TradingRecommendation> getActionableSignals() {
        return lastRecommendations.stream()
                .filter(r -> r.signal() == TradingSignal.LONG_SPREAD || r.signal() == TradingSignal.SHORT_SPREAD)
                .toList();
    }

    public Optional<TradingRecommendation> findForPair(String tickerY, String tickerX) {
        return lastRecommendations.stream()
                .filter(r -> r.tickerY().equalsIgnoreCase(tickerY) && r.tickerX().equalsIgnoreCase(tickerX))
                .findFirst();
    }

    private TradingRecommendation buildRecommendation(PairAnalysisResult pair) {
        SpreadPoint lastZ = lastPoint(pair.zScoreSeries());
        SpreadPoint lastSpread = lastPoint(pair.spreadSeries());

        double z = lastZ.value();
        LocalDate date = lastZ.date();
        double spread = lastSpread.value();

        double zEntry = properties.cointegration().zScoreEntry();
        double zExit = properties.cointegration().zScoreExit();
        boolean qualityOk;
        QualityCtx ctx = qualityCtx.get();
        if (ctx == null || ctx.barsPerDay() <= 1.0000001 && ctx.minHlDays() == null) {
            qualityOk = riskPolicyService.passesQualityFilters(pair);
        } else {
            qualityOk = riskPolicyService.passesQualityFilters(
                    pair, ctx.barsPerDay(), ctx.minHlDays(), ctx.tradeMaxHlDays());
        }
        boolean reversal = properties.cointegration().entryReversalRequired();

        double zPrev = Double.NaN;
        List<SpreadPoint> zSeries = pair.zScoreSeries();
        if (zSeries.size() >= 2) {
            zPrev = zSeries.get(zSeries.size() - 2).value();
        }

        TradingSignal signal;
        String summary;
        String details;

        if (Double.isNaN(z)) {
            signal = TradingSignal.NO_SIGNAL;
            summary = "Не торговать — rolling Z ещё не прогрет";
            details = "Недостаточно баров для окна rolling Z. Дождитесь накопления истории.";
        } else if (!qualityOk) {
            signal = TradingSignal.NO_SIGNAL;
            summary = "Не торговать — пара не прошла фильтры качества";
            details = beginnerSkip(pair);
        } else if (riskPolicyService.regimeBlocksEntries()) {
            signal = TradingSignal.WATCH;
            summary = "Не торговать! Выявлен тренд — стратегия только боковик";
            details = riskPolicyService.regime().detail()
                    + "\nTRINITY торгует mean-reversion только в боковике. При сильном тренде индекса "
                    + "новые входы блокируются: спред может «уехать» вместе с рынком и не вернуться."
                    + "\nПара " + pair.tickerY() + "/" + pair.tickerX()
                    + " формально могла пройти по качеству/Z, но вход запрещён режимным фильтром.";
        } else if (riskPolicyService.structuralBreak(pair)) {
            signal = TradingSignal.WATCH;
            summary = "WATCH — CUSUM structural break на спреде";
            details = "CUSUM на Z указывает на структурный сдвиг. Не входим, пока спред не стабилизируется. "
                    + "Пара " + pair.tickerY() + "/" + pair.tickerX() + ".";
        } else if (SignalRules.confirmLongEntry(zPrev, z, zEntry, reversal)) {
            signal = TradingSignal.LONG_SPREAD;
            summary = String.format(
                    "КУПИТЬ спред: купите %s и одновременно продайте (шорт) %s",
                    pair.tickerY(), pair.tickerX());
            details = beginnerLong(pair, z, date, zEntry, zExit);
        } else if (SignalRules.confirmShortEntry(zPrev, z, zEntry, reversal)) {
            signal = TradingSignal.SHORT_SPREAD;
            summary = String.format(
                    "ПРОДАТЬ спред: продайте (шорт) %s и одновременно купите %s",
                    pair.tickerY(), pair.tickerX());
            details = beginnerShort(pair, z, date, zEntry, zExit);
        } else if (!Double.isNaN(z) && Math.abs(z) >= zEntry) {
            signal = TradingSignal.WATCH;
            summary = String.format(
                    "Ждём разворот: |Z|=%.2f уже за порогом ±%.1f, но подтверждения разворота ещё нет",
                    Math.abs(z), zEntry);
            details = String.format("""
                    Z = %.2f на %s. При режиме require-entry-reversal вход только когда Z \
                    уже за ±%.1f и начинает идти к нулю. Сейчас спред ещё расширяется или стоит — не входим.
                    Пара %s/%s, half-life≈%.0f дн., Sharpe=%.2f.
                    """, z, date, zEntry, pair.tickerY(), pair.tickerX(),
                    pair.halfLifeDays(), pair.sharpeRatio()).trim();
        } else if (Math.abs(z) >= WATCH_Z_THRESHOLD) {
            signal = TradingSignal.WATCH;
            summary = String.format(
                    "Пока ждать: спред расширяется (Z=%.2f), но порог входа ±%.1f ещё не достигнут",
                    z, zEntry);
            details = beginnerWatch(pair, z, date, zEntry);
        } else if (Math.abs(z) <= zExit + 0.25) {
            signal = TradingSignal.HOLD;
            summary = "Спред у «нормы» — новую сделку открывать не нужно";
            details = beginnerHoldNearZero(pair, z, date, zExit);
        } else {
            signal = TradingSignal.HOLD;
            summary = String.format("Нет сигнала входа: |Z|=%.2f ниже порога %.1f", Math.abs(z), zEntry);
            details = beginnerHold(pair, z, date, zEntry);
        }

        details = appendStaleDataWarning(details, date);
        if (pair.coverageWarning() != null) {
            details = details + String.format(Locale.ROOT,
                    "%n%nData coverage: %.1f%%. %s", pair.coveragePercent(), pair.coverageWarning());
        }

        return applyMicrostructureGate(new TradingRecommendation(
                pair.tickerY(),
                pair.tickerX(),
                signal,
                z,
                date,
                spread,
                pair.hedgeRatio(),
                pair.halfLifeDays(),
                pair.sharpeRatio(),
                pair.pValue(),
                summary,
                details,
                pair.coveragePercent(),
                pair.coverageWarning()
        ));
    }

    private TradingRecommendation applyMicrostructureGate(TradingRecommendation rec) {
        QualityCtx ctx = qualityCtx.get();
        if (microstructureExecutionService == null || ctx == null || ctx.book() != BookKind.INTRADAY) {
            return rec;
        }
        if (rec.signal() != TradingSignal.LONG_SPREAD && rec.signal() != TradingSignal.SHORT_SPREAD) {
            return rec;
        }
        LocalDateTime at = rec.asOfDate().atTime(12, 0);
        MicrostructureExecutionService.MicrostructureVerdict verdict =
                microstructureExecutionService.evaluateEntry(
                        rec.tickerY(), rec.tickerX(), rec.signal(), BookKind.INTRADAY, at);
        if (verdict.allowed()) {
            return rec;
        }
        return new TradingRecommendation(
                rec.tickerY(),
                rec.tickerX(),
                TradingSignal.WATCH,
                rec.currentZScore(),
                rec.asOfDate(),
                rec.currentSpread(),
                rec.hedgeRatio(),
                rec.halfLifeDays(),
                rec.sharpeRatio(),
                rec.pValue(),
                "WATCH — microstructure gate (ATAS proxy)",
                verdict.reason() + "\nZ-сигнал формально есть, но исполнение на 1H не прошло фильтр ликвидности/order-flow.",
                rec.coveragePercent(),
                rec.coverageWarning()
        );
    }

    private String beginnerLong(PairAnalysisResult pair, double z, LocalDate date, double zEntry, double zExit) {
        double beta = Math.abs(pair.hedgeRatio());
        double lastSpread = pair.spreadSeries().isEmpty()
                ? Double.NaN
                : pair.spreadSeries().get(pair.spreadSeries().size() - 1).value();
        TradingRecommendation tmp = new TradingRecommendation(
                pair.tickerY(), pair.tickerX(), TradingSignal.LONG_SPREAD, z, date,
                lastSpread, pair.hedgeRatio(), pair.halfLifeDays(), pair.sharpeRatio(), pair.pValue(), "", "",
                null, null
        );
        double notionalY = riskPolicyService.suggestedNotional(tmp, false);
        double notionalX = notionalY * beta;
        double mult = riskPolicyService.sizeMultiplier(tmp, false);
        return String.format("""
                Что происходит простыми словами
                Акции %s и %s обычно движутся «вместе». Сейчас %s выглядит слишком дешёвой относительно %s \
                (Z-score = %.2f на дату %s, порог входа = −%.1f). Исторически такой разрыв часто сужается.

                Что сделать на счёте (парная сделка)
                1) Купить notional ≈ %.0f ₽ в %s (dynamic size ×%.2f от base; risk / paper).
                2) Одновременно продать (шорт) ≈ %.0f ₽ в %s (beta ≈ %.3f).
                3) Обе ноги в один день. Стоп |Z|≈%.1f или time-stop %d баров.

                Когда выходить
                Закрыть обе ноги при Z ≈ %.1f. Half-life ≈ %.0f торговых дней (ориентир).

                Почему сигнал выглядит качественным
                • p-value = %.4f (порог %.2f).
                • Sharpe бэктеста = %.2f.
                • Rolling Z / FDR / risk stops / dynamic sizing включены в пайплайн.

                Риски
                Коинтеграция может сломаться. Не усредняйте без плана, закрывайте ОБЕ ноги.
                """,
                pair.tickerY(), pair.tickerX(),
                pair.tickerY(), pair.tickerX(),
                z, date, zEntry,
                notionalY, pair.tickerY(), mult,
                notionalX, pair.tickerX(), pair.hedgeRatio(),
                properties.risk().stopZ(), properties.risk().maxHoldBars(),
                zExit, pair.halfLifeDays(),
                pair.pValue(), properties.cointegration().pValueThreshold(),
                pair.sharpeRatio()
        ).trim();
    }

    private String beginnerShort(PairAnalysisResult pair, double z, LocalDate date, double zEntry, double zExit) {
        double beta = Math.abs(pair.hedgeRatio());
        double lastSpread = pair.spreadSeries().isEmpty()
                ? Double.NaN
                : pair.spreadSeries().get(pair.spreadSeries().size() - 1).value();
        TradingRecommendation tmp = new TradingRecommendation(
                pair.tickerY(), pair.tickerX(), TradingSignal.SHORT_SPREAD, z, date,
                lastSpread, pair.hedgeRatio(), pair.halfLifeDays(), pair.sharpeRatio(), pair.pValue(), "", "",
                null, null
        );
        double notionalY = riskPolicyService.suggestedNotional(tmp, false);
        double notionalX = notionalY * beta;
        double mult = riskPolicyService.sizeMultiplier(tmp, false);
        return String.format("""
                Что происходит простыми словами
                Акции %s и %s обычно движутся «вместе». Сейчас %s выглядит слишком дорогой относительно %s \
                (Z-score = %.2f на дату %s, порог входа = +%.1f). Ставка — на сужение спреда.

                Что сделать на счёте (парная сделка)
                1) Продать (шорт) ≈ %.0f ₽ в %s (dynamic size ×%.2f).
                2) Одновременно купить ≈ %.0f ₽ в %s (beta ≈ %.3f).
                3) Стоп |Z|≈%.1f или time-stop %d баров; закрывать ноги вместе.

                Когда выходить
                Закрыть обе позиции при Z ≈ %.1f. Half-life ≈ %.0f дн.

                Почему сигнал выглядит качественным
                • p-value = %.4f (порог %.2f).
                • Sharpe бэктеста = %.2f.

                Риски
                Шорт дороже (borrow); коинтеграция может исчезнуть.
                """,
                pair.tickerY(), pair.tickerX(),
                pair.tickerY(), pair.tickerX(),
                z, date, zEntry,
                notionalY, pair.tickerY(), mult,
                notionalX, pair.tickerX(), pair.hedgeRatio(),
                properties.risk().stopZ(), properties.risk().maxHoldBars(),
                zExit, pair.halfLifeDays(),
                pair.pValue(), properties.cointegration().pValueThreshold(),
                pair.sharpeRatio()
        ).trim();
    }

    private String beginnerWatch(PairAnalysisResult pair, double z, LocalDate date, double zEntry) {
        return String.format("""
                Пока не входить. Z-score = %.2f (дата %s) уже заметно отклонился от нуля, \
                но порог входа ±%.1f ещё не пробит.

                Что делать: поставить алерт на |Z| ≥ %.1f по паре %s/%s и открыть график. \
                Half-life ≈ %.0f дн., Sharpe истории = %.2f — пара интересна для наблюдения.
                """,
                z, date, zEntry, zEntry, pair.tickerY(), pair.tickerX(),
                pair.halfLifeDays(), pair.sharpeRatio()
        ).trim();
    }

    private String beginnerHoldNearZero(PairAnalysisResult pair, double z, LocalDate date, double zExit) {
        return String.format("""
                Спред около равновесия (Z=%.2f на %s, зона выхода ≈ %.1f). \
                Новую парную сделку открывать не нужно. Если позиция уже была — это зона фиксации.
                Пара: %s/%s, Sharpe=%.2f, half-life≈%.0f дн.
                """,
                z, date, zExit, pair.tickerY(), pair.tickerX(),
                pair.sharpeRatio(), pair.halfLifeDays()
        ).trim();
    }

    private String beginnerHold(PairAnalysisResult pair, double z, LocalDate date, double zEntry) {
        return String.format("""
                Отклонение недостаточное для входа: |Z|=%.2f на %s, нужно ≥ %.1f. \
                Следите за парой %s/%s на графике Z-score; вход — только после пробоя уровня.
                """,
                Math.abs(z), date, zEntry, pair.tickerY(), pair.tickerX()
        ).trim();
    }

    private String beginnerSkip(PairAnalysisResult pair) {
        QualityCtx ctx = qualityCtx.get();
        String reason = ctx == null
                ? riskPolicyService.qualityRejectReason(pair)
                : riskPolicyService.qualityRejectReason(
                pair, ctx.barsPerDay(), ctx.minHlDays(), ctx.tradeMaxHlDays());
        return "Пара отфильтрована: " + reason
                + ". Для новичка это значит: даже при «красивом» Z лучше пропустить — "
                + "история возврата спреда выглядит слабой или слишком шумной.";
    }

    private String appendStaleDataWarning(String details, LocalDate asOfDate) {
        if (asOfDate == null || !asOfDate.isBefore(LocalDate.now().minusDays(14))) {
            return details;
        }
        return details + "\n\nВнимание: последняя общая свеча пары — " + asOfDate
                + ". Один из тикеров мог уйти с торгов (делистинг). Сигнал может быть устаревшим — "
                + "проверьте, что обе бумаги реально торгуются на MOEX сегодня.";
    }

    private SpreadPoint lastPoint(List<SpreadPoint> series) {
        if (series == null || series.isEmpty()) {
            throw new IllegalStateException("Price series is empty");
        }
        return series.get(series.size() - 1);
    }

    private Comparator<TradingRecommendation> recommendationPriority() {
        return Comparator
                .comparingInt((TradingRecommendation r) -> signalRank(r.signal()))
                .thenComparing(r -> Math.abs(r.currentZScore()), Comparator.reverseOrder())
                .thenComparing(TradingRecommendation::sharpeRatio, Comparator.reverseOrder());
    }

    private int signalRank(TradingSignal signal) {
        return switch (signal) {
            case LONG_SPREAD, SHORT_SPREAD -> 0;
            case WATCH -> 1;
            case HOLD -> 2;
            case NO_SIGNAL -> 3;
        };
    }

    private void printToConsole(List<TradingRecommendation> recommendations, int totalCointegrated) {
        long entries = recommendations.stream()
                .filter(r -> r.signal() == TradingSignal.LONG_SPREAD || r.signal() == TradingSignal.SHORT_SPREAD)
                .count();
        long watch = recommendations.stream().filter(r -> r.signal() == TradingSignal.WATCH).count();

        log.info("");
        log.info("=".repeat(72));
        log.info("  ТОРГОВЫЕ РЕКОМЕНДАЦИИ  |  дата анализа: {}", LocalDate.now());
        log.info("  Коинтегрированных пар: {}  |  сигналов входа: {}  |  watch: {}",
                totalCointegrated, entries, watch);
        log.info("=".repeat(72));

        for (TradingRecommendation r : recommendations) {
            if (r.signal() == TradingSignal.NO_SIGNAL && r.sharpeRatio() < 0) {
                continue;
            }
            log.info("[{}] {} / {}  |  Z={}  |  дата={}  |  beta={}  |  HL={}d  |  Sharpe={}",
                    signalLabel(r.signal()),
                    r.tickerY(),
                    r.tickerX(),
                    formatZ(r.currentZScore()),
                    r.asOfDate(),
                    formatNum(r.hedgeRatio()),
                    formatNum(r.halfLifeDays()),
                    formatNum(r.sharpeRatio()));
            log.info("       {}", r.summary());
            for (String line : r.details().split("\n")) {
                if (!line.isBlank()) {
                    log.info("       {}", line);
                }
            }
        }

        if (entries == 0) {
            log.info("-".repeat(72));
            log.info("  Нет пар с |Z| >= {} и прохождением фильтров качества.", properties.cointegration().zScoreEntry());
        }

        log.info("=".repeat(72));
        log.info("");
    }

    private String signalLabel(TradingSignal signal) {
        return switch (signal) {
            case LONG_SPREAD -> "LONG ";
            case SHORT_SPREAD -> "SHORT";
            case WATCH -> "WATCH";
            case HOLD -> "HOLD ";
            case NO_SIGNAL -> "SKIP ";
        };
    }

    private String formatZ(double z) {
        return z >= 0 ? String.format("+%.2f", z) : String.format("%.2f", z);
    }

    private String formatNum(double v) {
        return Double.isNaN(v) ? "n/a" : String.format("%.2f", v);
    }

    private void saveToFile(List<TradingRecommendation> recommendations) throws IOException {
        saveToFile(recommendations, "trading-recommendations.json");
    }

    private void saveToFile(List<TradingRecommendation> recommendations, String fileName) throws IOException {
        Path file = Path.of(properties.dataDir(), fileName);
        Files.createDirectories(file.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), recommendations);
    }

    private Path recommendationsFile() {
        return Path.of(properties.dataDir(), "trading-recommendations.json");
    }
}
