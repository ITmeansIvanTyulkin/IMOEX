package com.moex.cointegration.service;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.AlignedPairData;
import com.moex.cointegration.model.BookKind;
import com.moex.cointegration.model.Candle;
import com.moex.cointegration.model.EngleGrangerResult;
import com.moex.cointegration.model.PairAnalysisResult;
import com.moex.cointegration.model.PairCoverage;
import com.moex.cointegration.model.PriceSeries;
import com.moex.cointegration.model.TradingMetrics;
import com.moex.cointegration.quant.EngleGrangerTest;
import com.moex.cointegration.quant.KalmanHedgeFilter;
import com.moex.cointegration.quant.SpreadAnalytics;
import com.moex.cointegration.quant.WalkForwardAnalyzer;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * FDR-сканирование коинтегрированных пар по обработанному юниверсу (как в live-пайплайне).
 */
@Service
public class PairUniverseScanService {

    private final ImoexProperties properties;
    private final PreprocessingService preprocessingService;
    private final UniverseFilterService universeFilterService;

    public PairUniverseScanService(
            ImoexProperties properties,
            PreprocessingService preprocessingService,
            UniverseFilterService universeFilterService
    ) {
        this.properties = properties;
        this.preprocessingService = preprocessingService;
        this.universeFilterService = universeFilterService;
    }

    public List<PairAnalysisResult> scan(
            Map<String, PriceSeries> processed,
            Map<String, List<Candle>> candlesByTicker,
            PairScanParams params
    ) throws IOException {
        List<String> tickers = processed.keySet().stream().sorted().toList();
        List<Candidate> candidates = new ArrayList<>();
        int pairsTested = 0;
        int pairsSkipped = 0;

        ImoexProperties.CointegrationProperties coint = properties.cointegration();

        for (int i = 0; i < tickers.size(); i++) {
            for (int j = i + 1; j < tickers.size(); j++) {
                String tickerY = tickers.get(i);
                String tickerX = tickers.get(j);

                Optional<AlignedPairData> aligned = preprocessingService.alignPair(
                        processed.get(tickerY), processed.get(tickerX));
                if (aligned.isEmpty()) {
                    pairsSkipped++;
                    continue;
                }
                if (!universeFilterService.allowPair(tickerY, tickerX, candlesByTicker, params.book())) {
                    pairsSkipped++;
                    continue;
                }

                pairsTested++;
                EngleGrangerResult eg = EngleGrangerTest.test(
                        tickerY,
                        tickerX,
                        aligned.get().logY(),
                        aligned.get().logX(),
                        coint.pValueThreshold()
                );
                candidates.add(new Candidate(tickerY, tickerX, aligned.get(), eg));
            }
        }

        params.setLastPairsTested(pairsTested);
        params.setLastPairsSkipped(pairsSkipped);

        double[] pValues = candidates.stream().mapToDouble(c -> c.eg().pValue()).toArray();
        boolean[] passFdr = WalkForwardAnalyzer.benjaminiHochberg(pValues, coint.fdrQ());

        List<PairAnalysisResult> cointegratedPairs = new ArrayList<>();
        ImoexProperties.RiskProperties risk = properties.risk();

        for (int idx = 0; idx < candidates.size(); idx++) {
            if (coint.fdrEnabled() && !passFdr[idx]) {
                continue;
            }
            Candidate c = candidates.get(idx);
            if (!c.eg().cointegrated()) {
                continue;
            }

            double[] spread;
            double intercept;
            double hedgeRatio;
            if (coint.kalmanEnabled()) {
                KalmanHedgeFilter.Result kf = KalmanHedgeFilter.filter(
                        c.pairData().logY(),
                        c.pairData().logX(),
                        c.eg().intercept(),
                        c.eg().hedgeRatio(),
                        coint.kalmanDelta(),
                        coint.kalmanVe()
                );
                spread = kf.spread();
                intercept = kf.lastIntercept();
                hedgeRatio = kf.lastBeta();
            } else {
                intercept = c.eg().intercept();
                hedgeRatio = c.eg().hedgeRatio();
                spread = SpreadAnalytics.computeSpread(
                        c.pairData().logY(), c.pairData().logX(), intercept, hedgeRatio);
            }

            double[] zScores = coint.rollingZEnabled()
                    ? SpreadAnalytics.rollingZScores(spread, params.rollingZWindow())
                    : SpreadAnalytics.zScores(spread);

            double stopZ = risk.adaptiveStopEnabled()
                    ? com.moex.cointegration.quant.AdaptiveStop.stopZ(
                    spread, risk.adaptiveStopBase(), risk.adaptiveStopCap(), 20, params.adaptiveLongWin())
                    : risk.stopZ();

            TradingMetrics metrics = SpreadAnalytics.simulateMeanReversion(
                    spread,
                    properties.commissionRate(),
                    coint.zScoreEntry(),
                    coint.zScoreExit(),
                    replaceNanZWithZeroForMetrics(zScores),
                    stopZ,
                    params.maxHoldBars(),
                    risk.borrowRateAnnual(),
                    coint.entryReversalRequired(),
                    risk.trailZ(),
                    risk.partialTpFraction(),
                    params.barsPerYear()
            );

            int barsY = processed.get(c.tickerY()).points().size();
            int barsX = processed.get(c.tickerX()).points().size();
            PairCoverage coverage = PairCoverage.of(barsY, barsX, c.pairData().begins().length);

            cointegratedPairs.add(new PairAnalysisResult(
                    c.tickerY(),
                    c.tickerX(),
                    intercept,
                    hedgeRatio,
                    c.eg().adfStatistic(),
                    c.eg().pValue(),
                    metrics.sharpeRatio(),
                    metrics.maxDrawdown(),
                    metrics.halfLifeDays(),
                    metrics.tradeCount(),
                    metrics.totalReturn(),
                    c.eg().rSquared(),
                    SpreadAnalytics.toSeries(c.pairData().begins(), spread),
                    SpreadAnalytics.toSeries(c.pairData().begins(), zScores),
                    coverage.coveragePercent(),
                    coverage.warning()
            ));
        }
        return cointegratedPairs;
    }

    private static double[] replaceNanZWithZeroForMetrics(double[] z) {
        double[] copy = z.clone();
        for (int i = 0; i < copy.length; i++) {
            if (Double.isNaN(copy[i])) {
                copy[i] = 0.0;
            }
        }
        return copy;
    }

    private record Candidate(
            String tickerY,
            String tickerX,
            AlignedPairData pairData,
            EngleGrangerResult eg
    ) {
    }
}
