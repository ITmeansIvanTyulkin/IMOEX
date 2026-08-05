package com.moex.cointegration.model;

import java.util.List;

/**
 * Новостная оценка одной пары.
 *
 * @param riskLevel       агрегированный риск
 * @param asymmetric      новость бьёт в основном одну ногу
 * @param summary         краткий вердикт для новичка
 * @param hits            сработавшие триггеры
 * @param newsCheckedDays горизонт просмотра новостей (дней)
 */
public record PairNewsAssessment(
        NewsRiskLevel riskLevel,
        boolean asymmetric,
        String summary,
        List<NewsTriggerHit> hits,
        int newsCheckedDays
) {
    public static PairNewsAssessment none(int lookbackDays) {
        return new PairNewsAssessment(
                NewsRiskLevel.LOW,
                false,
                "Существенных новостных триггеров за " + lookbackDays + " дн. не найдено.",
                List.of(),
                lookbackDays
        );
    }
}
