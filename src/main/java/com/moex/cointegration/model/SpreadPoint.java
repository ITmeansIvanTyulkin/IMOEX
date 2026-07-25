package com.moex.cointegration.model;

import java.time.LocalDate;

/**
 * Одна точка временного ряда спреда или Z-score.
 *
 * @param date  дата
 * @param value значение спреда / Z-score
 */
public record SpreadPoint(LocalDate date, double value) {
}
