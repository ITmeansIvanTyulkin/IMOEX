package com.moex.cointegration.service;

import com.moex.cointegration.client.MoexIssClient;
import com.moex.cointegration.config.CapitalProperties;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.config.MicrostructureProperties;
import com.moex.cointegration.config.RegimeProperties;
import com.moex.cointegration.config.SessionProperties;
import com.moex.cointegration.model.BookKind;
import com.moex.cointegration.model.EventCalendarEntry;
import com.moex.cointegration.model.PairCashStats;
import com.moex.cointegration.model.PortfolioHistoricalReplayReport;
import com.moex.cointegration.storage.MarketDataStorage;
import com.moex.cointegration.universe.ResearchPairWhitelist;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DAILY whitelist campaigns (multi-pair metals — не одна «звезда»).
 * Запуск: {@code RUN_REPLAY_CAMPAIGN=1 mvn test -Dtest=HistoricalReplayCampaignRunnerTest}
 *
 * <p>Canonical commercial windows (честный {@link ResearchPairWhitelist#DAILY_METALS}):
 * 2023–2024 history + 2026 YTD — для лендинга/КП (research, не гарантия прибыли).
 */
@EnabledIfEnvironmentVariable(named = "RUN_REPLAY_CAMPAIGN", matches = "1")
class HistoricalReplayCampaignRunnerTest {

    private static final double EQUITY = 250_000.0;

    @Test
    void runDailyMetalsOnly() throws Exception {
        PortfolioHistoricalReplayReport daily = runMetalsCanonical(
                "WHITELIST DAILY METALS 2024-2025",
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2025, 12, 31),
                "replay-whitelist-daily-metals-2024-2025.json"
        );
        assertTrue(daily.barsProcessed() > 0);
    }

    @Test
    void runCanonicalMetals_2023_2024() throws Exception {
        PortfolioHistoricalReplayReport r = runMetalsCanonical(
                "CANONICAL DAILY METALS 2023-2024",
                LocalDate.of(2023, 1, 1),
                LocalDate.of(2024, 12, 31),
                "replay-canonical-daily-metals-2023-2024.json"
        );
        assertTrue(r.barsProcessed() > 0);
    }

    @Test
    void runCanonicalMetals_2026Ytd() throws Exception {
        LocalDate to = LocalDate.now();
        if (to.getYear() < 2026) {
            to = LocalDate.of(2026, 8, 4);
        }
        PortfolioHistoricalReplayReport r = runMetalsCanonical(
                "CANONICAL DAILY METALS 2026 YTD",
                LocalDate.of(2026, 1, 1),
                to,
                "replay-canonical-daily-metals-2026-ytd.json"
        );
        assertTrue(r.barsProcessed() > 0);
    }

    private static Path resolveDataDir() {
        Path local = Path.of("data").toAbsolutePath();
        if (java.nio.file.Files.isDirectory(local)) {
            return local;
        }
        Path parent = Path.of("..", "data").toAbsolutePath().normalize();
        if (java.nio.file.Files.isDirectory(parent)) {
            return parent;
        }
        return local;
    }

    private static PortfolioHistoricalReplayReport runMetalsCanonical(
            String label, LocalDate from, LocalDate to, String fileName
    ) throws Exception {
        Path dataDir = resolveDataDir();
        ImoexProperties props = whitelistProps(dataDir);
        PortfolioHistoricalReplayService portfolio = buildPortfolio(props);
        portfolio.setPairWhitelist(ResearchPairWhitelist.DAILY_METALS);

        System.out.printf(Locale.ROOT,
                "%s | whitelist=%d metals pairs | entryZ=1.75 hold=12 CUSUM=on equity=%.0f | %s → %s%n",
                label, ResearchPairWhitelist.DAILY_METALS.size(), EQUITY, from, to);

        PortfolioHistoricalReplayReport report = portfolio.replayAndSave(
                label, BookKind.DAILY, from, to, fileName
        );
        System.out.println("=== " + label + " ===");
        printPortfolioSummary(report);
        printMonthlyWinGoal(report, 1, "DAILY");
        return report;
    }

    private static ImoexProperties whitelistProps(Path dataDir) {
        return new ImoexProperties(
                "https://iss.moex.com/iss",
                "TQBR",
                "IMOEX",
                5,
                0.0005,
                ImoexProperties.CointegrationProperties.whitelistResearchDefaults(),
                ImoexProperties.NewsProperties.withoutRss(false, 10, 10, 3),
                dataDir.toString(),
                dataDir.resolve("charts").toString(),
                ImoexProperties.RiskProperties.whitelistResearchDefaults(),
                ImoexProperties.WalkForwardProperties.defaults(),
                ImoexProperties.PaperProperties.defaults(),
                ImoexProperties.UniverseProperties.researchDefaults(),
                ImoexProperties.PortfolioProperties.defaults(),
                ImoexProperties.AuthProperties.defaults()
        );
    }

    private static PortfolioHistoricalReplayService buildPortfolio(ImoexProperties props) throws Exception {
        CapitalProperties capital = new CapitalProperties(EQUITY, 1_000_000.0, 1.0, 0.40, 0.60);
        SessionProperties session = SessionProperties.defaults();
        MicrostructureProperties micro = MicrostructureProperties.researchDefaults();
        RegimeProperties regime = new RegimeProperties(false, 14, 20.0, 25.0, 0.5, "SNDX");
        MarketDataStorage storage = new MarketDataStorage(props);
        MoexIssClient moex = new MoexIssClient(new RestTemplate(), props);
        PreprocessingService preprocessing = new PreprocessingService();
        UniverseFilterService universe = new UniverseFilterService(storage, props);
        PairUniverseScanService scanner = new PairUniverseScanService(props, preprocessing, universe);
        MarketRegimeService regimeService = new MarketRegimeService(regime, props, moex, storage);
        RiskPolicyService risk = new RiskPolicyService(props, capital, regime, regimeService);
        EventCalendarRiskService events = new EventCalendarRiskService(session, List.<EventCalendarEntry>of());
        return new PortfolioHistoricalReplayService(
                props, capital, session, preprocessing, universe, scanner,
                risk, regimeService, events, micro, storage, new MonthlyClusterReviewService());
    }

    private static void printPortfolioSummary(PortfolioHistoricalReplayReport r) {
        System.out.printf(Locale.ROOT, "label=%s profile=%s book=%s %s — %s%n",
                r.label(), r.profile(), r.book(), r.from(), r.to());
        System.out.printf(Locale.ROOT, "equity %.0f → %.0f | net=%.0f ₽ realized=%.0f unrealized=%.0f maxDD=%.0f%n",
                r.equityStartRub(), r.equityEndRub(), r.netPnlRub(),
                r.realizedPnlRub(), r.unrealizedPnlRub(), r.maxDrawdownRub());
        System.out.printf(Locale.ROOT,
                "cash: E[trade]=%.1f ₽ avgWin=%.1f avgLoss=%.1f PF=%.2f | win=%.0f%% open=%d close=%d%n",
                r.expectancyRub(), r.avgWinRub(), r.avgLossRub(), r.profitFactor(),
                r.winRate() * 100, r.tradesOpened(), r.tradesClosed());
        System.out.printf(Locale.ROOT, "bars=%d activePairBars=%d slots=%d%n",
                r.barsProcessed(), r.barsWithFdrPairs(), r.maxPairsSlot());
        if (r.pairBreakdown() != null && !r.pairBreakdown().isEmpty()) {
            System.out.println("pair breakdown (by net PnL):");
            for (PairCashStats p : r.pairBreakdown()) {
                System.out.printf(Locale.ROOT,
                        "  %s/%s: net=%.0f closed=%d win=%.0f%% avg=%.1f dd=%.0f%n",
                        p.tickerY(), p.tickerX(), p.netPnlRub(), p.tradesClosed(),
                        p.winRate() * 100, p.avgPnlRub(), p.maxDrawdownRub());
            }
        }
    }

    private static void printMonthlyWinGoal(
            PortfolioHistoricalReplayReport r, int targetWinsPerMonth, String bookLabel
    ) {
        Map<YearMonth, Integer> winsByMonth = new LinkedHashMap<>();
        Map<YearMonth, Integer> closedByMonth = new LinkedHashMap<>();
        Map<YearMonth, Double> pnlByMonth = new LinkedHashMap<>();
        if (r.entries() != null) {
            for (var e : r.entries()) {
                if (!"CLOSED".equals(e.status()) || e.closedAt() == null || e.pnlRub() == null) {
                    continue;
                }
                YearMonth ym = YearMonth.from(e.closedAt());
                closedByMonth.merge(ym, 1, Integer::sum);
                pnlByMonth.merge(ym, e.pnlRub(), Double::sum);
                if (e.pnlRub() > 0) {
                    winsByMonth.merge(ym, 1, Integer::sum);
                }
            }
        }
        int monthsWithGoal = 0;
        int monthsGreen = 0;
        System.out.println(bookLabel + " monthly (target ≥" + targetWinsPerMonth + " wins):");
        for (YearMonth ym : closedByMonth.keySet().stream().sorted().toList()) {
            int wins = winsByMonth.getOrDefault(ym, 0);
            double pnl = pnlByMonth.getOrDefault(ym, 0.0);
            boolean ok = wins >= targetWinsPerMonth;
            if (ok) {
                monthsWithGoal++;
            }
            if (pnl > 0) {
                monthsGreen++;
            }
            System.out.printf(Locale.ROOT, "  %s: wins=%d closed=%d pnl=%.0f %s%n",
                    ym, wins, closedByMonth.get(ym), pnl, ok ? "OK" : "below");
        }
        System.out.printf(Locale.ROOT,
                "%s months≥%d wins: %d/%d | +PnL months: %d/%d%n",
                bookLabel, targetWinsPerMonth, monthsWithGoal, closedByMonth.size(),
                monthsGreen, closedByMonth.size());
    }
}
