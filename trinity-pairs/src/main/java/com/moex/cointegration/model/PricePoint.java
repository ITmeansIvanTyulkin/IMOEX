package com.moex.cointegration.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Одна точка ценового ряда после выравнивания по времени.
 *
 * @param begin начало бара / момент наблюдения
 * @param close цена закрытия
 */
public record PricePoint(LocalDateTime begin, double close) {
    /** Совместимость со старым API. */
    public LocalDate date() {
        return begin.toLocalDate();
    }

    public PricePoint(LocalDate date, double close) {
        this(date.atStartOfDay(), close);
    }
}
