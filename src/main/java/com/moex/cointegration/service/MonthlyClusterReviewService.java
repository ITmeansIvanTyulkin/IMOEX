package com.moex.cointegration.service;

import com.moex.cointegration.config.ClusterReviewProperties;
import com.moex.cointegration.model.BookKind;
import com.moex.cointegration.model.ClusterReviewReport;
import com.moex.cointegration.model.PairAnalysisResult;
import com.moex.cointegration.model.PaperTradeEntry;
import com.moex.cointegration.universe.SectorCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Ежемесячный пересмотр кластеров: EG/FDR/quality остаются снаружи;
 * здесь — sector cash PnL / PF за lookback + hard ban OIL_GAS для DAILY pairs.
 */
@Service
public class MonthlyClusterReviewService {

    private static final Logger log = LoggerFactory.getLogger(MonthlyClusterReviewService.class);

    private final ClusterReviewProperties properties;

    public MonthlyClusterReviewService(ClusterReviewProperties properties) {
        this.properties = properties;
    }

    public MonthlyClusterReviewService() {
        this(ClusterReviewProperties.defaults());
    }

    public ClusterReviewProperties properties() {
        return properties;
    }

    public ClusterReviewReport review(
            List<PaperTradeEntry> journal,
            BookKind book,
            LocalDate asOf
    ) {
        LocalDate from = asOf.minusMonths(properties.lookbackMonths());
        Map<SectorCatalog.Sector, List<Double>> pnls = new EnumMap<>(SectorCatalog.Sector.class);

        if (journal != null) {
            for (PaperTradeEntry e : journal) {
                if (e == null || !"CLOSED".equals(e.status()) || e.pnlRub() == null) {
                    continue;
                }
                if (book != null && e.book() != null && !book.name().equalsIgnoreCase(e.book())) {
                    continue;
                }
                LocalDate closed = closedDate(e);
                if (closed == null || closed.isBefore(from) || closed.isAfter(asOf)) {
                    continue;
                }
                Optional<SectorCatalog.Sector> sector = pairSector(e.tickerY(), e.tickerX());
                if (sector.isEmpty()) {
                    continue;
                }
                pnls.computeIfAbsent(sector.get(), s -> new ArrayList<>()).add(e.pnlRub());
            }
        }

        List<ClusterReviewReport.SectorClusterStats> rows = new ArrayList<>();
        Set<SectorCatalog.Sector> eligible = EnumSet.noneOf(SectorCatalog.Sector.class);

        for (SectorCatalog.Sector sector : SectorCatalog.Sector.values()) {
            List<Double> list = pnls.getOrDefault(sector, List.of());
            if (properties.excludeOilGasFlag() && sector == SectorCatalog.Sector.OIL_GAS) {
                rows.add(new ClusterReviewReport.SectorClusterStats(
                        sector, list.size(), sum(list), profitFactor(list), winRate(list),
                        false, "OIL_GAS excluded from DAILY pairs (roadmap futures/options)"));
                continue;
            }
            if (list.size() < properties.minClosedTrades()) {
                // мало истории — сектор допускаем (кроме нефти), пока не наберётся статистика
                boolean ok = true;
                rows.add(new ClusterReviewReport.SectorClusterStats(
                        sector, list.size(), sum(list), profitFactor(list), winRate(list),
                        ok, "insufficient history (<" + properties.minClosedTrades() + ") — provisional allow"));
                if (ok) {
                    eligible.add(sector);
                }
                continue;
            }
            double net = sum(list);
            double pf = profitFactor(list);
            boolean ok = net > 0 && pf >= properties.minProfitFactor();
            String reason = ok
                    ? "rolling net>0 and PF≥" + properties.minProfitFactor()
                    : String.format("blocked: net=%.0f PF=%.2f (need net>0 and PF≥%.2f)",
                    net, pf, properties.minProfitFactor());
            rows.add(new ClusterReviewReport.SectorClusterStats(
                    sector, list.size(), net, pf, winRate(list), ok, reason));
            if (ok) {
                eligible.add(sector);
            }
        }

        rows.sort(Comparator.comparingDouble(ClusterReviewReport.SectorClusterStats::netPnlRub).reversed());
        ClusterReviewReport report = new ClusterReviewReport(
                asOf, from, properties.minProfitFactor(), properties.minClosedTrades(), rows, eligible);
        log.info("Cluster review asOf={}: eligible={} / {} sectors (lookback {}m, minPF={})",
                asOf, eligible.size(), rows.size(), properties.lookbackMonths(), properties.minProfitFactor());
        return report;
    }

