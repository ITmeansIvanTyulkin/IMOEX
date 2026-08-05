package com.moex.cointegration.model;

/**
 * Результат OLS-регрессии y = intercept + beta * x.
 *
 * @param intercept  свободный член
 * @param beta       коэффициент хеджа (наклон)
 * @param residuals  остатки регрессии
 * @param rSquared   коэффициент детерминации R²
 */
public record OlsResult(
        double intercept,
        double beta,
        double[] residuals,
        double rSquared
) {
}
