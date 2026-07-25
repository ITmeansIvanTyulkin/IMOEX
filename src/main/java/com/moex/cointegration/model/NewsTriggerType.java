package com.moex.cointegration.model;

/**
 * Тип новостного/структурного триггера для дневной парной торговли (не intraday).
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
    SECONDARY_OFFERING,
    BUYBACK,
    MANAGEMENT_CHANGE,
    STALE_PRICE_DATA,
    NOT_TRADABLE,
    NOT_IN_INDEX,
    OTHER_MATERIAL
}
