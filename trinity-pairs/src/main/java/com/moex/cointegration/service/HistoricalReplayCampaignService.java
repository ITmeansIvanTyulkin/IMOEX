package com.moex.cointegration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moex.cointegration.config.CapitalProperties;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.BookKind;
import com.moex.cointegration.model.HistoricalReplayCampaignReport;
import com.moex.cointegration.model.HistoricalReplayReport;
import com.moex.cointegration.universe.TierOneCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Пакетный исторический replay по списку пар (validation / OOS paper).
 */
@Service
public class HistoricalReplayCampaignService {

    private static final Logger log = LoggerFactory.getLogger(HistoricalReplayCampaignService.class);

    /** Ликвидные пары 1-го эшелона для validation-прогона. */
    public static final List<String[]> DEFAULT_VALIDATION_PAIRS = List.of(
            new String[]{"SBER", "VTBR"},
            new String[]{"LKOH", "ROSN"},
            new String[]{"GAZP", "NVTK"},
            new String[]{"CHMF", "NLMK"},
            new String[]{"SBER", "LKOH"},
            new String[]{"MOEX", "AFKS"},
            new String[]{"ALRS", "CHMF"},
            new String[]{"TATN", "ROSN"}
    );

    private final HistoricalReplayService replayService;
    private final MarketDataService marketDataService;
    private final ImoexProperties properties;
    private final CapitalProperties capitalProperties;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public HistoricalReplayCampaignService(
            HistoricalReplayService replayService,
            MarketDataService marketDataService,
            ImoexProperties properties,
            CapitalProperties capitalProperties
    ) {
        this.replayService = replayService;
        this.marketDataService = marketDataService;
        this.properties = properties;
        this.capitalProperties = capitalProperties;
    }

    public HistoricalReplayCampaignReport runAndSave(
            String label,
            BookKind book,
            LocalDate from,
            LocalDate to,
            List<String[]> pairs,
            String outputFileName,
            boolean downloadHourly
    ) throws IOException {
        if (downloadHourly && book == BookKind.INTRADAY) {
            List<String> tickers = pairs.stream()
                    .flatMap(p -> java.util.stream.Stream.of(p[0], p[1]))
                    .filter(TierOneCatalog::isTierOne)
                    .distinct()
                    .sorted()
                    .toList();
            log.info("Downloading hourly candles {} — {} for {} tickers", from, to, tickers.size());
            marketDataService.refreshHourlyCandles(tickers, from, to);
        }

        HistoricalReplayCampaignReport report = run(label, book, from, to, pairs);
        Path out = Path.of(properties.dataDir(), outputFileName);
        Files.createDirectories(out.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), report);
        log.info("Campaign {} saved → {} (net≈{} ₽, {}/{} pairs)",
                label, out, String.format(Locale.ROOT, "%.0f", report.totalNetPnlRub()),
                report.pairsCompleted(), report.pairsRequested());
        return report;
    }

    public HistoricalReplayCampaignReport run(
            String label,
            BookKind book,
            LocalDate from,
            LocalDate to,
            List<String[]> pairs
    ) throws IOException {
        List<HistoricalReplayReport> completed = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (String[] pair : pairs) {
            String y = pair[0].toUpperCase(Locale.ROOT);
            String x = pair[1].toUpperCase(Locale.ROOT);
            try {
                HistoricalReplayReport one = replayService.replayFromStorage(y, x, from, to, book);
                completed.add(one);
                log.info("  {} {}/{}: net={} ₽, closed={}, win={}%",
                        book, y, x,
                        String.format(Locale.ROOT, "%.0f", one.netPnlRub()),
                        one.tradesClosed(),
                        String.format(Locale.ROOT, "%.0f", one.winRate() * 100));
            } catch (Exception ex) {
                String msg = y + "/" + x + ": " + ex.getMessage();
                errors.add(msg);
                log.warn("Replay skip {}: {}", msg, ex.toString());
            }
        }

        double totalNet = completed.stream().mapToDouble(HistoricalReplayReport::netPnlRub).sum();
        double totalRealized = completed.stream().mapToDouble(HistoricalReplayReport::realizedPnlRub).sum();
        int opened = completed.stream().mapToInt(HistoricalReplayReport::tradesOpened).sum();
        int closed = completed.stream().mapToInt(HistoricalReplayReport::tradesClosed).sum();
        long wins = completed.stream()
                .flatMap(r -> r.entries().stream())
                .filter(e -> "CLOSED".equals(e.status()) && e.pnlRub() != null && e.pnlRub() > 0)
                .count();
        double aggWin = closed == 0 ? 0.0 : (double) wins / closed;

        return new HistoricalReplayCampaignReport(
                label,
                book,
                from,
                to,
                capitalProperties.equityRub(),
                pairs.size(),
                completed.size(),
                errors.size(),
                totalNet,
                totalRealized,
                opened,
                closed,
                aggWin,
                LocalDateTime.now(),
                completed,
                errors
        );
    }
}
