package com.moex.cointegration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.PairAnalysisResult;
import com.moex.cointegration.model.SpreadPoint;
import com.moex.cointegration.model.TradingRecommendation;
import com.moex.cointegration.model.TradingSignal;
import com.moex.cointegration.quant.SignalRules;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final ObjectMapper objectMapper;
    private final List<TradingRecommendation> lastRecommendations = new CopyOnWriteArrayList<>();

    public TradingRecommendationService(ImoexProperties properties, RiskPolicyService riskPolicyService) {
        this.properties = properties;
        this.riskPolicyService = riskPolicyService;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /** Подгружает сохранённые рекомендации после рестарта приложения. */
    @PostConstruct
    void loadFromDisk() {
        Path file = recommendationsFile();
        if (!Files.exists(file)) {
            return;
        }
        try {
            TradingRecommendation[] loaded = objectMapper.readValue(file.toFile(), TradingRecommendation[].class);
            lastRecommendations.clear();
            lastRecommendations.addAll(List.of(loaded));
            log.info("Loaded {} trading recommendations from {}", loaded.length, file);
        } catch (Exception ex) {
            log.warn("Could not load recommendations from {}: {}", file, ex.getMessage());
        }
    }

    public List<TradingRecommendation> analyzeAndPrint(List<PairAnalysisResult> cointegratedPairs)
            throws IOException {
        List<TradingRecommendation> recommendations = new ArrayList<>();

        for (PairAnalysisResult pair : cointegratedPairs) {
            recommendations.add(buildRecommendation(pair));
        }

        recommendations.sort(recommendationPriority());

        lastRecommendations.clear();
        lastRecommendations.addAll(recommendations);

        saveToFile(recommendations);
        printToConsole(recommendations, cointegratedPairs.size());

        return List.copyOf(recommendations);
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
        boolean qualityOk = riskPolicyService.passesQualityFilters(pair);
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

        return new TradingRecommendation(
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
                details
        );
    }

    private String beginnerLong(PairAnalysisResult pair, double z, LocalDate date, double zEntry, double zExit) {
        double beta = Math.abs(pair.hedgeRatio());
        double lastSpread = pair.spreadSeries().isEmpty()
                ? Double.NaN
                : pair.spreadSeries().get(pair.spreadSeries().size() - 1).value();
        TradingRecommendation tmp = new TradingRecommendation(
                pair.tickerY(), pair.tickerX(), TradingSignal.LONG_SPREAD, z, date,
                lastSpread, pair.hedgeRatio(), pair.halfLifeDays(), pair.sharpeRatio(), pair.pValue(), "", ""
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
                lastSpread, pair.hedgeRatio(), pair.halfLifeDays(), pair.sharpeRatio(), pair.pValue(), "", ""
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
        return "Пара отфильтрована: " + riskPolicyService.qualityRejectReason(pair)
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
        Path file = recommendationsFile();
        Files.createDirectories(file.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), recommendations);
    }

    private Path recommendationsFile() {
        return Path.of(properties.dataDir(), "trading-recommendations.json");
    }
}
