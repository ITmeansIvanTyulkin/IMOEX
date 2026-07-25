package com.moex.cointegration.model;

/**
 * Статус бумаги на TQBR для структурного фильтра.
 */
public record SecurityTradingStatus(
        String ticker,
        boolean found,
        String status,
        String tradingStatus,
        String shortName,
        String secName
) {
    public boolean tradable() {
        if (!found) {
            return false;
        }
        // A = активен; T/N = торгуется / перерыв нормальный для MOEX marketdata
        boolean statusOk = status == null || status.isBlank() || "A".equalsIgnoreCase(status);
        boolean tradingOk = tradingStatus == null
                || tradingStatus.isBlank()
                || "T".equalsIgnoreCase(tradingStatus)
                || "N".equalsIgnoreCase(tradingStatus)
                || "C".equalsIgnoreCase(tradingStatus); // закрытие сессии — ок для swing
        return statusOk && tradingOk;
    }

    public static SecurityTradingStatus missing(String ticker) {
        return new SecurityTradingStatus(ticker, false, null, null, null, null);
    }
}
