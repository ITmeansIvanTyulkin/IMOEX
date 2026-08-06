package com.moex.cointegration.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moex.cointegration.config.ImoexProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Research paper statement for trend (BR) — closed trades with cash PnL.
 * Separate from pairs {@code paper-journal.json}; file: {@code data/trend-paper-journal.json}.
 */
@Service
@ConditionalOnProperty(prefix = "imoex.strategies.trend", name = "enabled", havingValue = "true")
public class TrendPaperJournalService {

    private static final Logger log = LoggerFactory.getLogger(TrendPaperJournalService.class);
    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");

    private final Path journalFile;
    private final ObjectMapper mapper;
    private final ReentrantLock lock = new ReentrantLock();
    private JournalFile data = JournalFile.empty();

    public TrendPaperJournalService(ImoexProperties imoexProperties) {
        this.journalFile = Path.of(imoexProperties.dataDir(), "trend-paper-journal.json");
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        load();
    }

    public synchronized JournalFile journal() {
        return data;
    }

    public Map<String, Object> statement() {
        List<Trade> trades = data.trades();
        LocalDate today = LocalDate.now(MSK);
        double realized = 0;
        double todayPnl = 0;
        int wins = 0;
        int losses = 0;
        int be = 0;
        for (Trade t : trades) {
            double pnl = t.pnlRub() == null ? 0 : t.pnlRub();
            realized += pnl;
            if (pnl > 0) {
                wins++;
            } else if (pnl < 0) {
                losses++;
            } else {
                be++;
            }
            LocalDate closeDay = parseDay(t.closedAt());
            if (today.equals(closeDay)) {
                todayPnl += pnl;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("closedCount", trades.size());
        m.put("wins", wins);
        m.put("losses", losses);
        m.put("breakeven", be);
        m.put("realizedPnlRub", round2(realized));
        m.put("today", today.toString());
        m.put("todayPnlRub", round2(todayPnl));
        m.put("instrument", data.instrument());
        m.put("equityRub", data.equityRub());
        m.put("updatedAt", data.updatedAt());
        m.put("note", data.note());
        return m;
    }

    /** Desk payload fragment. */
    public Map<String, Object> deskDto() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("statement", statement());
        List<Map<String, Object>> recent = new ArrayList<>();
        List<Trade> trades = data.trades();
        int from = Math.max(0, trades.size() - 8);
        for (int i = trades.size() - 1; i >= from; i--) {
            recent.add(tradeDto(trades.get(i)));
        }
        m.put("recentTrades", recent);
        return m;
    }

    public synchronized Trade record(Trade trade) {
        if (trade == null || trade.id() == null || trade.id().isBlank()) {
            throw new IllegalArgumentException("trade.id required");
        }
        lock.lock();
        try {
            List<Trade> next = new ArrayList<>(data.trades());
            next.removeIf(t -> trade.id().equals(t.id()));
            next.add(trade);
            data = data.withTrades(next);
            save();
            log.info("Trend paper trade recorded id={} pnl={}+ ₽", trade.id(), trade.pnlRub());
            return trade;
        } finally {
            lock.unlock();
        }
    }

    public synchronized int upsertAll(List<Trade> trades) {
        if (trades == null || trades.isEmpty()) {
            return 0;
        }
        lock.lock();
        try {
            List<Trade> next = new ArrayList<>(data.trades());
            int n = 0;
            for (Trade trade : trades) {
                if (trade == null || trade.id() == null || trade.id().isBlank()) {
                    continue;
                }
                next.removeIf(t -> trade.id().equals(t.id()));
                next.add(trade);
                n++;
            }
            data = data.withTrades(next).withNote(
                    "Paper statement (research). Includes MISSED_* backfills when HTF blocked live.");
            save();
            return n;
        } finally {
            lock.unlock();
        }
    }

    public synchronized void reload() {
        load();
    }

    private void load() {
        try {
            if (Files.isRegularFile(journalFile)) {
                JournalFile loaded = mapper.readValue(journalFile.toFile(), JournalFile.class);
                data = loaded == null ? JournalFile.empty() : loaded;
            }
        } catch (Exception ex) {
            log.warn("Could not load trend paper journal {}: {}", journalFile, ex.getMessage());
            data = JournalFile.empty();
        }
    }

    private void save() {
        try {
            Files.createDirectories(journalFile.getParent());
            data = data.withUpdatedAt(LocalDateTime.now().toString());
            // Keep a computed statement block in the file for ops/eyeballing
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("updatedAt", data.updatedAt());
            wire.put("currency", data.currency());
            wire.put("instrument", data.instrument());
            wire.put("rubPerPoint", data.rubPerPoint());
            wire.put("pointSize", data.pointSize());
            wire.put("equityRub", data.equityRub());
            wire.put("note", data.note());
            wire.put("trades", data.trades());
            wire.put("statement", statement());
            mapper.writerWithDefaultPrettyPrinter().writeValue(journalFile.toFile(), wire);
        } catch (Exception ex) {
            log.warn("Could not save trend paper journal {}: {}", journalFile, ex.getMessage());
        }
    }

    private static Map<String, Object> tradeDto(Trade t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("instrument", t.instrument());
        m.put("side", t.side());
        m.put("mode", t.mode());
        m.put("tag", t.tag());
        m.put("qty", t.qty());
        m.put("entryPrice", t.entryPrice());
        m.put("exitPrice", t.exitPrice());
        m.put("openedAt", t.openedAt());
        m.put("closedAt", t.closedAt());
        m.put("exitReason", t.exitReason());
        m.put("pnlRub", t.pnlRub());
        m.put("notes", t.notes());
        return m;
    }

    private static LocalDate parseDay(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            if (iso.contains("T")) {
                if (iso.endsWith("Z") || iso.contains("+") || iso.lastIndexOf('-') > 10) {
                    return OffsetDateTime.parse(iso).atZoneSameInstant(MSK).toLocalDate();
                }
                return LocalDateTime.parse(iso.substring(0, Math.min(19, iso.length()))).toLocalDate();
            }
            return LocalDate.parse(iso.substring(0, 10));
        } catch (Exception ex) {
            try {
                return LocalDate.parse(iso.substring(0, 10));
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Trade(
            String id,
            String instrument,
            String side,
            String mode,
            String tag,
            Integer qty,
            Double entryPrice,
            Double exitPrice,
            Double stopLoss,
            Double tp1,
            Double tp2,
            String openedAt,
            String closedAt,
            String exitReason,
            Double pnlRub,
            String notes
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JournalFile(
            String updatedAt,
            String currency,
            String instrument,
            Double rubPerPoint,
            Double pointSize,
            Double equityRub,
            String note,
            List<Trade> trades
    ) {
        public JournalFile {
            trades = trades == null ? List.of() : List.copyOf(trades);
            if (currency == null || currency.isBlank()) {
                currency = "RUB";
            }
            if (instrument == null || instrument.isBlank()) {
                instrument = "BRU6";
            }
            if (rubPerPoint == null) {
                rubPerPoint = 7.0;
            }
            if (pointSize == null) {
                pointSize = 0.01;
            }
            if (equityRub == null) {
                equityRub = 100_000.0;
            }
        }

        static JournalFile empty() {
            return new JournalFile(LocalDateTime.now().toString(), "RUB", "BRU6",
                    7.0, 0.01, 100_000.0, null, List.of());
        }

        JournalFile withTrades(List<Trade> next) {
            return new JournalFile(updatedAt, currency, instrument, rubPerPoint, pointSize, equityRub, note, next);
        }

        JournalFile withUpdatedAt(String at) {
            return new JournalFile(at, currency, instrument, rubPerPoint, pointSize, equityRub, note, trades);
        }

        JournalFile withNote(String n) {
            return new JournalFile(updatedAt, currency, instrument, rubPerPoint, pointSize, equityRub, n, trades);
        }
    }
}
