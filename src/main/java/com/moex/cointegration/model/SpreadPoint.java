package com.moex.cointegration.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Одна точка временного ряда спреда или Z-score.
 *
 * @param begin момент наблюдения
 * @param value значение спреда / Z-score
 */
public record SpreadPoint(LocalDateTime begin, double value) {
    public LocalDate date() {
        return begin.toLocalDate();
    }

    public SpreadPoint(LocalDate date, double value) {
        this(date.atStartOfDay(), value);
    }
}
