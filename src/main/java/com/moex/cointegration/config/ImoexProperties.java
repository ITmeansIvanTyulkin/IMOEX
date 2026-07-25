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
        String chartsDir,
        RiskProperties risk,
        WalkForwardProperties walkForward,
        PaperProperties paper,
        AuthProperties auth
) {
    public ImoexProperties {
        if (news == null) {
            news = new NewsProperties(true, 10, 10, 8);
        }
        if (cointegration == null) {
            cointegration = new CointegrationProperties(0.05, 2.0, 0.0, 10, true, 60, 0.10, true, 1e-5, 1e-3, true);
        }
        if (risk == null) {
            risk = RiskProperties.defaults();
        }
        if (walkForward == null) {
            walkForward = WalkForwardProperties.defaults();
        }
        if (paper == null) {
            paper = PaperProperties.defaults();
        }
        if (auth == null) {
            auth = AuthProperties.defaults();
        }
    }

    /** Фабрика для unit-тестов (risk / walk-forward / paper / auth — defaults). */
    public static ImoexProperties forTests(
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
        return new ImoexProperties(
                baseUrl, board, index, historyYears, commissionRate, cointegration, news,
                dataDir, chartsDir, null, null, null, null
        );
    }

    public record CointegrationProperties(
            double pValueThreshold,
            double zScoreEntry,
            double zScoreExit,
            int topN,
            Boolean useRollingZ,
            Integer rollingZWindow,
            Double fdrQ,
            Boolean useKalmanHedge,
            Double kalmanDelta,
            Double kalmanVe,
            Boolean requireEntryReversal
    ) {
        public CointegrationProperties {
            if (useRollingZ == null) {
                useRollingZ = true;
            }
            if (rollingZWindow == null || rollingZWindow < 2) {
                rollingZWindow = 60;
            }
            if (fdrQ == null || fdrQ <= 0 || fdrQ > 1) {
                fdrQ = 0.10;
            }
            if (useKalmanHedge == null) {
                useKalmanHedge = true;
            }
            if (kalmanDelta == null || kalmanDelta < 0) {
                kalmanDelta = 1e-5;
            }
            if (kalmanVe == null || kalmanVe <= 0) {
                kalmanVe = 1e-3;
            }
            if (requireEntryReversal == null) {
                requireEntryReversal = true;
            }
        }

        public static CointegrationProperties of(double pValueThreshold, double zScoreEntry, double zScoreExit, int topN) {
            return new CointegrationProperties(pValueThreshold, zScoreEntry, zScoreExit, topN,
                    true, 60, 0.10, true, 1e-5, 1e-3, true);
        }

        public boolean rollingZEnabled() {
            return Boolean.TRUE.equals(useRollingZ);
        }

        public boolean kalmanEnabled() {
            return Boolean.TRUE.equals(useKalmanHedge);
        }

        public boolean entryReversalRequired() {
            return Boolean.TRUE.equals(requireEntryReversal);
        }
    }

    public record NewsProperties(
            boolean enabled,
            int lookbackDays,
            int staleCandleDays,
            int maxNewsPages
    ) {
    }

    /**
     * Risk policy для сигналов и бэктеста.
     */
    public record RiskProperties(
            double stopZ,
            int maxHoldBars,
            double reduceSizeFactor,
            int maxOpenPairs,
            double maxPortfolioGross,
            double minSharpe,
            double maxHalfLifeDays,
            double minHalfLifeDays,
            Double borrowRateAnnual
    ) {
        public RiskProperties {
            if (borrowRateAnnual == null || borrowRateAnnual < 0) {
                borrowRateAnnual = 0.08;
            }
        }

        public static RiskProperties defaults() {
            return new RiskProperties(3.5, 40, 0.5, 5, 1.0, 0.0, 90.0, 1.0, 0.08);
        }
    }

    /**
     * Walk-forward / OOS валидация.
     */
    public record WalkForwardProperties(
            boolean enabled,
            int trainBars,
            int testBars,
            int stepBars
    ) {
        public static WalkForwardProperties defaults() {
            return new WalkForwardProperties(true, 504, 63, 63);
        }
    }

    /**
     * Paper trading journal.
     *
     * @param autoRunDaily при true планировщик каждый торговый день гоняет анализ + paper sync
     * @param dailyCron    cron (по умолчанию пн–пт 19:05 Europe/Moscow wall clock JVM)
     */
    public record PaperProperties(
            boolean enabled,
            double notionalPerLeg,
            String journalFile,
            Boolean autoRunDaily,
            String dailyCron
    ) {
        public PaperProperties {
            if (autoRunDaily == null) {
                autoRunDaily = true;
            }
            if (dailyCron == null || dailyCron.isBlank()) {
                dailyCron = "0 5 19 * * MON-FRI";
            }
        }

        public static PaperProperties defaults() {
            return new PaperProperties(true, 100_000.0, "paper-journal.json", true, "0 5 19 * * MON-FRI");
        }

        public boolean autoRunDailyEnabled() {
            return Boolean.TRUE.equals(autoRunDaily);
        }
    }

    /**
     * HTTP auth для mutating endpoints. При {@code enabled=false} API открыт (dev).
     */
    public record AuthProperties(
            boolean enabled,
            String username,
            String password,
            String apiKey
    ) {
        public static AuthProperties defaults() {
            return new AuthProperties(false, "imoex", "change-me", "");
        }
    }
}
