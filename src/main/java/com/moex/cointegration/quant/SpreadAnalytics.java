package com.moex.cointegration.quant;

import com.moex.cointegration.model.OlsResult;
import com.moex.cointegration.model.SpreadPoint;
import com.moex.cointegration.model.TradingMetrics;
import org.apache.commons.math3.stat.regression.SimpleRegression;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Расчёт спреда, Z-score, half-life и метрик mean-reversion стратегии.
 */
public final class SpreadAnalytics {

    private SpreadAnalytics() {
    }

    /**
     * Вычисляет спред коинтегрирующего уравнения: spread = logY − (intercept + beta·logX).
     */
    public static double[] computeSpread(double[] y, double[] x, double intercept, double beta) {
        if (y.length != x.length) {
            throw new IllegalArgumentException("y and x must have the same length");
        }
        double[] spread = new double[y.length];
        for (int i = 0; i < y.length; i++) {
            spread[i] = y[i] - (intercept + beta * x[i]);
        }
        return spread;
    }

    /** Преобразует массив значений и дат в список точек для API и графиков. */
    public static List<SpreadPoint> toSeries(LocalDate[] dates, double[] values) {
        if (dates.length != values.length) {
            throw new IllegalArgumentException("dates and values must have the same length");
        }
        List<SpreadPoint> points = new ArrayList<>(values.length);
        for (int i = 0; i < values.length; i++) {
            points.add(new SpreadPoint(dates[i], values[i]));
        }
        return points;
    }

    /**
     * Стандартизует спред: Z = (spread − mean) / std по всей истории.
     * Для live/OOS предпочтительнее {@link #rollingZScores(double[], int)}.
     */
    public static double[] zScores(double[] spread) {
        if (spread.length == 0) {
            return new double[0];
        }
        double mean = mean(spread);
        double std = sampleStd(spread, mean);
        double[] z = new double[spread.length];
        for (int i = 0; i < spread.length; i++) {
            z[i] = std > 1e-12 ? (spread[i] - mean) / std : 0.0;
        }
        return z;
    }

    /**
     * Rolling Z-score без look-ahead: на каждом баре mean/std считаются только по окну
     * {@code [i - window + 1, i]} (включительно). До накопления окна — NaN.
     */
    public static double[] rollingZScores(double[] spread, int window) {
        if (window < 2) {
            throw new IllegalArgumentException("rolling Z window must be >= 2");
        }
        double[] z = new double[spread.length];
        for (int i = 0; i < spread.length; i++) {
            if (i + 1 < window) {
                z[i] = Double.NaN;
                continue;
            }
            int from = i - window + 1;
            double sum = 0.0;
            for (int j = from; j <= i; j++) {
                sum += spread[j];
            }
            double mean = sum / window;
            double var = 0.0;
            for (int j = from; j <= i; j++) {
                double d = spread[j] - mean;
                var += d * d;
            }
            var /= (window - 1);
            double std = Math.sqrt(var);
            z[i] = std > 1e-12 ? (spread[i] - mean) / std : 0.0;
        }
        return z;
    }

    /**
     * Оценивает half-life mean reversion через AR(1): spread_t = c + φ·spread_{t-1}.
     * half-life = −ln(2) / ln(φ).
     */
    public static double halfLifeDays(double[] spread) {
        if (spread.length < 3) {
            return Double.NaN;
        }

        SimpleRegression regression = new SimpleRegression(true);
        for (int i = 1; i < spread.length; i++) {
            regression.addData(spread[i - 1], spread[i]);
        }

        double phi = regression.getSlope();
        if (phi <= 0.0 || phi >= 1.0) {
            return Double.NaN;
        }

        return -Math.log(2.0) / Math.log(phi);
    }

    /**
     * Симулирует mean-reversion стратегию на спреде с учётом комиссий.
     * <ul>
     *   <li>Вход long spread при Z ≤ −zEntry, short при Z ≥ zEntry</li>
     *   <li>Выход при пересечении Z через zExit (обычно 0)</li>
     *   <li>Опционально: stop по |Z| и time-stop по числу баров в позиции</li>
     *   <li>Доходность нормируется на σ спреда (не на |spread|), чтобы избежать взрыва у нуля</li>
     *   <li>Комиссия: 2 сделки на вход и 2 на выход (обе ноги пары)</li>
     *   <li>Borrow: дневной cost ≈ annualBorrow/252, пока позиция открыта (одна короткая нога)</li>
     * </ul>
     */
    public static TradingMetrics simulateMeanReversion(
            double[] spread,
            double commissionRate,
            double zEntry,
            double zExit
    ) {
        return simulateMeanReversion(spread, commissionRate, zEntry, zExit, zScores(spread), Double.NaN, 0, 0.0, false);
    }

