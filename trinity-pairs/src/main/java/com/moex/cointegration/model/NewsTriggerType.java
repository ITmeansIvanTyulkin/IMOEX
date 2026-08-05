package com.moex.cointegration.model;

/**
 * Тип новостного/структурного триггера для дневной/позиционной парной торговли
 * (multi-day). В режиме INTRADAY фундамент не применяется.
 */
public enum NewsTriggerType {
    TRADING_HALT,
    DELISTING,
    SANCTIONS,
    DEFAULT_BANKRUPTCY,
    REORGANIZATION_MNA,
    MANDATORY_OFFER,
    DISCRETE_AUCTION,
    RISK_PARAMS_CHANGE,
    DIVIDEND_EVENT,
    DIVIDEND_CUT,
    SECONDARY_OFFERING,
    BUYBACK,
    MANAGEMENT_CHANGE,
    EARNINGS_MISS,
    EARNINGS_BEAT,
    GUIDANCE_DOWN,
    GUIDANCE_UP,
    MAJOR_CONTRACT,
    STALE_PRICE_DATA,
    NOT_TRADABLE,
    NOT_IN_INDEX,
    OTHER_MATERIAL
}
