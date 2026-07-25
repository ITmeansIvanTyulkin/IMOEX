package com.moex.cointegration.service;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.PairAnalysisResult;
import com.moex.cointegration.model.TradingRecommendation;
import com.moex.cointegration.model.TradingSignal;
import org.springframework.stereotype.Service;

/**
 * Risk policy: стопы, лимиты портфеля, динамический размер, фильтры качества.
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
     * Доля notional: REDUCE × динамика (1/σ_spread × room-to-stop).
     */
    public double sizeMultiplier(TradingSignal signal, boolean reduce) {
        return sizeMultiplier(signal, reduce, Double.NaN, Double.NaN);
    }

    public double sizeMultiplier(TradingRecommendation rec, boolean reduce) {
        return sizeMultiplier(rec.signal(), reduce, rec.currentZScore(), rec.currentSpread());
    }

    public double sizeMultiplier(TradingSignal signal, boolean reduce, double z, double spread) {
        if (signal != TradingSignal.LONG_SPREAD && signal != TradingSignal.SHORT_SPREAD) {
            return 0.0;
        }
        ImoexProperties.RiskProperties risk = properties.risk();
        double base = reduce ? risk.reduceSizeFactor() : 1.0;
        if (!risk.dynamicSizingEnabled()) {
            return base;
        }

        double sigma = estimateSpreadSigma(spread, z);
        double volPart = 1.0;
        if (!Double.isNaN(sigma) && sigma > 0) {
            volPart = clamp(risk.targetSpreadSigma() / sigma, risk.minSizeMult(), risk.maxSizeMult());
        }

        double zAbs = Math.abs(z);
        if (Double.isNaN(zAbs)) {
            zAbs = 0;
        }
        double room = (risk.stopZ() - zAbs) / risk.stopZ();
        room = clamp(room, 0.05, 1.0);

        return clamp(base * volPart * room, risk.minSizeMult() * (reduce ? risk.reduceSizeFactor() : 0.05),
                risk.maxSizeMult());
    }

    public int maxOpenPairs() {
        return Math.max(1, properties.risk().maxOpenPairs());
    }

    public double suggestedNotional(TradingRecommendation rec, boolean reduce) {
        double base = properties.paper().notionalPerLeg();
        return base * sizeMultiplier(rec, reduce);
    }

    /**
     * σ ≈ |spread / Z| при осмысленном Z; иначе target σ (нейтральный размер по воле).
     */
    static double estimateSpreadSigma(double spread, double z) {
        if (!Double.isNaN(spread) && !Double.isNaN(z) && Math.abs(z) >= 0.25) {
            return Math.abs(spread / z);
        }
        return Double.NaN;
    }

    private double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
