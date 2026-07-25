package com.moex.cointegration.service;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.PairAnalysisResult;
import com.moex.cointegration.model.TradingRecommendation;
import com.moex.cointegration.model.TradingSignal;
import org.springframework.stereotype.Service;

/**
 * Risk policy: стопы, лимиты портфеля, размер REDUCE, фильтры качества.
 */
@Service
public class RiskPolicyService {

    private final ImoexProperties properties;

    public RiskPolicyService(ImoexProperties properties) {
        this.properties = properties;
    }

    public ImoexProperties.RiskProperties policy() {
        return properties.risk();
    }

    public boolean passesQualityFilters(PairAnalysisResult pair) {
        ImoexProperties.RiskProperties risk = properties.risk();
        if (pair.sharpeRatio() < risk.minSharpe()) {
            return false;
        }
        if (Double.isNaN(pair.halfLifeDays())) {
            return false;
        }
        return pair.halfLifeDays() >= risk.minHalfLifeDays()
                && pair.halfLifeDays() <= risk.maxHalfLifeDays();
    }

    public String qualityRejectReason(PairAnalysisResult pair) {
        ImoexProperties.RiskProperties risk = properties.risk();
        if (pair.sharpeRatio() < risk.minSharpe()) {
            return String.format("Sharpe=%.2f < %.1f", pair.sharpeRatio(), risk.minSharpe());
        }
        if (Double.isNaN(pair.halfLifeDays())) {
            return "half-life не определён (спред не mean-reverting)";
        }
        if (pair.halfLifeDays() > risk.maxHalfLifeDays()) {
            return String.format("half-life=%.1f дней — слишком медленный возврат", pair.halfLifeDays());
        }
        if (pair.halfLifeDays() < risk.minHalfLifeDays()) {
            return String.format("half-life=%.2f — подозрительно быстрый (шум)", pair.halfLifeDays());
        }
        return "неизвестная причина";
    }

    /**
     * Доля notional: 1.0 для ENTER-качества, {@code reduceSizeFactor} для REDUCE.
     */
    public double sizeMultiplier(TradingSignal signal, boolean reduce) {
        if (signal != TradingSignal.LONG_SPREAD && signal != TradingSignal.SHORT_SPREAD) {
            return 0.0;
        }
        return reduce ? properties.risk().reduceSizeFactor() : 1.0;
    }

    public int maxOpenPairs() {
        return Math.max(1, properties.risk().maxOpenPairs());
    }

    public double suggestedNotional(TradingRecommendation rec, boolean reduce) {
        double base = properties.paper().notionalPerLeg();
        return base * sizeMultiplier(rec.signal(), reduce);
    }
}
