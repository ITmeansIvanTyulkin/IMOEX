package com.moex.cointegration.model;

/**
 * Тип торгового сигнала по Z-score спреда пары.
 */
public enum TradingSignal {
    /** Z ≤ −entry: спред ниже среднего — покупаем спред (long Y, short X). */
    LONG_SPREAD,
    /** Z ≥ +entry: спред выше среднего — продаём спред (short Y, long X). */
    SHORT_SPREAD,
    /** |Z| < entry: позиция не открывается / удерживаем нейтраль. */
    HOLD,
    /** |Z| близок к entry (1.5–2.0): наблюдение. */
    WATCH,
    /** Пара не прошла фильтры качества для торговли. */
    NO_SIGNAL
}