    public static TradingMetrics simulateMeanReversion(
            double[] spread,
            double commissionRate,
            double zEntry,
            double zExit,
            double[] zScoreSeries,
            double stopZ,
            int maxHoldBars
    ) {
        return simulateMeanReversion(spread, commissionRate, zEntry, zExit, zScoreSeries, stopZ, maxHoldBars, 0.0, false);
    }

    public static TradingMetrics simulateMeanReversion(
            double[] spread,
            double commissionRate,
            double zEntry,
            double zExit,
            double[] zScoreSeries,
            double stopZ,
            int maxHoldBars,
            double borrowRateAnnual
    ) {
        return simulateMeanReversion(
                spread, commissionRate, zEntry, zExit, zScoreSeries, stopZ, maxHoldBars, borrowRateAnnual, false);
    }

    /**
     * @param borrowRateAnnual     годовая ставка займа под шорт; 0 — выкл.
     * @param requireEntryReversal true — вход только на развороте за порогом entry
     */
    public static TradingMetrics simulateMeanReversion(
            double[] spread,
            double commissionRate,
            double zEntry,
            double zExit,
            double[] zScoreSeries,
            double stopZ,
            int maxHoldBars,
            double borrowRateAnnual,
            boolean requireEntryReversal
    ) {
        if (spread.length != zScoreSeries.length) {
            throw new IllegalArgumentException("spread and zScoreSeries length mismatch");
        }
        if (spread.length < 2) {
            return new TradingMetrics(0.0, 0.0, halfLifeDays(spread), 0.0, 0);
        }

        double scale = Math.max(sampleStd(spread, mean(spread)), 1e-6);
        double dailyBorrow = Math.max(0.0, borrowRateAnnual) / 252.0;
        List<Double> strategyReturns = new ArrayList<>();
        int position = 0;
        int barsInTrade = 0;
        int tradeCount = 0;
        double equity = 1.0;
        double peak = 1.0;
        double maxDrawdown = 0.0;
        boolean useStop = !Double.isNaN(stopZ) && stopZ > 0.0;

        for (int i = 1; i < spread.length; i++) {
            double dailyReturn = 0.0;
            if (position != 0) {
                dailyReturn = position * (spread[i] - spread[i - 1]) / scale;
                dailyReturn -= dailyBorrow;
                barsInTrade++;
            }

            double z = zScoreSeries[i];
            double zPrev = zScoreSeries[i - 1];

            if (position == 0) {
                if (SignalRules.confirmShortEntry(zPrev, z, zEntry, requireEntryReversal)) {
                    position = -1;
                    barsInTrade = 0;
                    tradeCount += 2;
                    dailyReturn -= 2 * commissionRate;
                } else if (SignalRules.confirmLongEntry(zPrev, z, zEntry, requireEntryReversal)) {
                    position = 1;
                    barsInTrade = 0;
                    tradeCount += 2;
                    dailyReturn -= 2 * commissionRate;
                }
            } else {
                boolean exit = false;
                if (position == 1 && SignalRules.exitLong(zPrev, z, zExit)) {
                    exit = true;
                } else if (position == -1 && SignalRules.exitShort(zPrev, z, zExit)) {
                    exit = true;
                } else if (useStop && !Double.isNaN(z) && Math.abs(z) >= stopZ) {
                    exit = true;
                }
                if (!exit && maxHoldBars > 0 && barsInTrade >= maxHoldBars) {
                    exit = true;
                }
                if (exit) {
                    position = 0;
                    barsInTrade = 0;
                    tradeCount += 2;
                    dailyReturn -= 2 * commissionRate;
                }
            }

            equity *= (1.0 + dailyReturn);
            if (equity <= 0.0) {
                equity = 1e-12;
            }
            strategyReturns.add(dailyReturn);
            peak = Math.max(peak, equity);
            maxDrawdown = Math.max(maxDrawdown, (peak - equity) / peak);
        }

        double meanRet = strategyReturns.stream().mapToDouble(v -> v).average().orElse(0.0);
        double variance = strategyReturns.stream()
                .mapToDouble(v -> (v - meanRet) * (v - meanRet))
                .average()
                .orElse(0.0);
        double std = Math.sqrt(variance);
        double sharpe = std > 1e-12 ? (meanRet / std) * Math.sqrt(252.0) : 0.0;

        return new TradingMetrics(
                sharpe,
                Math.min(maxDrawdown, 1.0),
                halfLifeDays(spread),
                equity - 1.0,
                tradeCount
        );
    }

    /** Оценивает коинтегрирующее уравнение OLS (обёртка над {@link OlsRegression#regressYOnX}). */
    public static OlsResult fitSpread(double[] y, double[] x) {
        return OlsRegression.regressYOnX(y, x);
    }

    private static double mean(double[] values) {
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    private static double sampleStd(double[] values, double mean) {
        if (values.length < 2) {
            return 0.0;
        }
        double variance = 0.0;
        for (double v : values) {
            double diff = v - mean;
            variance += diff * diff;
        }
        variance /= (values.length - 1);
        return Math.sqrt(variance);
    }
}
