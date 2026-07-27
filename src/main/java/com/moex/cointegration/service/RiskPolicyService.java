package com.moex.cointegration.service;

import com.moex.cointegration.config.CapitalProperties;
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
    private final CapitalProperties capitalProperties;
    private final RegimeProperties regimeProperties;
    private final MarketRegimeService marketRegimeService;

    @Autowired
    public RiskPolicyService(
            ImoexProperties properties,
            CapitalProperties capitalProperties,
            RegimeProperties regimeProperties,
            MarketRegimeService marketRegimeService
    ) {
        this.properties = properties;
        this.capitalProperties = capitalProperties;
        this.regimeProperties = regimeProperties;
        this.marketRegimeService = marketRegimeService;
    }

    /** Тесты без Spring: regime off. */
    public RiskPolicyService(ImoexProperties properties) {
        this(properties, CapitalProperties.defaults(), new RegimeProperties(false, 14, 20.0, 25.0, 0.5, "SNDX"), null);
    }

    public ImoexProperties.RiskProperties policy() {
        return properties.risk();
    }

    public boolean passesQualityFilters(PairAnalysisResult pair) {
        return qualityRejectReason(pair, 1.0, null, null) == null;
    }

    public boolean passesQualityFilters(
            PairAnalysisResult pair,
            double barsPerDay,
            Double minHalfLifeDaysOverride,
            Double tradeMaxHalfLifeDaysOverride
    ) {
        return qualityRejectReason(pair, barsPerDay, minHalfLifeDaysOverride, tradeMaxHalfLifeDaysOverride) == null;
    }

    public String qualityRejectReason(PairAnalysisResult pair) {
        return qualityRejectReason(pair, 1.0, null, null);
    }

    /**
     * @param barsPerDay сколько баров в одном торговом дне (1 для daily, ~7 для 1H)
     */
    public String qualityRejectReason(
            PairAnalysisResult pair,
            double barsPerDay,
            Double minHalfLifeDaysOverride,
            Double tradeMaxHalfLifeDaysOverride
    ) {
        ImoexProperties.RiskProperties risk = properties.risk();
        double bpDay = barsPerDay > 0 ? barsPerDay : 1.0;
        if (pair.sharpeRatio() < risk.minSharpe()) {
            return String.format("Sharpe=%.2f < %.1f", pair.sharpeRatio(), risk.minSharpe());
        }
        if (Double.isNaN(pair.halfLifeDays())) {
            return "half-life не определён (спред не mean-reverting)";
        }
        // halfLifeDays() считает half-life в барах; переводим в дни
        double hlDays = pair.halfLifeDays() / bpDay;
        double minHl = minHalfLifeDaysOverride != null ? minHalfLifeDaysOverride : risk.minHalfLifeDays();
        double maxHlResearch = risk.maxHalfLifeDays();
        double tradeMaxHl = tradeMaxHalfLifeDaysOverride != null
                ? tradeMaxHalfLifeDaysOverride
                : risk.tradeMaxHalfLifeDays();

        if (hlDays > maxHlResearch) {
            return String.format("half-life=%.1f дней — слишком медленный возврат (research)", hlDays);
        }
        if (hlDays < minHl) {
            return String.format("half-life=%.2f — подозрительно быстрый (шум)", hlDays);
        }
        if (hlDays > tradeMaxHl) {
            return String.format("half-life=%.1f > trade-max %.0f дн. (боковик)", hlDays, tradeMaxHl);
        }
        if (!Double.isNaN(pair.rSquared()) && pair.rSquared() < risk.minRSquared()) {
            return String.format("R²=%.2f < %.2f", pair.rSquared(), risk.minRSquared());
        }
        if (pair.tradeCount() < risk.minTradeCount() * 2) {
            return String.format("сделок в бэктесте мало (%d < %d)",
                    pair.tradeCount() / 2, risk.minTradeCount());
        }
        if (pair.coveragePercent() < risk.minCoveragePercent()) {
            return String.format("coverage=%.1f%% < %.0f%% (низкое пересечение истории)",
                    pair.coveragePercent(), risk.minCoveragePercent());
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
        double equity = capitalProperties == null ? 100_000.0 : capitalProperties.equityRub();
        double base = properties.paper().baseNotionalPerLeg(equity);
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
