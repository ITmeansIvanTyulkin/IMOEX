package com.moex.cointegration.service;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.config.RegimeProperties;
import com.moex.cointegration.model.MarketRegimeSnapshot;
import com.moex.cointegration.model.PairAnalysisResult;
import com.moex.cointegration.model.TradingRecommendation;
import com.moex.cointegration.model.TradingSignal;
import com.moex.cointegration.quant.CusumDetector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Risk policy: стопы, лимиты портфеля, динамический размер, фильтры качества, regime.
 */
@Service
public class RiskPolicyService {

    private final ImoexProperties properties;
    private final RegimeProperties regimeProperties;
    private final MarketRegimeService marketRegimeService;

    @Autowired
    public RiskPolicyService(
            ImoexProperties properties,
            RegimeProperties regimeProperties,
            MarketRegimeService marketRegimeService
    ) {
        this.properties = properties;
        this.regimeProperties = regimeProperties;
        this.marketRegimeService = marketRegimeService;
    }

    /** Тесты без Spring: regime off. */
    public RiskPolicyService(ImoexProperties properties) {
        this(properties, new RegimeProperties(false, 14, 20.0, 25.0, 0.5, "SNDX"), null);
    }

    public ImoexProperties.RiskProperties policy() {
        return properties.risk();
    }

    public boolean passesQualityFilters(PairAnalysisResult pair) {
        return qualityRejectReason(pair) == null;
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
            return String.format("half-life=%.1f дней — слишком медленный возврат (research)", pair.halfLifeDays());
        }
        if (pair.halfLifeDays() < risk.minHalfLifeDays()) {
            return String.format("half-life=%.2f — подозрительно быстрый (шум)", pair.halfLifeDays());
        }
        // Торговый gate для боковика жёстче research maxHalfLife
        if (pair.halfLifeDays() > risk.tradeMaxHalfLifeDays()) {
            return String.format("half-life=%.1f > trade-max %.0f дн. (боковик)",
                    pair.halfLifeDays(), risk.tradeMaxHalfLifeDays());
        }
        if (!Double.isNaN(pair.rSquared()) && pair.rSquared() < risk.minRSquared()) {
            return String.format("R²=%.2f < %.2f", pair.rSquared(), risk.minRSquared());
        }
        // tradeCount в симуляции считает ноги (open+close), поэтому порог ×2
        if (pair.tradeCount() < risk.minTradeCount() * 2) {
            return String.format("сделок в бэктесте мало (%d < %d)",
                    pair.tradeCount() / 2, risk.minTradeCount());
        }
        return null;
    }

    public boolean regimeBlocksEntries() {
        if (!regimeProperties.enabledFlag() || marketRegimeService == null) {
            return false;
        }
        return marketRegimeService.current().blockEntries();
    }

    public MarketRegimeSnapshot regime() {
        if (marketRegimeService == null) {
            return MarketRegimeSnapshot.unknown();
        }
        return marketRegimeService.current();
    }

    public boolean structuralBreak(PairAnalysisResult pair) {
        ImoexProperties.RiskProperties risk = properties.risk();
        if (!risk.cusumEnabledFlag() || pair.zScoreSeries() == null || pair.zScoreSeries().isEmpty()) {
            return false;
        }
        double[] z = pair.zScoreSeries().stream().mapToDouble(p -> p.value()).toArray();
        return CusumDetector.detectTail(z, risk.cusumLookback(), risk.cusumThreshold(), risk.cusumDrift());
    }

    public double effectiveStopZ(double[] spread) {
        ImoexProperties.RiskProperties risk = properties.risk();
        if (!risk.adaptiveStopEnabled() || spread == null) {
            return risk.stopZ();
        }
        return com.moex.cointegration.quant.AdaptiveStop.stopZ(
                spread, risk.adaptiveStopBase(), risk.adaptiveStopCap(), 20, 252);
    }

    /**
     * Доля notional: REDUCE × regime × динамика (1/σ_spread × room-to-stop).
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
        MarketRegimeSnapshot reg = regime();
        if (reg.reduceSize()) {
            base *= regimeProperties.reduceFactor();
        }
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
        double stop = risk.stopZ();
        double room = (stop - zAbs) / stop;
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
