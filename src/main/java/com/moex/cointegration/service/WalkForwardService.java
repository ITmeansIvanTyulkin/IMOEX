package com.moex.cointegration.service;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.AlignedPairData;
import com.moex.cointegration.model.PriceSeries;
import com.moex.cointegration.model.WalkForwardReport;
import com.moex.cointegration.quant.WalkForwardAnalyzer;
import com.moex.cointegration.storage.MarketDataStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Запускает walk-forward по коинтегрированным / топ кандидатам и сохраняет отчёт.
 */
@Service
public class WalkForwardService {

    private static final Logger log = LoggerFactory.getLogger(WalkForwardService.class);

    private final MarketDataService marketDataService;
    private final PreprocessingService preprocessingService;
    private final MarketDataStorage storage;
    private final ImoexProperties properties;
    private final AtomicReference<WalkForwardReport> lastReport = new AtomicReference<>();

    public WalkForwardService(
            MarketDataService marketDataService,
            PreprocessingService preprocessingService,
            MarketDataStorage storage,
            ImoexProperties properties
    ) {
        this.marketDataService = marketDataService;
        this.preprocessingService = preprocessingService;
        this.storage = storage;
        this.properties = properties;
    }

    public Optional<WalkForwardReport> getLastReport() {
        return Optional.ofNullable(lastReport.get());
    }

    public WalkForwardReport runForTopPairs(int maxPairs) throws IOException {
        if (!properties.walkForward().enabled()) {
            WalkForwardReport empty = new WalkForwardReport(LocalDate.now(), 0, 0, 0.0, List.of());
            lastReport.set(empty);
            return empty;
        }

        Map<String, PriceSeries> loaded = marketDataService.loadAlignedPriceSeries();
        Map<String, PriceSeries> processed = preprocessingService.preprocess(loaded);

        List<String[]> pairKeys = storage.loadReport()
                .map(r -> r.topPairs().stream()
                        .limit(maxPairs)
                        .map(p -> new String[]{p.tickerY(), p.tickerX()})
                        .toList())
                .orElseGet(() -> samplePairs(processed, maxPairs));

        ImoexProperties.WalkForwardProperties wf = properties.walkForward();
        ImoexProperties.RiskProperties risk = properties.risk();
        ImoexProperties.CointegrationProperties coint = properties.cointegration();

        List<WalkForwardReport.PairWalkForward> results = new ArrayList<>();
        for (String[] key : pairKeys) {
            PriceSeries y = processed.get(key[0]);
            PriceSeries x = processed.get(key[1]);
            if (y == null || x == null) {
                continue;
            }
            Optional<AlignedPairData> aligned = preprocessingService.alignPair(y, x);
            if (aligned.isEmpty()) {
                continue;
            }
            AlignedPairData data = aligned.get();
            WalkForwardAnalyzer.Summary summary = WalkForwardAnalyzer.evaluate(
                    data.logY(),
                    data.logX(),
                    coint.pValueThreshold(),
                    properties.commissionRate(),
                    coint.zScoreEntry(),
                    coint.zScoreExit(),
                    coint.rollingZWindow(),
                    risk.stopZ(),
                    risk.maxHoldBars(),
                    wf.trainBars(),
                    wf.testBars(),
                    wf.stepBars(),
                    coint.kalmanEnabled(),
                    coint.kalmanDelta(),
                    coint.kalmanVe(),
                    risk.borrowRateAnnual(),
                    coint.entryReversalRequired()
            );
            results.add(new WalkForwardReport.PairWalkForward(key[0], key[1], summary));
        }

        long positive = results.stream()
                .filter(p -> p.summary().medianOosSharpe() > 0)
                .count();
        double meanMedian = results.stream()
                .mapToDouble(p -> p.summary().medianOosSharpe())
                .average()
                .orElse(0.0);

        WalkForwardReport report = new WalkForwardReport(
                LocalDate.now(),
                results.size(),
                (int) positive,
                meanMedian,
                results.stream()
                        .sorted(Comparator.comparingDouble((WalkForwardReport.PairWalkForward p) ->
                                p.summary().medianOosSharpe()).reversed())
                        .toList()
        );
        lastReport.set(report);
        storage.saveWalkForwardReport(report);
        log.info("Walk-forward done: {} pairs, {} with median OOS Sharpe>0, meanMedian={}",
                report.pairsEvaluated(), report.pairsWithPositiveMedianOosSharpe(), report.meanMedianOosSharpe());
        return report;
    }

    private List<String[]> samplePairs(Map<String, PriceSeries> processed, int maxPairs) {
        List<String> tickers = processed.keySet().stream().sorted().toList();
        List<String[]> pairs = new ArrayList<>();
        for (int i = 0; i < tickers.size() && pairs.size() < maxPairs; i++) {
            for (int j = i + 1; j < tickers.size() && pairs.size() < maxPairs; j++) {
                pairs.add(new String[]{tickers.get(i), tickers.get(j)});
            }
        }
        return pairs;
    }
}
