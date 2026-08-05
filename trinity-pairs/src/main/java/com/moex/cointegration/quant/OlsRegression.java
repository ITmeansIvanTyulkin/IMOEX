package com.moex.cointegration.quant;

import com.moex.cointegration.model.OlsResult;
import org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression;
import org.apache.commons.math3.stat.regression.SimpleRegression;

/**
 * OLS-регрессия для оценки коинтегрирующего уравнения и hedge ratio.
 */
public final class OlsRegression {

    private OlsRegression() {
    }

    /**
     * Простая линейная регрессия Y на X с константой: Y = intercept + beta·X.
     *
     * @param y зависимая переменная (log-цены Y)
     * @param x независимая переменная (log-цены X)
     * @return intercept, beta, остатки и R²
     */
    public static OlsResult regressYOnX(double[] y, double[] x) {
        if (y.length != x.length || y.length < 3) {
            throw new IllegalArgumentException("Need at least 3 aligned observations for OLS");
        }

        SimpleRegression regression = new SimpleRegression(true);
        for (int i = 0; i < y.length; i++) {
            regression.addData(x[i], y[i]);
        }

        double intercept = regression.getIntercept();
        double beta = regression.getSlope();
        double[] residuals = new double[y.length];
        for (int i = 0; i < y.length; i++) {
            residuals[i] = y[i] - (intercept + beta * x[i]);
        }

        return new OlsResult(intercept, beta, residuals, regression.getRSquare());
    }

    /**
     * Множественная OLS-регрессия с несколькими регрессорами (запасной/расширенный вариант).
     *
     * @param dependent  вектор Y
     * @param regressors матрица регрессоров без константы (intercept добавляется автоматически)
     */
    public static OlsResult regressWithLags(double[] dependent, double[][] regressors) {
        OLSMultipleLinearRegression regression = new OLSMultipleLinearRegression();
        regression.newSampleData(dependent, regressors);
        double[] params = regression.estimateRegressionParameters();
        double[] residuals = regression.estimateResiduals();
        double intercept = params[0];
        double beta = params.length > 1 ? params[1] : 0.0;
        return new OlsResult(intercept, beta, residuals, regression.calculateRSquared());
    }
}
