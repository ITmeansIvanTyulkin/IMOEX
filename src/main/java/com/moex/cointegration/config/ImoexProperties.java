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
        PortfolioProperties portfolio,
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
        if (portfolio == null) {
            portfolio = PortfolioProperties.defaults();
        }
        if (auth == null) {
            auth = AuthProperties.defaults();
        }
    }

    /** Фабрика для unit-тестов (risk / walk-forward / paper / universe / portfolio / auth — defaults). */
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
                dataDir, chartsDir, null, null, null, null, null, null
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
            int maxNewsPages,
            Boolean rssEnabled,
            String rssInterfaxUrl,
            String rssRbcUrl
    ) {
        public NewsProperties {
            if (rssEnabled == null) {
                rssEnabled = false;
            }
            if (rssInterfaxUrl == null || rssInterfaxUrl.isBlank()) {
                rssInterfaxUrl = "https://www.interfax.ru/rss.asp";
            }
            if (rssRbcUrl == null || rssRbcUrl.isBlank()) {
                rssRbcUrl = "https://rssexport.rbc.ru/rbcnews/news/30/full.rss";
            }
        }

        /** Короткий конструктор для тестов / defaults без RSS. */
        public NewsProperties(boolean enabled, int lookbackDays, int staleCandleDays, int maxNewsPages) {
            this(enabled, lookbackDays, staleCandleDays, maxNewsPages, false, null, null);
        }

        public boolean rssEnabledFlag() {
            return Boolean.TRUE.equals(rssEnabled);
        }
    }

    /**
     * Risk policy для сигналов и бэктеста.
     *
     * @param dynamicSizing     размер ~ (targetσ / σ_spread) × (room to stop)
     * @param targetSpreadSigma опорная σ спреда для полного размера
     * @param minSizeMult       нижний clamp множителя
     * @param maxSizeMult       верхний clamp множителя
     * @param partialTpFraction доля пути к 0 для частичного TP (0.5 = 50%)
     * @param trailZ            откат Z от лучшего уровня для trailing exit
     * @param betaBreakPct      отн. скачок β для выхода
     * @param cointPBreak       p-value выше → считаем коинтеграцию сломанной
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
            Double maxSizeMult,
            Double partialTpFraction,
            Double trailZ,
            Double betaBreakPct,
            Double cointPBreak,
            Double tradeMaxHalfLifeDays,
            Double minRSquared,
            Integer minTradeCount,
            Boolean adaptiveStop,
            Double adaptiveStopBase,
            Double adaptiveStopCap,
            Boolean cusumEnabled,
            Double cusumThreshold,
            Double cusumDrift,
            Integer cusumLookback
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
            if (partialTpFraction == null || partialTpFraction <= 0 || partialTpFraction >= 1) {
                partialTpFraction = 0.5;
            }
            if (trailZ == null || trailZ <= 0) {
                trailZ = 0.75;
            }
            if (betaBreakPct == null || betaBreakPct <= 0) {
                betaBreakPct = 0.35;
            }
            if (cointPBreak == null || cointPBreak <= 0) {
                cointPBreak = 0.20;
            }
            if (tradeMaxHalfLifeDays == null || tradeMaxHalfLifeDays <= 0) {
                tradeMaxHalfLifeDays = 15.0;
            }
            if (minRSquared == null || minRSquared < 0) {
                minRSquared = 0.70;
            }
            if (minTradeCount == null || minTradeCount < 0) {
                minTradeCount = 8;
            }
            if (adaptiveStop == null) {
                adaptiveStop = true;
            }
            if (adaptiveStopBase == null || adaptiveStopBase <= 0) {
                adaptiveStopBase = 2.5;
            }
            if (adaptiveStopCap == null || adaptiveStopCap < adaptiveStopBase) {
                adaptiveStopCap = 4.0;
            }
            if (cusumEnabled == null) {
                cusumEnabled = true;
            }
            if (cusumThreshold == null || cusumThreshold <= 0) {
                cusumThreshold = 5.0;
            }
            if (cusumDrift == null || cusumDrift < 0) {
                cusumDrift = 0.5;
            }
            if (cusumLookback == null || cusumLookback < 5) {
                cusumLookback = 40;
            }
        }

        public static RiskProperties defaults() {
            return new RiskProperties(3.5, 40, 0.5, 5, 1.0, 0.0, 90.0, 1.0, 0.08,
                    true, 0.02, 0.25, 1.5, 0.5, 0.75, 0.35, 0.20,
                    15.0, 0.70, 8, true, 2.5, 4.0, true, 5.0, 0.5, 40);
        }

        public boolean dynamicSizingEnabled() {
            return Boolean.TRUE.equals(dynamicSizing);
        }

        public boolean adaptiveStopEnabled() {
            return Boolean.TRUE.equals(adaptiveStop);
        }

        public boolean cusumEnabledFlag() {
            return Boolean.TRUE.equals(cusumEnabled);
        }
    }

    /**
     * Портфельные book'и: лимит открытых пар на сектор (диверсификация).
     */
    public record PortfolioProperties(
            Boolean diversifyBySector,
            Integer maxPairsPerSector
    ) {
        public PortfolioProperties {
            if (diversifyBySector == null) {
                diversifyBySector = true;
            }
            if (maxPairsPerSector == null || maxPairsPerSector < 1) {
                maxPairsPerSector = 2;
            }
        }

        public static PortfolioProperties defaults() {
            return new PortfolioProperties(true, 2);
        }

        public boolean diversifyBySectorEnabled() {
            return Boolean.TRUE.equals(diversifyBySector);
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
     * @param autoRunDaily    при true планировщик каждый торговый день гоняет анализ + paper sync
     * @param dailyCron       cron (по умолчанию пн–пт 19:05 Europe/Moscow wall clock JVM)
     * @param autoRunIntraday при true — часовой INTRADAY прогон в сессию (1H → paper)
     * @param intradayCron    cron (по умолчанию пн–пт :05 10–18)
     */
    public record PaperProperties(
            boolean enabled,
            double notionalPerLeg,
            String journalFile,
            Boolean autoRunDaily,
            String dailyCron,
            Boolean autoRunIntraday,
            String intradayCron,
            Double slippageBps,
            Boolean applyBorrow,
            Double notionalPerLegPct,
            Double slippageBpsDaily,
            Double slippageBpsIntraday
    ) {
        public PaperProperties {
            if (autoRunDaily == null) {
                autoRunDaily = true;
            }
            if (dailyCron == null || dailyCron.isBlank()) {
                dailyCron = "0 5 19 * * MON-FRI";
            }
            if (autoRunIntraday == null) {
                autoRunIntraday = true;
            }
            if (intradayCron == null || intradayCron.isBlank()) {
                intradayCron = "0 5 10-18 * * MON-FRI";
            }
            if (slippageBps == null || slippageBps < 0) {
                slippageBps = 20.0;
            }
            if (applyBorrow == null) {
                applyBorrow = true;
            }
            if (notionalPerLegPct == null || notionalPerLegPct <= 0) {
                notionalPerLegPct = 0.30;
            }
        }

        public static PaperProperties defaults() {
            return new PaperProperties(true, 100_000.0, "paper-journal.json", true, "0 5 19 * * MON-FRI",
                    true, "0 5 10-18 * * MON-FRI", 20.0, true, 0.30, 20.0, 40.0);
        }

        public boolean autoRunDailyEnabled() {
            return Boolean.TRUE.equals(autoRunDaily);
        }

        public boolean autoRunIntradayEnabled() {
            return Boolean.TRUE.equals(autoRunIntraday);
        }

        public boolean applyBorrowEnabled() {
            return Boolean.TRUE.equals(applyBorrow);
        }

        /** Базовый notional на ногу Y: % от equity или фикс из конфига. */
        public double baseNotionalPerLeg(double equityRub) {
            if (notionalPerLegPct != null && notionalPerLegPct > 0) {
                return equityRub * notionalPerLegPct;
            }
            return notionalPerLeg;
        }

        /** Slippage в долях для книги (DAILY / INTRADAY). */
        public double slippageFraction(com.moex.cointegration.model.BookKind book) {
            double bps = switch (book) {
                case DAILY -> slippageBpsDaily != null ? slippageBpsDaily : slippageBps;
                case INTRADAY -> slippageBpsIntraday != null ? slippageBpsIntraday : slippageBps * 2.0;
            };
            return bps / 10_000.0;
        }

        /** Доля notional на вход+выход (bps → fraction). */
        public double slippageFraction() {
            return slippageFraction(com.moex.cointegration.model.BookKind.DAILY);
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
            Boolean allowRelatedSectors,
            Double minPairTurnoverRub,
            Double maxTurnoverRatio
    ) {
        public UniverseProperties {
            if (sameSectorOnly == null) {
                sameSectorOnly = true;
            }
            if (allowRelatedSectors == null) {
                allowRelatedSectors = true;
            }
            if (minPairTurnoverRub == null || minPairTurnoverRub < 0) {
                minPairTurnoverRub = 50_000_000.0;
            }
            if (maxTurnoverRatio == null || maxTurnoverRatio < 1) {
                maxTurnoverRatio = 20.0;
            }
        }

        public static UniverseProperties defaults() {
            return new UniverseProperties(
                    true, 60, 50_000_000.0, 5.0, 0.15, true,
                    true, true, 50_000_000.0, 20.0
            );
        }

        public boolean sameSectorOnlyEnabled() {
            return Boolean.TRUE.equals(sameSectorOnly);
        }

        public boolean allowRelatedSectorsEnabled() {
            return Boolean.TRUE.equals(allowRelatedSectors);
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
