package com.moex.cointegration.service;

import com.moex.cointegration.config.ClusterReviewProperties;
import com.moex.cointegration.model.BookKind;
import com.moex.cointegration.model.ClusterReviewReport;
import com.moex.cointegration.model.FinalTradeDecision;
import com.moex.cointegration.model.PaperTradeEntry;
import com.moex.cointegration.model.TradingSignal;
import com.moex.cointegration.universe.SectorCatalog;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonthlyClusterReviewServiceTest {

    private final MonthlyClusterReviewService service =
            new MonthlyClusterReviewService(new ClusterReviewProperties(true, 6, 1.10, 4, true));

    @Test
    void oilGasAlwaysExcludedFromEligible() {
        LocalDate asOf = LocalDate.of(2025, 6, 15);
        List<PaperTradeEntry> journal = List.of(
                closed("LKOH", "ROSN", 5_000, asOf.minusMonths(1)),
                closed("LKOH", "ROSN", 3_000, asOf.minusMonths(2)),
                closed("LKOH", "ROSN", 2_000, asOf.minusMonths(3)),
                closed("LKOH", "ROSN", 1_000, asOf.minusMonths(4))
        );
        ClusterReviewReport report = service.review(journal, BookKind.DAILY, asOf);
        assertFalse(report.sectorEligible(SectorCatalog.Sector.OIL_GAS));
    }

    @Test
    void metalsEligibleWhenRollingNetPositiveAndPfAboveThreshold() {
        LocalDate asOf = LocalDate.of(2025, 6, 15);
        List<PaperTradeEntry> journal = List.of(
                closed("CHMF", "MAGN", 4_000, asOf.minusMonths(1)),
                closed("CHMF", "MAGN", 2_000, asOf.minusMonths(2)),
                closed("NLMK", "CHMF", -1_000, asOf.minusMonths(3)),
                closed("MAGN", "NLMK", 1_500, asOf.minusMonths(4))
        );
        ClusterReviewReport report = service.review(journal, BookKind.DAILY, asOf);
        assertTrue(report.sectorEligible(SectorCatalog.Sector.METALS_MINING));
    }

    @Test
    void sectorBlockedWhenRollingNetNegative() {
        LocalDate asOf = LocalDate.of(2025, 6, 15);
        List<PaperTradeEntry> journal = List.of(
                closed("SBER", "VTBR", -3_000, asOf.minusMonths(1)),
                closed("SBER", "VTBR", -2_000, asOf.minusMonths(2)),
                closed("SBER", "TCSG", 500, asOf.minusMonths(3)),
                closed("VTBR", "TCSG", -800, asOf.minusMonths(4))
        );
        ClusterReviewReport report = service.review(journal, BookKind.DAILY, asOf);
        assertFalse(report.sectorEligible(SectorCatalog.Sector.BANKS));
    }

    private static PaperTradeEntry closed(String y, String x, double pnl, LocalDate closedDay) {
        return new PaperTradeEntry(
                "t-" + y + "-" + x + "-" + closedDay,
                closedDay.atTime(10, 0),
                closedDay,
                y, x,
                TradingSignal.LONG_SPREAD,
                FinalTradeDecision.ENTER,
                2.0, 1.0, 30_000, 30_000, 1.0,
                "CLOSED",
                LocalDateTime.of(closedDay, java.time.LocalTime.of(18, 0)),
                0.2, 0.01, pnl,
                null, null, null, closedDay, "test",
                null, null, null, null, null, null, null, null, "mean-reversion", "DAILY"
        );
    }
}
