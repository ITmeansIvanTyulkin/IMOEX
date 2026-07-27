package com.moex.cointegration.service;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.BookKind;
import com.moex.cointegration.model.AlignedPairData;
import com.moex.cointegration.model.Candle;
import com.moex.cointegration.model.EngleGrangerResult;
import com.moex.cointegration.model.PairAnalysisResult;
import com.moex.cointegration.model.PricePoint;
import com.moex.cointegration.model.PriceSeries;
import com.moex.cointegration.model.TradingMetrics;
import com.moex.cointegration.quant.EngleGrangerTest;
import com.moex.cointegration.quant.KalmanHedgeFilter;
import com.moex.cointegration.quant.SpreadAnalytics;
import com.moex.cointegration.storage.MarketDataStorage;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Ищет пару в топ-отчёте или пересчитывает её заново по локальным свечам.
 * Нужен, чтобы графики работали не только для top-N по Sharpe, но и для сигналов.
 */
@Service
public class PairLookupService {

    private final MarketDataStorage storage;
    private final PreprocessingService preprocessingService;
    private final ImoexProperties properties;

    public PairLookupService(
            MarketDataStorage storage,
            PreprocessingService preprocessingService,
            ImoexProperties properties
    ) {
        this.storage = storage;
        this.preprocessingService = preprocessingService;
        this.properties = properties;
    }

    /**
     * Возвращает результат анализа пары: сначала из отчёта, иначе on-demand по свечам.
     */
    public PairAnalysisResult requirePair(String tickerY, String tickerX) throws IOException {
        Optional<PairAnalysisResult> fromReport = storage.findPair(tickerY, tickerX);
        if (fromReport.isPresent()) {
            return fromReport.get();
        }
        return analyzeFromCandles(tickerY, tickerX)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Не удалось построить пару " + tickerY + "/" + tickerX
                                + ": нет общих свечей или пара не коинтегрирована."));
    }

    /** Пересчёт Engle–Granger + спред/Z по локальным OHLC. */
    public Optional<PairAnalysisResult> analyzeFromCandles(String tickerY, String tickerX) throws IOException {
        return analyzeFromCandles(tickerY, tickerX, BookKind.DAILY);
    }

    public Optional<PairAnalysisResult> analyzeFromCandles(
            String tickerY,
            String tickerX,
            BookKind book
    ) throws IOException {
        boolean hourly = book == BookKind.INTRADAY;
        PriceSeries seriesY = toPriceSeries(tickerY, hourly);
        PriceSeries seriesX = toPriceSeries(tickerX, hourly);
        int minBars = hourly ? 80 : 100;
        if (seriesY.points().size() < minBars || seriesX.points().size() < minBars) {
            return Optional.empty();
        }

        PriceSeries y = preprocessingService.applyLocf(seriesY);
        PriceSeries x = preprocessingService.applyLocf(seriesX);

        Optional<AlignedPairData> aligned = preprocessingService.alignPair(y, x);
        if (aligned.isEmpty()) {
            return Optional.empty();
        }

        AlignedPairData pairData = aligned.get();
        var coint = properties.cointegration();
        var risk = properties.risk();
        EngleGrangerResult eg = EngleGrangerTest.test(
                tickerY,
                tickerX,
                pairData.logY(),
                pairData.logX(),
                coint.pValueThreshold()
        );

        double intercept;
        double hedgeRatio;
        double[] spread;
        if (coint.kalmanEnabled()) {
            KalmanHedgeFilter.Result kf = KalmanHedgeFilter.filter(
                    pairData.logY(), pairData.logX(),
                    eg.intercept(), eg.hedgeRatio(),
                    coint.kalmanDelta(), coint.kalmanVe()
            );
            spread = kf.spread();
            intercept = kf.lastIntercept();
            hedgeRatio = kf.lastBeta();
        } else {
            intercept = eg.intercept();
            hedgeRatio = eg.hedgeRatio();
            spread = SpreadAnalytics.computeSpread(pairData.logY(), pairData.logX(), intercept, hedgeRatio);
        }
        double[] zScores = coint.rollingZEnabled()
                ? SpreadAnalytics.rollingZScores(spread, coint.rollingZWindow())
                : SpreadAnalytics.zScores(spread);
        TradingMetrics metrics = SpreadAnalytics.simulateMeanReversion(
                spread,
                properties.commissionRate(),
                coint.zScoreEntry(),
                coint.zScoreExit(),
                zScores,
                risk.adaptiveStopEnabled()
                        ? com.moex.cointegration.quant.AdaptiveStop.stopZ(
                        spread, risk.adaptiveStopBase(), risk.adaptiveStopCap(), 20, 252)
                        : risk.stopZ(),
                risk.maxHoldBars(),
                risk.borrowRateAnnual(),
                coint.entryReversalRequired(),
                risk.trailZ(),
                risk.partialTpFraction()
        );

        int barsY = seriesY.points().size();
        int barsX = seriesX.points().size();
        var coverage = com.moex.cointegration.model.PairCoverage.of(barsY, barsX, pairData.begins().length);

        return Optional.of(new PairAnalysisResult(
                tickerY,
                tickerX,
                intercept,
                hedgeRatio,
                eg.adfStatistic(),
                eg.pValue(),
                metrics.sharpeRatio(),
                metrics.maxDrawdown(),
                metrics.halfLifeDays(),
                metrics.tradeCount(),
                metrics.totalReturn(),
                eg.rSquared(),
                SpreadAnalytics.toSeries(pairData.dates(), spread),
                SpreadAnalytics.toSeries(pairData.dates(), zScores),
                coverage.coveragePercent(),
                coverage.warning()
        ));
    }

    /** Выровненные по датам OHLC обеих ног пары. */
    public AlignedCandles loadAlignedCandles(String tickerY, String tickerX) throws IOException {
        List<Candle> rawY = storage.loadCandles(tickerY);
        List<Candle> rawX = storage.loadCandles(tickerX);
        if (rawY.isEmpty() || rawX.isEmpty()) {
            throw new IllegalArgumentException("Нет локальных свечей для " + tickerY + "/" + tickerX);
        }

        Map<LocalDate, Candle> yByDate = new HashMap<>();
        for (Candle c : rawY) {
            yByDate.put(c.date(), c);
        }
        Map<LocalDate, Candle> xByDate = new HashMap<>();
        for (Candle c : rawX) {
            xByDate.put(c.date(), c);
        }

        List<LocalDate> dates = yByDate.keySet().stream()
                .filter(xByDate::containsKey)
                .sorted()
                .toList();

        List<Candle> candlesY = dates.stream().map(yByDate::get).toList();
        List<Candle> candlesX = dates.stream().map(xByDate::get).toList();
        return new AlignedCandles(dates, candlesY, candlesX);
    }

    private PriceSeries toPriceSeries(String ticker) throws IOException {
        return toPriceSeries(ticker, false);
    }

    private PriceSeries toPriceSeries(String ticker, boolean hourly) throws IOException {
        List<Candle> candles = hourly
                ? storage.loadHourlyCandles(ticker)
                : storage.loadCandles(ticker);
        candles = candles.stream()
                .sorted(Comparator.comparing(Candle::begin))
                .toList();
        List<PricePoint> points = candles.stream()
                .map(c -> new PricePoint(c.begin(), c.close()))
                .toList();
        return new PriceSeries(ticker, points);
    }

    public record AlignedCandles(List<LocalDate> dates, List<Candle> candlesY, List<Candle> candlesX) {
    }
}
