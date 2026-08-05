package com.moex.cointegration.model;

/**
 * Cash PnL по одной паре внутри портфельного replay (поиск edge).
 */
public record PairCashStats(
        String tickerY,
        String tickerX,
        int tradesOpened,
        int tradesClosed,
        double netPnlRub,
        double winRate,
        double avgPnlRub,
        double maxDrawdownRub
) {
}
