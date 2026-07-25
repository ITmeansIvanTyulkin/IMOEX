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
        double[] spread = new double[y.length];
        for (int i = 0; i < y.length; i++) {
            spread[i] = y[i] - (intercept + beta * x[i]);
        }
        return spread;
    }

    /** Преобразует массив значений и дат в список точек для API и графиков. */
    public static List<SpreadPoint> toSeries(LocalDate[] dates, double[] values) {
        List<SpreadPoint> points = new ArrayList<>(values.length);
        for (int i = 0; i < values.length; i++) {
            points.add(new SpreadPoint(dates[i], values[i]));
        }
        return points;
    }

    /**
     * Стандартизует спред: Z = (spread − mean) / std по всей истории.
     */
    public static double[] zScores(double[] spread) {
        double mean = 0.0;
        for (double v : spread) {
            mean += v;
        }
        mean /= spread.length;

        double variance = 0.0;
        for (double v : spread) {
            double diff = v - mean;
            variance += diff * diff;
        }
        variance /= Math.max(1, spread.length - 1);
        double std = Math.sqrt(variance);

        double[] z = new double[spread.length];
        for (int i = 0; i < spread.length; i++) {
            z[i] = std > 1e-12 ? (spread[i] - mean) / std : 0.0;
        }
        return z;
    }

    /**
     * Оценивает half-life mean reversion через AR(1): spread_t = φ·spread_{t-1}.
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
     *   <li>Комиссия: 2 сделки на вход и 2 на выход (обе ноги пары)</li>
     * </ul>
     *
     * @return Sharpe, max drawdown, half-life, total return и число сделок
     */
    public static TradingMetrics simulateMeanReversion(
            double[] spread,
            double commissionRate,
            double zEntry,
            double zExit
    ) {
        double[] z = zScores(spread);
        List<Double> strategyReturns = new ArrayList<>();
        int position = 0;
        int tradeCount = 0;
        double equity = 1.0;
        double peak = 1.0;
        double maxDrawdown = 0.0;

        for (int i = 1; i < spread.length; i++) {
            double dailyReturn = 0.0;
            if (position != 0) {
                double spreadReturn = (spread[i] - spread[i - 1]) / Math.max(Math.abs(spread[i - 1]), 1e-6);
                dailyReturn = position * spreadReturn;
            }

            int previousPosition = position;

            if (position == 0) {
                if (z[i] >= zEntry) {
                    position = -1;
                    tradeCount += 2;
                    dailyReturn -= 2 * commissionRate;
                } else if (z[i] <= -zEntry) {
                    position = 1;
                    tradeCount += 2;
                    dailyReturn -= 2 * commissionRate;
                }
            } else if (position == 1) {
                if (z[i - 1] < 0 && z[i] >= zExit) {
                    position = 0;
                    tradeCount += 2;
                    dailyReturn -= 2 * commissionRate;
                }
            } else if (position == -1) {
                if (z[i - 1] > 0 && z[i] <= zExit) {
                    position = 0;
                    tradeCount += 2;
                    dailyReturn -= 2 * commissionRate;
                }
            }

            if (previousPosition != 0 && position == 0 && i == spread.length - 1) {
                // position closed on last bar already accounted
            }

            equity *= (1.0 + dailyReturn);
            strategyReturns.add(dailyReturn);
            peak = Math.max(peak, equity);
            maxDrawdown = Math.max(maxDrawdown, (peak - equity) / peak);
        }

        double mean = strategyReturns.stream().mapToDouble(v -> v).average().orElse(0.0);
        double variance = strategyReturns.stream()
                .mapToDouble(v -> (v - mean) * (v - mean))
                .average()
                .orElse(0.0);
        double std = Math.sqrt(variance);
        double sharpe = std > 1e-12 ? (mean / std) * Math.sqrt(252.0) : 0.0;
        double halfLife = halfLifeDays(spread);

        return new TradingMetrics(
                sharpe,
                maxDrawdown,
                halfLife,
                equity - 1.0,
                tradeCount
        );
    }

    /** Оценивает коинтегрирующее уравнение OLS (обёртка над {@link OlsRegression#regressYOnX}). */
    public static OlsResult fitSpread(double[] y, double[] x) {
        return OlsRegression.regressYOnX(y, x);
    }
}
