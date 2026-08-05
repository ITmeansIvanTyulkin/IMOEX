package com.moex.cointegration.model;

import java.time.LocalDateTime;

/**
 * Log-цены и метки времени двух акций, выровненные по общим барам.
 *
 * @param logY   log-цены Y
 * @param logX   log-цены X
 * @param begins общие метки начала баров
 */
public record AlignedPairData(double[] logY, double[] logX, LocalDateTime[] begins) {
    /** Alias для кода, который ещё ждёт dates(). */
    public LocalDateTime[] dates() {
        return begins;
    }
}
