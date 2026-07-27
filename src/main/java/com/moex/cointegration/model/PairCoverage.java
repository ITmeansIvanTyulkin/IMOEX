package com.moex.cointegration.model;

import java.util.Locale;

/**
 * Доля общих баров двух тикеров относительно более длинного ряда.
 */
public record PairCoverage(double coveragePercent, String warning) {

    public static PairCoverage of(int barsY, int barsX, int commonBars) {
        int denom = Math.max(barsY, barsX);
        if (denom <= 0 || commonBars <= 0) {
            return new PairCoverage(0, "Нет общей истории для пары");
        }
        double pct = 100.0 * commonBars / denom;
        String warn = null;
        double missing = 100.0 - pct;
        if (missing >= 0.05) {
            warn = String.format(Locale.ROOT,
                    "Низкое покрытие — %.1f%% общих баров отсутствуют", missing);
        }
        return new PairCoverage(pct, warn);
    }

    public boolean belowThreshold(double minPercent) {
        return coveragePercent < minPercent;
    }
}
