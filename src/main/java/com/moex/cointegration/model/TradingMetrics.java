package com.moex.cointegration.model;

/**
 * Метрики backtest mean-reversion стратегии на спреде пары.
 *
 * @param sharpeRatio   годовой коэффициент Шарпа (252 торговых дня)
 * @param maxDrawdown   максимальная просадка equity-кривой
 * @param halfLifeDays  полупериод возврата спреда к среднему (дни)
 * @param totalReturn   суммарная доходность стратегии за период
 * @param tradeCount    число сделок (каждая нога считается отдельно)
 */
public record TradingMetrics(
        double sharpeRatio,
        double maxDrawdown,
        double halfLifeDays,
        double totalReturn,
        int tradeCount
) {
}
