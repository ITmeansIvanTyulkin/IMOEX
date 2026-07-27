package com.moex.cointegration.service;

import com.moex.cointegration.config.CapitalProperties;
import com.moex.cointegration.model.BookKind;
import com.moex.cointegration.model.FinalTradeDecision;
import com.moex.cointegration.model.PairNewsAssessment;
import com.moex.cointegration.model.TradingRecommendation;
import com.moex.cointegration.model.TradingSignal;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Краткое объяснение итоговой рекомендации для оператора и JSON.
 */
@Service
public class RecommendationRationaleService {

    private final RiskPolicyService riskPolicyService;
    private final CapitalProperties capitalProperties;

    public RecommendationRationaleService(
            RiskPolicyService riskPolicyService,
            CapitalProperties capitalProperties
    ) {
        this.riskPolicyService = riskPolicyService;
        this.capitalProperties = capitalProperties;
    }

    public String build(
            TradingRecommendation rec,
            FinalTradeDecision decision,
            PairNewsAssessment news,
            BookKind book
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("Техника: Z = ").append(formatZ(rec.currentZScore()));
        sb.append(", ").append(signalPhrase(rec.signal()));
        if (rec.coveragePercent() != null && rec.coveragePercent() < 100) {
            sb.append(String.format(Locale.ROOT, ", coverage %.1f%%", rec.coveragePercent()));
        }
        sb.append(". ");

        if (book == BookKind.INTRADAY) {
            sb.append("Фундаментал: пропущен (INTRADAY). ");
        } else {
            sb.append("Фундаментал: ");
            if (news == null || news.riskLevel() == null) {
                sb.append("нет данных. ");
            } else {
                sb.append(switch (news.riskLevel()) {
                    case BLOCK -> "BLOCK — " + shorten(news.summary());
                    case HIGH -> "высокий риск — " + shorten(news.summary());
                    case MEDIUM -> "средний риск — " + shorten(news.summary());
                    default -> "нет блокеров";
                }).append(". ");
            }
        }

        var regime = riskPolicyService.regime();
        sb.append("Режим: ").append(regime.label()).append(". ");
        var alloc = capitalProperties.allocation();
        int slots = book == BookKind.INTRADAY ? alloc.intradayMaxPairs() : alloc.dailyMaxPairs();
        sb.append("Риск: ").append(decision.name()).append(", ~").append(slots).append(" слот(ов).");
        return sb.toString();
    }

    private static String signalPhrase(TradingSignal signal) {
        return switch (signal) {
            case LONG_SPREAD -> "разворот вверх (LONG spread)";
            case SHORT_SPREAD -> "разворот вниз (SHORT spread)";
            case WATCH -> "WATCH — ждём подтверждения";
            default -> signal.name();
        };
    }

    private static String formatZ(double z) {
        return String.format(Locale.ROOT, "%+.2f", z);
    }

    private static String shorten(String s) {
        if (s == null || s.isBlank()) {
            return "—";
        }
        return s.length() > 80 ? s.substring(0, 77) + "…" : s;
    }
}
