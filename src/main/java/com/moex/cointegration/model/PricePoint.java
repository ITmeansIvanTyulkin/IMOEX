package com.moex.cointegration.model;

import java.time.LocalDate;

/**
 * Одна точка ценового ряда после выравнивания по датам.
 *
 * @param date  дата наблюдения
 * @param close цена закрытия
 */
public record PricePoint(LocalDate date, double close) {
}
