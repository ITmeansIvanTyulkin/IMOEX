package com.moex.cointegration.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.moex.cointegration.model.jackson.CandleBeginDeserializer;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * OHLCV-бар (дневной или часовой) с MOEX.
 *
 * @param begin  начало бара (для дневных — обычно 00:00)
 * @param open   цена открытия
 * @param high   максимум
 * @param low    минимум
 * @param close  цена закрытия
 * @param volume объём в штуках
 */
public record Candle(
        @JsonProperty("begin")
        @JsonAlias("date")
        @JsonDeserialize(using = CandleBeginDeserializer.class)
        LocalDateTime begin,
        double open,
        double high,
        double low,
        double close,
        double volume
) {
    /** Календарная дата бара (для daily-группировок / UI). */
    @JsonIgnore
    public LocalDate date() {
        return begin.toLocalDate();
    }

    /** Дневная свеча из календарной даты. */
    public Candle(LocalDate date, double open, double high, double low, double close, double volume) {
        this(date.atStartOfDay(), open, high, low, close, volume);
    }
}
