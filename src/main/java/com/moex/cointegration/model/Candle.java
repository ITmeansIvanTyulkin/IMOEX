package com.moex.cointegration.model;

import java.time.LocalDate;

/**
 * Дневная свеча по акции с MOEX.
 *
 * @param date   торговая дата
 * @param open   цена открытия
 * @param high   максимум
 * @param low    минимум
 * @param close  цена закрытия
 * @param volume объём в штуках
 */
public record Candle(
        LocalDate date,
        double open,
        double high,
        double low,
        double close,
        double volume
) {
}
