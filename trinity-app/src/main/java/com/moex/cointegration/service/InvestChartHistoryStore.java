package com.moex.cointegration.service;

import com.moex.cointegration.config.ImoexProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-machine SQLite candle archive for investments charts.
 * Lives under {@code imoex.data-dir} (durable OS path in release) so the archive
 * survives deleting an old app build and installing a new one. First login warms
 * max history; later sessions / new versions reuse the same file.
 */
@Service
public class InvestChartHistoryStore {

    private static final Logger log = LoggerFactory.getLogger(InvestChartHistoryStore.class);

    /** How long a series is considered fresh before we re-fetch from ISS/T-Invest. */
    public static final long SERIES_TTL_MS = 6L * 60L * 60L * 1000L;

    public static final int MAX_D1_YEARS = 5;
    public static final int MAX_H1_DAYS = 400;
    public static final int MAX_M5_DAYS = 20;
    public static final int MAX_M15_DAYS = 20;

    private final Path dbPath;
    private Connection conn;

    public InvestChartHistoryStore(ImoexProperties properties) {
        String dir = properties.dataDir() == null || properties.dataDir().isBlank()
                ? "data"
                : properties.dataDir();
        this.dbPath = Path.of(dir, "invest-chart.db");
    }

    @PostConstruct
    public void init() {
        try {
            com.moex.trinity.TrinityUserDataPaths.migrateInvestChartDbIfNeeded(dbPath);
            Files.createDirectories(dbPath.getParent());
            Class.forName("org.sqlite.JDBC");
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS invest_bars (
                          instrument TEXT NOT NULL,
                          tf TEXT NOT NULL,
                          t TEXT NOT NULL,
                          open REAL NOT NULL,
                          high REAL NOT NULL,
                          low REAL NOT NULL,
                          close REAL NOT NULL,
                          volume REAL NOT NULL DEFAULT 0,
                          PRIMARY KEY (instrument, tf, t)
                        )
                        """);
                st.execute("""
                        CREATE TABLE IF NOT EXISTS invest_series (
                          instrument TEXT NOT NULL,
                          tf TEXT NOT NULL,
                          source TEXT,
                          updated_at INTEGER NOT NULL,
                          PRIMARY KEY (instrument, tf)
                        )
                        """);
                st.execute("CREATE INDEX IF NOT EXISTS idx_invest_bars_inst_tf ON invest_bars(instrument, tf)");
            }
            log.info("Invest chart history SQLite ready: {}", dbPath.toAbsolutePath());
        } catch (Exception ex) {
            log.warn("Invest chart history DB init failed: {}", ex.getMessage());
            closeQuietly();
        }
    }

    @PreDestroy
    public void shutdown() {
        closeQuietly();
    }

    public boolean ready() {
        return conn != null;
    }

    public Path dbPath() {
        return dbPath;
    }

    /** Max calendar span we keep for a timeframe. */
    public static int maxDaysForTf(String tf) {
        String t = normTf(tf);
        return switch (t) {
            case "M5" -> MAX_M5_DAYS;
            case "M15" -> MAX_M15_DAYS;
            case "H1" -> MAX_H1_DAYS;
            case "W1", "WEEK" -> MAX_D1_YEARS * 365;
            case "MN", "MONTH" -> MAX_D1_YEARS * 365;
            default -> MAX_D1_YEARS * 365;
        };
    }

    public static int minBarsForTf(String tf) {
        String t = normTf(tf);
        return switch (t) {
            case "M5", "M15" -> 80;
            case "H1" -> 200;
            case "W1", "WEEK" -> 100;
            case "MN", "MONTH" -> 36;
            default -> 500;
        };
    }

    /**
     * Load bars from the shared table, optionally truncated to {@code years}/{@code since}.
     * Returns null when the series is missing or too thin / stale for the request.
     */
    public SeriesSnapshot load(String instrument, String tf, int years) {
        if (!ready()) {
            return null;
        }
        String inst = normInst(instrument);
        String tfN = normTf(tf);
        if (inst.isBlank()) {
            return null;
        }
        try {
            long updatedAt = 0L;
            String source = "DB";
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT source, updated_at FROM invest_series WHERE instrument=? AND tf=?")) {
                ps.setString(1, inst);
                ps.setString(2, tfN);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    source = rs.getString(1);
                    if (source == null || source.isBlank()) {
                        source = "DB";
                    }
                    updatedAt = rs.getLong(2);
                }
            }
            String since = sinceIso(tfN, years);
            List<Map<String, Object>> bars = new ArrayList<>();
            String sql = since == null
                    ? "SELECT t, open, high, low, close, volume FROM invest_bars WHERE instrument=? AND tf=? ORDER BY t"
                    : "SELECT t, open, high, low, close, volume FROM invest_bars WHERE instrument=? AND tf=? AND t>=? ORDER BY t";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, inst);
                ps.setString(2, tfN);
                if (since != null) {
                    ps.setString(3, since);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        bars.add(bar(
                                rs.getString(1),
                                rs.getDouble(2),
                                rs.getDouble(3),
                                rs.getDouble(4),
                                rs.getDouble(5),
                                rs.getDouble(6)
                        ));
                    }
                }
            }
            if (bars.isEmpty()) {
                return null;
            }
            return new SeriesSnapshot(List.copyOf(bars), source, updatedAt);
        } catch (Exception ex) {
            log.debug("invest history load {} {}: {}", instrument, tf, ex.getMessage());
            return null;
        }
    }

    /** True when shared series is fresh enough and deep enough for the request. */
    public boolean covers(SeriesSnapshot snap, String tf, int years) {
        if (snap == null || snap.bars().isEmpty()) {
            return false;
        }
        if (System.currentTimeMillis() - snap.updatedAt() > SERIES_TTL_MS) {
            return false;
        }
        if (snap.bars().size() < minBarsForTf(tf)) {
            return false;
        }
        int needDays = years > 0
                ? Math.min(years * 365, maxDaysForTf(tf))
                : Math.min(maxDaysForTf(tf), 90);
        // Allow ~70% coverage — weekends/holidays shrink calendar span.
        long needMs = (long) (needDays * 0.7 * 86_400_000L);
        String first = String.valueOf(snap.bars().get(0).get("time"));
        String last = String.valueOf(snap.bars().get(snap.bars().size() - 1).get("time"));
        long span = approxEpochMs(last) - approxEpochMs(first);
        return span >= needMs;
    }

    /**
     * Upsert bars into the shared table (keeps max history). Trims older than TF max span.
     */
    public void save(String instrument, String tf, String source, List<Map<String, Object>> bars) {
        if (!ready() || bars == null || bars.isEmpty()) {
            return;
        }
        String inst = normInst(instrument);
        String tfN = normTf(tf);
        if (inst.isBlank()) {
            return;
        }
        synchronized (this) {
            try {
                conn.setAutoCommit(false);
                try (PreparedStatement ups = conn.prepareStatement("""
                        INSERT INTO invest_bars(instrument, tf, t, open, high, low, close, volume)
                        VALUES(?,?,?,?,?,?,?,?)
                        ON CONFLICT(instrument, tf, t) DO UPDATE SET
                          open=excluded.open,
                          high=excluded.high,
                          low=excluded.low,
                          close=excluded.close,
                          volume=excluded.volume
                        """)) {
                    for (Map<String, Object> b : bars) {
                        if (b == null) {
                            continue;
                        }
                        Object time = b.get("time");
                        if (time == null) {
                            continue;
                        }
                        String t = String.valueOf(time);
                        if (t.isBlank()) {
                            continue;
                        }
                        ups.setString(1, inst);
                        ups.setString(2, tfN);
                        ups.setString(3, t);
                        ups.setDouble(4, asDouble(b.get("open")));
                        ups.setDouble(5, asDouble(b.get("high")));
                        ups.setDouble(6, asDouble(b.get("low")));
                        ups.setDouble(7, asDouble(b.get("close")));
                        ups.setDouble(8, asDouble(b.get("volume")));
                        ups.addBatch();
                    }
                    ups.executeBatch();
                }
                // Drop bars older than the TF max window.
                String cutoff = LocalDate.now().minusDays(maxDaysForTf(tfN) + 14).toString();
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM invest_bars WHERE instrument=? AND tf=? AND t<?")) {
                    del.setString(1, inst);
                    del.setString(2, tfN);
                    del.setString(3, cutoff);
                    del.executeUpdate();
                }
                try (PreparedStatement meta = conn.prepareStatement("""
                        INSERT INTO invest_series(instrument, tf, source, updated_at)
                        VALUES(?,?,?,?)
                        ON CONFLICT(instrument, tf) DO UPDATE SET
                          source=excluded.source,
                          updated_at=excluded.updated_at
                        """)) {
                    meta.setString(1, inst);
                    meta.setString(2, tfN);
                    meta.setString(3, source == null || source.isBlank() ? "LIVE" : source);
                    meta.setLong(4, System.currentTimeMillis());
                    meta.executeUpdate();
                }
                conn.commit();
            } catch (Exception ex) {
                try {
                    conn.rollback();
                } catch (Exception ignored) {
                }
                log.debug("invest history save {} {}: {}", instrument, tf, ex.getMessage());
            } finally {
                try {
                    conn.setAutoCommit(true);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public Map<String, Object> stats(String instrument, String tf) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instrument", normInst(instrument));
        out.put("tf", normTf(tf));
        out.put("ready", ready());
        if (!ready()) {
            return out;
        }
        SeriesSnapshot snap = load(instrument, tf, 0);
        if (snap == null) {
            out.put("bars", 0);
            return out;
        }
        out.put("bars", snap.bars().size());
        out.put("source", snap.source());
        out.put("updatedAt", snap.updatedAt());
        if (!snap.bars().isEmpty()) {
            out.put("from", snap.bars().get(0).get("time"));
            out.put("to", snap.bars().get(snap.bars().size() - 1).get("time"));
        }
        return out;
    }

    private static String sinceIso(String tf, int years) {
        int days;
        if (years > 0) {
            days = Math.min(years * 365, maxDaysForTf(tf));
        } else {
            // Default short window when years omitted — still served from full store.
            String t = normTf(tf);
            days = switch (t) {
                case "M5", "M15" -> maxDaysForTf(t);
                case "H1" -> 90;
                default -> 500;
            };
        }
        return LocalDate.now().minusDays(days).toString();
    }

    private static long approxEpochMs(String time) {
        if (time == null || time.isBlank()) {
            return 0L;
        }
        try {
            if (time.length() >= 10) {
                return LocalDate.parse(time.substring(0, 10)).toEpochDay() * 86_400_000L;
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }

    private static Map<String, Object> bar(
            String time, double open, double high, double low, double close, double volume
    ) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time", time);
        m.put("open", open);
        m.put("high", high);
        m.put("low", low);
        m.put("close", close);
        m.put("volume", volume);
        return m;
    }

    private static double asDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v == null) {
            return 0;
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String normInst(String instrument) {
        return instrument == null ? "" : instrument.trim().toUpperCase(Locale.ROOT);
    }

    private static String normTf(String tf) {
        if (tf == null || tf.isBlank()) {
            return "D1";
        }
        String t = tf.trim().toUpperCase(Locale.ROOT);
        if ("DAY".equals(t) || "1D".equals(t)) {
            return "D1";
        }
        if ("WEEK".equals(t) || "1W".equals(t)) {
            return "W1";
        }
        if ("MONTH".equals(t) || "1M".equals(t) || "MO".equals(t)) {
            return "MN";
        }
        return t;
    }

    private void closeQuietly() {
        if (conn != null) {
            try {
                conn.close();
            } catch (Exception ignored) {
            }
            conn = null;
        }
    }

    public record SeriesSnapshot(List<Map<String, Object>> bars, String source, long updatedAt) {
    }
}
