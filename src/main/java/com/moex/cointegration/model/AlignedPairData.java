package com.moex.cointegration.model;

import java.time.LocalDate;

/**
 * Log-цены и даты двух акций, выровненные по общим торговым дням.
 *
 * @param logY   log-цены Y
 * @param logX   log-цены X
 * @param dates  общие даты наблюдений
 */
public record AlignedPairData(double[] logY, double[] logX, LocalDate[] dates) {
}
