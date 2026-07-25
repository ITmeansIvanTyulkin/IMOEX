package com.moex.cointegration.model;

import java.util.List;

/**
 * Выровненный по календарю ценовой ряд одной акции.
 *
 * @param ticker тикер бумаги
 * @param points последовательность цен закрытия по общим датам
 */
public record PriceSeries(String ticker, List<PricePoint> points) {
}
