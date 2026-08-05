package com.moex.cointegration.model;

import java.util.List;

/**
 * JSON-данные для интерактивной страницы графика пары.
 */
public record ChartPayload(
        String tickerY,
        String tickerX,
        String signal,
        double currentZScore,
        double hedgeRatio,
        double halfLifeDays,
        double sharpeRatio,
        double zEntry,
        double zExit,
        String summary,
        String details,
        List<CandleBar> candlesY,
        List<CandleBar> candlesX,
        List<DateValue> normalizedY,
        List<DateValue> normalizedX,
        List<DateValue> spread,
        List<DateValue> kama,
        List<DateValue> zScore,
        List<ChartMarker> markers
) {
    public record CandleBar(String time, double open, double high, double low, double close) {
    }

    public record DateValue(String time, double value) {
    }

    /**
     * Маркер на графике: покупка / продажа / выход.
     *
     * @param time     дата YYYY-MM-DD
     * @param position aboveBar / belowBar
     * @param color    hex-цвет
     * @param shape    arrowUp / arrowDown / circle
     * @param text     подпись
     * @param series   zscore | spread | price
     */
    public record ChartMarker(
            String time,
            String position,
            String color,
            String shape,
            String text,
            String series
    ) {
    }
}