    /**
     * Оставляет пары из eligible-секторов; OIL_GAS всегда режется при excludeOilGas.
     * Пары с достаточной собственной историей: только rolling net>0 и PF≥ порога.
     */
    public List<PairAnalysisResult> filterPairs(
            List<PairAnalysisResult> pairs,
            ClusterReviewReport review,
            List<PaperTradeEntry> journal,
            BookKind book,
            LocalDate asOf
    ) {
        if (pairs == null || pairs.isEmpty()) {
            return List.of();
        }
        if (!properties.enabledFlag() || review == null) {
            return pairs.stream()
                    .filter(p -> !blockedOil(p.tickerY(), p.tickerX()))
                    .toList();
        }

        LocalDate from = review.lookbackFrom();
        Map<String, List<Double>> pairPnls = pairPnlMap(journal, book, from, asOf);

        List<PairAnalysisResult> out = new ArrayList<>();
        for (PairAnalysisResult p : pairs) {
            if (blockedOil(p.tickerY(), p.tickerX())) {
                continue;
            }
            Optional<SectorCatalog.Sector> sector = pairSector(p.tickerY(), p.tickerX());
            if (sector.isPresent() && !review.sectorEligible(sector.get())) {
                continue;
            }
            String key = pairKey(p.tickerY(), p.tickerX());
            List<Double> hist = pairPnls.getOrDefault(key, List.of());
            if (hist.size() >= properties.minClosedTrades()) {
                double net = sum(hist);
                double pf = profitFactor(hist);
                if (!(net > 0 && pf >= properties.minProfitFactor())) {
                    continue;
                }
            }
            out.add(p);
        }
        return out;
    }

    private boolean blockedOil(String y, String x) {
        if (!properties.excludeOilGasFlag()) {
            return false;
        }
        return pairSector(y, x).map(s -> s == SectorCatalog.Sector.OIL_GAS).orElse(false);
    }

    private static Optional<SectorCatalog.Sector> pairSector(String y, String x) {
        Optional<SectorCatalog.Sector> a = SectorCatalog.sectorOf(y);
        Optional<SectorCatalog.Sector> b = SectorCatalog.sectorOf(x);
        if (a.isPresent() && b.isPresent() && a.get() == b.get()) {
            return a;
        }
        return a.isPresent() ? a : b;
    }

    private static LocalDate closedDate(PaperTradeEntry e) {
        if (e.closedAt() != null) {
            return e.closedAt().toLocalDate();
        }
        if (e.asOfDate() != null) {
            return e.asOfDate();
        }
        return null;
    }

    private static Map<String, List<Double>> pairPnlMap(
            List<PaperTradeEntry> journal, BookKind book, LocalDate from, LocalDate asOf
    ) {
        Map<String, List<Double>> map = new HashMap<>();
        if (journal == null) {
            return map;
        }
        for (PaperTradeEntry e : journal) {
            if (e == null || !"CLOSED".equals(e.status()) || e.pnlRub() == null) {
                continue;
            }
            if (book != null && e.book() != null && !book.name().equalsIgnoreCase(e.book())) {
                continue;
            }
            LocalDate closed = closedDate(e);
            if (closed == null || closed.isBefore(from) || closed.isAfter(asOf)) {
                continue;
            }
            map.computeIfAbsent(pairKey(e.tickerY(), e.tickerX()), k -> new ArrayList<>()).add(e.pnlRub());
        }
        return map;
    }

    private static String pairKey(String y, String x) {
        if (y == null || x == null) {
            return "";
        }
        String a = y.toUpperCase();
        String b = x.toUpperCase();
        return a.compareTo(b) <= 0 ? a + "/" + b : b + "/" + a;
    }

    private static double sum(List<Double> pnls) {
        return pnls.stream().mapToDouble(Double::doubleValue).sum();
    }

    private static double winRate(List<Double> pnls) {
        if (pnls.isEmpty()) {
            return 0;
        }
        long wins = pnls.stream().filter(p -> p > 0).count();
        return (double) wins / pnls.size();
    }

    private static double profitFactor(List<Double> pnls) {
        double gw = pnls.stream().filter(p -> p > 0).mapToDouble(Double::doubleValue).sum();
        double gl = Math.abs(pnls.stream().filter(p -> p < 0).mapToDouble(Double::doubleValue).sum());
        if (gl < 1e-9) {
            return gw > 0 ? Double.POSITIVE_INFINITY : 0.0;
        }
        return gw / gl;
    }
}
