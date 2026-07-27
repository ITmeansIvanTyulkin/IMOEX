package com.moex.cointegration.model;

import com.moex.cointegration.universe.SectorCatalog;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Итог ежемесячного пересмотра кластеров (секторов) по rolling cash PnL / PF.
 */
public record ClusterReviewReport(
        LocalDate asOf,
        LocalDate lookbackFrom,
        double minProfitFactor,
        int minClosedTrades,
        List<SectorClusterStats> sectors,
        Set<SectorCatalog.Sector> eligibleSectors
) {
    public record SectorClusterStats(
            SectorCatalog.Sector sector,
            int tradesClosed,
            double netPnlRub,
            double profitFactor,
            double winRate,
            boolean eligible,
            String reason
    ) {
    }

    public boolean sectorEligible(SectorCatalog.Sector sector) {
        return sector != null && eligibleSectors != null && eligibleSectors.contains(sector);
    }
}
