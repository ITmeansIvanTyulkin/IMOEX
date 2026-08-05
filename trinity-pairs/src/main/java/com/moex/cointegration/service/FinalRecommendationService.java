package com.moex.cointegration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.BookKind;
import com.moex.cointegration.model.FinalTradeDecision;
import com.moex.cointegration.model.FinalTradeRecommendation;
import com.moex.cointegration.model.TradingRecommendation;
import com.moex.cointegration.model.TradingSignal;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Хранит итоговые рекомендации (техника + фундамент/новости) и пишет их на диск.
 * Полный новостной проход — только для LONG/SHORT/WATCH в режиме DAILY (multi-day).
 * Paper sync вызывается только после этого шага.
 */
@Service
public class FinalRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(FinalRecommendationService.class);

    private final NewsRiskAnalysisService newsRiskAnalysisService;
    private final ImoexProperties properties;
    private final ObjectMapper objectMapper;
    private final List<FinalTradeRecommendation> lastFinal = new CopyOnWriteArrayList<>();
    private final List<FinalTradeRecommendation> lastIntradayFinal = new CopyOnWriteArrayList<>();

    public FinalRecommendationService(
            NewsRiskAnalysisService newsRiskAnalysisService,
            ImoexProperties properties
    ) {
        this.newsRiskAnalysisService = newsRiskAnalysisService;
        this.properties = properties;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @PostConstruct
    void loadFromDisk() {
        Path file = finalFile();
        if (!Files.exists(file)) {
            return;
        }
        try {
            FinalTradeRecommendation[] loaded = objectMapper.readValue(file.toFile(), FinalTradeRecommendation[].class);
            lastFinal.clear();
            lastFinal.addAll(List.of(loaded));
            log.info("Loaded {} final recommendations from {}", loaded.length, file);
        } catch (Exception ex) {
            log.warn("Could not load final recommendations: {}", ex.getMessage());
        }
    }

    public List<FinalTradeRecommendation> rebuildFromTechnical(List<TradingRecommendation> technical)
            throws IOException {
        return rebuildFromTechnical(technical, BookKind.DAILY);
    }

    public List<FinalTradeRecommendation> rebuildFromTechnical(
            List<TradingRecommendation> technical,
            BookKind book
    ) throws IOException {
        List<TradingRecommendation> forNews = technical.stream()
                .filter(r -> r.signal() == TradingSignal.LONG_SPREAD
                        || r.signal() == TradingSignal.SHORT_SPREAD
                        || r.signal() == TradingSignal.WATCH)
                .toList();

        List<FinalTradeRecommendation> analyzed = new ArrayList<>(
                newsRiskAnalysisService.analyze(forNews, book));

        analyzed.sort(Comparator
                .comparingInt((FinalTradeRecommendation f) -> decisionRank(f.decision()))
                .thenComparing(f -> Math.abs(f.technical().currentZScore()), Comparator.reverseOrder()));

        if (book == BookKind.INTRADAY) {
            lastIntradayFinal.clear();
            lastIntradayFinal.addAll(analyzed);
            save(analyzed, "final-recommendations-intraday.json");
        } else {
            lastFinal.clear();
            lastFinal.addAll(analyzed);
            save(analyzed, "final-recommendations.json");
        }
        printSummary(analyzed);
        return List.copyOf(analyzed);
    }

    public List<FinalTradeRecommendation> getLastIntradayFinal() {
        return List.copyOf(lastIntradayFinal);
    }

    /** Ручной пересчёт новостей по уже сохранённым техническим рекомендациям. */
    public List<FinalTradeRecommendation> reanalyzeExisting(List<TradingRecommendation> technical)
            throws IOException {
        return rebuildFromTechnical(technical);
    }

    public List<FinalTradeRecommendation> getLastFinal() {
        return List.copyOf(lastFinal);
    }

    public List<FinalTradeRecommendation> getActionableFinal() {
        return lastFinal.stream()
                .filter(f -> f.decision() == FinalTradeDecision.ENTER
                        || f.decision() == FinalTradeDecision.REDUCE_SIZE)
                .toList();
    }

    private void save(List<FinalTradeRecommendation> rows) throws IOException {
        save(rows, "final-recommendations.json");
    }

    private void save(List<FinalTradeRecommendation> rows, String fileName) throws IOException {
        Path file = Path.of(properties.dataDir(), fileName);
        Files.createDirectories(file.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), rows);
    }

    private Path finalFile() {
        return Path.of(properties.dataDir(), "final-recommendations.json");
    }

    private int decisionRank(FinalTradeDecision decision) {
        return switch (decision) {
            case ENTER -> 0;
            case REDUCE_SIZE -> 1;
            case WATCH -> 2;
            case BLOCK -> 3;
        };
    }

    private void printSummary(List<FinalTradeRecommendation> rows) {
        log.info("");
        log.info("=".repeat(72));
        log.info("  ИТОГОВАЯ ТАБЛИЦА (техника + новости, горизонт {} дн.)",
                properties.news() != null ? properties.news().lookbackDays() : 0);
        log.info("=".repeat(72));
        for (FinalTradeRecommendation f : rows) {
            log.info("[{}] {}/{} | tech={} Z={} | news={} asym={} | {}",
                    f.decision(),
                    f.tickerY(),
                    f.tickerX(),
                    f.technical().signal(),
                    String.format("%.2f", f.technical().currentZScore()),
                    f.news().riskLevel(),
                    f.news().asymmetric(),
                    f.decisionSummary());
        }
        log.info("=".repeat(72));
        log.info("");
    }
}
