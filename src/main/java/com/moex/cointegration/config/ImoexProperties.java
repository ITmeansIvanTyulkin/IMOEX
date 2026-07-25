package com.moex.cointegration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки интеграции с MOEX и параметров анализа коинтеграции из {@code application.yml}.
 */
@ConfigurationProperties(prefix = "imoex")
public record ImoexProperties(
        String baseUrl,
        String board,
        String index,
        int historyYears,
        double commissionRate,
        CointegrationProperties cointegration,
        NewsProperties news,
        String dataDir,
        String chartsDir
) {
    public ImoexProperties {
        if (news == null) {
            news = new NewsProperties(true, 10, 10, 8);
        }
        if (cointegration == null) {
            cointegration = new CointegrationProperties(0.05, 2.0, 0.0, 10);
        }
    }

    public record CointegrationProperties(
            double pValueThreshold,
            double zScoreEntry,
            double zScoreExit,
            int topN
    ) {
    }

    /**
     * Новостной safety-layer для дневной/свинговой парной торговли.
     */
    public record NewsProperties(
            boolean enabled,
            int lookbackDays,
            int staleCandleDays,
            int maxNewsPages
    ) {
    }
}
