package com.moex.cointegration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.PairAnalysisResult;
import com.moex.cointegration.model.SpreadPoint;
import com.moex.cointegration.model.TradingRecommendation;
import com.moex.cointegration.model.TradingSignal;
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
    private static final double MIN_SHARPE_FOR_ENTRY = 0.0;
    private static final double MAX_HALF_LIFE_DAYS = 90.0;
    private static final double MIN_HALF_LIFE_DAYS = 1.0;

    private final ImoexProperties properties;
    private final ObjectMapper objectMapper;
    private final List<TradingRecommendation> lastRecommendations = new CopyOnWriteArrayList<>();

    public TradingRecommendationService(ImoexProperties properties) {
        this.properties = properties;
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
        boolean qualityOk = isTradeQualityOk(pair);

        TradingSignal signal;
        String summary;
        String details;

        if (!qualityOk) {
            signal = TradingSignal.NO_SIGNAL;
            summary = "Не торговать — пара не прошла фильтры качества";
            details = beginnerSkip(pair);
        } else if (z <= -zEntry) {
            signal = TradingSignal.LONG_SPREAD;
            summary = String.format(
                    "КУПИТЬ спред: купите %s и одновременно продайте (шорт) %s",
                    pair.tickerY(), pair.tickerX());
            details = beginnerLong(pair, z, date, zEntry, zExit);
        } else if (z >= zEntry) {
            signal = TradingSignal.SHORT_SPREAD;
            summary = String.format(
                    "ПРОДАТЬ спред: продайте (шорт) %s и одновременно купите %s",
                    pair.tickerY(), pair.tickerX());
            details = beginnerShort(pair, z, date, zEntry, zExit);
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
        return String.format("""
                Что происходит простыми словами
                Акции %s и %s обычно движутся «вместе». Сейчас %s выглядит слишком дешёвой относительно %s \
                (Z-score = %.2f на дату %s, порог входа = −%.1f). Исторически такой разрыв часто сужается.

                Что сделать на счёте (парная сделка)
                1) Купить 100 акций %s (или 1 лот — как удобнее по размеру депозита).
                2) Одновременно продать (шорт) примерно %.0f акций %s \
                (коэффициент хеджа beta ≈ %.3f: на 1 рубль %s нужно ≈ %.3f рубля %s).
                3) Обе ноги открывать в один день, чтобы ставка была на схождение, а не на рынок в целом.

                Когда выходить
                Закрыть обе ноги, когда Z-score вернётся к ≈ %.1f (спред «нормализовался»). \
                Ожидаемое время возврата (half-life) ≈ %.0f торговых дней — это ориентир, не гарантия.

                Почему сигнал выглядит качественным
                • p-value коинтеграции = %.4f (ниже %.2f — связь статистически подтверждена).
                • Sharpe бэктеста = %.2f (чем выше, тем стабильнее была стратегия на истории).
                • Max drawdown в симуляции смотрите на дашборде / в отчёте.

                Риски для новичка
                Коинтеграция может «сломаться» (одна компания уходит в новости/делистинг). \
                Держите небольшой размер позиции, не усредняйте без плана, всегда закрывайте ОБЕ ноги.
                """,
                pair.tickerY(), pair.tickerX(),
                pair.tickerY(), pair.tickerX(),
                z, date, zEntry,
                pair.tickerY(),
                beta * 100.0, pair.tickerX(),
                pair.hedgeRatio(), pair.tickerY(), beta, pair.tickerX(),
                zExit,
                pair.halfLifeDays(),
                pair.pValue(), properties.cointegration().pValueThreshold(),
                pair.sharpeRatio()
        ).trim();
    }

    private String beginnerShort(PairAnalysisResult pair, double z, LocalDate date, double zEntry, double zExit) {
        double beta = Math.abs(pair.hedgeRatio());
        return String.format("""
                Что происходит простыми словами
                Акции %s и %s обычно движутся «вместе». Сейчас %s выглядит слишком дорогой относительно %s \
                (Z-score = %.2f на дату %s, порог входа = +%.1f). Ставка — на сужение спреда.

                Что сделать на счёте (парная сделка)
                1) Продать (шорт) 100 акций %s.
                2) Одновременно купить примерно %.0f акций %s (beta ≈ %.3f).
                3) Открывать обе ноги вместе; закрывать тоже вместе.

                Когда выходить
                Закрыть обе позиции, когда Z вернётся к ≈ %.1f. \
                Ориентир по времени возврата (half-life) ≈ %.0f торговых дней.

                Почему сигнал выглядит качественным
                • p-value = %.4f (порог %.2f).
                • Sharpe бэктеста = %.2f.

                Риски для новичка
                Шорт дороже по комиссиям/заёмной ставке; коинтеграция может исчезнуть. \
                Не ставьте размер, который «болит» при просадке 10–20%% по паре.
                """,
                pair.tickerY(), pair.tickerX(),
                pair.tickerY(), pair.tickerX(),
                z, date, zEntry,
                pair.tickerY(),
                beta * 100.0, pair.tickerX(), pair.hedgeRatio(),
                zExit,
                pair.halfLifeDays(),
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
        return "Пара отфильтрована: " + qualityReason(pair)
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

    private boolean isTradeQualityOk(PairAnalysisResult pair) {
        if (pair.sharpeRatio() < MIN_SHARPE_FOR_ENTRY) {
            return false;
        }
        if (Double.isNaN(pair.halfLifeDays())) {
            return false;
        }
        return pair.halfLifeDays() >= MIN_HALF_LIFE_DAYS && pair.halfLifeDays() <= MAX_HALF_LIFE_DAYS;
    }

    private String qualityReason(PairAnalysisResult pair) {
        if (pair.sharpeRatio() < MIN_SHARPE_FOR_ENTRY) {
            return String.format("Sharpe=%.2f < %.1f", pair.sharpeRatio(), MIN_SHARPE_FOR_ENTRY);
        }
        if (Double.isNaN(pair.halfLifeDays())) {
            return "half-life не определён (спред не mean-reverting)";
        }
        if (pair.halfLifeDays() > MAX_HALF_LIFE_DAYS) {
            return String.format("half-life=%.1f дней — слишком медленный возврат", pair.halfLifeDays());
        }
        if (pair.halfLifeDays() < MIN_HALF_LIFE_DAYS) {
            return String.format("half-life=%.2f — подозрительно быстрый (шум)", pair.halfLifeDays());
        }
        return "неизвестная причина";
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
