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
        UniverseProperties universe,
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
        if (universe == null) {
            universe = UniverseProperties.defaults();
        }
        if (auth == null) {
            auth = AuthProperties.defaults();
        }
    }

    /** Фабрика для unit-тестов (risk / walk-forward / paper / universe / auth — defaults). */
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
                dataDir, chartsDir, null, null, null, null, null
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
     *
     * @param dynamicSizing     размер ~ (targetσ / σ_spread) × (room to stop)
     * @param targetSpreadSigma опорная σ спреда для полного размера
     * @param minSizeMult       нижний clamp множителя
     * @param maxSizeMult       верхний clamp множителя
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
            Double borrowRateAnnual,
            Boolean dynamicSizing,
            Double targetSpreadSigma,
            Double minSizeMult,
            Double maxSizeMult
    ) {
        public RiskProperties {
            if (borrowRateAnnual == null || borrowRateAnnual < 0) {
                borrowRateAnnual = 0.08;
            }
            if (dynamicSizing == null) {
                dynamicSizing = true;
            }
            if (targetSpreadSigma == null || targetSpreadSigma <= 0) {
                targetSpreadSigma = 0.02;
            }
            if (minSizeMult == null || minSizeMult <= 0) {
                minSizeMult = 0.25;
            }
            if (maxSizeMult == null || maxSizeMult < minSizeMult) {
                maxSizeMult = 1.5;
            }
        }

        public static RiskProperties defaults() {
            return new RiskProperties(3.5, 40, 0.5, 5, 1.0, 0.0, 90.0, 1.0, 0.08,
                    true, 0.02, 0.25, 1.5);
        }

        public boolean dynamicSizingEnabled() {
            return Boolean.TRUE.equals(dynamicSizing);
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
     * Pre-filter тикеров перед Engle–Granger (ликвидность / цена / preferred / сектора).
     * Shortability без брокера аппроксимируется: exclude-preferred + ADV.
     *
     * @param sameSectorOnly        пары только внутри одного сектора (банки, нефть, …)
     * @param minPairTurnoverRub    обе ноги должны иметь медианный оборот ≥ этого порога
     * @param maxTurnoverRatio      max(ADV_y,ADV_x)/min ≤ ratio (баланс ликвидности ног)
     */
    public record UniverseProperties(
            boolean enabled,
            int lookbackDays,
            double minMedianTurnoverRub,
            double minPrice,
            double maxZeroVolumeFraction,
            boolean excludePreferred,
            Boolean sameSectorOnly,
            Double minPairTurnoverRub,
            Double maxTurnoverRatio
    ) {
        public UniverseProperties {
            if (sameSectorOnly == null) {
                sameSectorOnly = true;
            }
            if (minPairTurnoverRub == null || minPairTurnoverRub < 0) {
                minPairTurnoverRub = 80_000_000.0;
            }
            if (maxTurnoverRatio == null || maxTurnoverRatio < 1) {
                maxTurnoverRatio = 15.0;
            }
        }

        public static UniverseProperties defaults() {
            return new UniverseProperties(
                    true, 60, 50_000_000.0, 5.0, 0.15, true,
                    true, 80_000_000.0, 15.0
            );
        }

        public boolean sameSectorOnlyEnabled() {
            return Boolean.TRUE.equals(sameSectorOnly);
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
