package com.moex.cointegration.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moex.cointegration.config.DeskCloudSessionStore;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.MarketRegimeSnapshot;
import com.moex.cointegration.model.PaperJournal;
import com.moex.cointegration.model.PaperTradeEntry;
import com.moex.trinity.marketdata.PlainHttp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pushes paper/regime snapshot from this desk into Supabase {@code desk_snapshots}
 * so the public cabinet can read live metrics.
 */
@Service
@ConditionalOnProperty(prefix = "imoex.auth.supabase", name = "enabled", havingValue = "true")
public class DeskSnapshotPublisher {

    private static final Logger log = LoggerFactory.getLogger(DeskSnapshotPublisher.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final int UPSTREAM_TIMEOUT_MS = 20_000;

    private final ImoexProperties properties;
    private final DeskCloudSessionStore cloudSessions;
    private final ObjectMapper mapper;
    private final ObjectProvider<PaperTradingService> pairsPaper;
    private final ObjectProvider<TrendPaperJournalService> trendPaper;
    private final ObjectProvider<CalendarArbPaperJournalService> arbPaper;
    private final ObjectProvider<MarketRegimeService> regimeService;

    private final AtomicReference<String> lastOkAt = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final AtomicReference<Integer> lastClosed = new AtomicReference<>();

    public DeskSnapshotPublisher(
            ImoexProperties properties,
            DeskCloudSessionStore cloudSessions,
            ObjectMapper mapper,
            ObjectProvider<PaperTradingService> pairsPaper,
            ObjectProvider<TrendPaperJournalService> trendPaper,
            ObjectProvider<CalendarArbPaperJournalService> arbPaper,
            ObjectProvider<MarketRegimeService> regimeService
    ) {
        this.properties = properties;
        this.cloudSessions = cloudSessions;
        this.mapper = mapper;
        this.pairsPaper = pairsPaper;
        this.trendPaper = trendPaper;
        this.arbPaper = arbPaper;
        this.regimeService = regimeService;
    }

    @Scheduled(fixedDelayString = "${imoex.auth.supabase.snapshot-ms:60000}", initialDelay = 15_000L)
    public void scheduledPublish() {
        if (!cloudSessions.hasSession()) {
            return;
        }
        try {
            publishNow();
        } catch (Exception e) {
            lastError.set(e.getMessage() == null ? e.toString() : e.getMessage());
            log.warn("Desk snapshot publish failed: {}", lastError.get());
        }
    }

    public Map<String, Object> status() {
        DeskCloudSessionStore.Session s = cloudSessions.current();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", true);
        out.put("hasSession", cloudSessions.hasSession());
        out.put("email", s == null ? "" : s.email());
        out.put("userId", s == null ? "" : s.userId());
        out.put("lastOkAt", lastOkAt.get());
        out.put("lastError", lastError.get());
        out.put("lastClosedCount", lastClosed.get());
        return out;
    }

    public synchronized Map<String, Object> publishNow() throws Exception {
        var sb = properties.auth().supabase();
        if (!sb.enabled() || sb.url() == null || sb.url().isBlank()) {
            throw new IllegalStateException("Supabase выключен");
        }
        DeskCloudSessionStore.Session session = ensureFreshToken();
        if (session == null || session.userId() == null || session.userId().isBlank()) {
            throw new IllegalStateException(
                    "Нет сессии кабинета на столе. Войдите email/паролем в Настройках /view."
            );
        }

        Map<String, Object> row = buildRow(session.userId());
        String base = sb.url().endsWith("/") ? sb.url().substring(0, sb.url().length() - 1) : sb.url();
        String anon = sb.anonKey() == null ? "" : sb.anonKey().trim();
        byte[] body = mapper.writeValueAsBytes(List.of(row));

        PlainHttp.Reply res = PlainHttp.exchange(
                "POST",
                base + "/rest/v1/desk_snapshots?on_conflict=user_id",
                UPSTREAM_TIMEOUT_MS,
                "TRINITY-desk/1.0",
                "application/json",
                body,
                Map.of(
                        "apikey", anon,
                        "Authorization", "Bearer " + session.accessToken(),
                        "Prefer", "resolution=merge-duplicates,return=minimal"
                )
        );
        if (res.status() < 200 || res.status() >= 300) {
            String msg = "Supabase HTTP " + res.status() + ": " + abbreviate(res.body());
            lastError.set(msg);
            throw new IllegalStateException(msg);
        }
        lastOkAt.set(Instant.now().toString());
        lastError.set(null);
        Object closed = row.get("closed_count");
        if (closed instanceof Number n) {
            lastClosed.set(n.intValue());
        }
        log.info("Desk snapshot upserted (closed={})", closed);
        Map<String, Object> out = status();
        out.put("ok", true);
        out.put("closedCount", closed);
        return out;
    }

    Map<String, Object> buildRow(String userId) {
        PaperJournal pairs = null;
        try {
            PaperTradingService svc = pairsPaper.getIfAvailable();
            if (svc != null) {
                pairs = svc.summary();
            }
        } catch (Exception e) {
            log.debug("pairs journal unavailable: {}", e.toString());
        }

        TrendPaperJournalService.JournalFile trend = null;
        Map<String, Object> trendSt = Map.of();
        try {
            TrendPaperJournalService svc = trendPaper.getIfAvailable();
            if (svc != null) {
                trend = svc.journal();
                trendSt = svc.statement();
            }
        } catch (Exception e) {
            log.debug("trend journal unavailable: {}", e.toString());
        }

        Map<String, Object> arbSt = Map.of();
        List<Map<String, Object>> arbTrades = List.of();
        try {
            CalendarArbPaperJournalService svc = arbPaper.getIfAvailable();
            if (svc != null) {
                arbSt = svc.statement();
                arbTrades = svc.allTradeDtos();
            }
        } catch (Exception e) {
            log.debug("arb journal unavailable: {}", e.toString());
        }

        MarketRegimeSnapshot regime = MarketRegimeSnapshot.unknown();
        try {
            MarketRegimeService svc = regimeService.getIfAvailable();
            if (svc != null) {
                regime = svc.currentOrRefresh();
            }
        } catch (Exception e) {
            log.debug("regime unavailable: {}", e.toString());
        }

        List<Leg> legs = new ArrayList<>();
        if (pairs != null && pairs.entries() != null) {
            for (PaperTradeEntry e : pairs.entries()) {
                legs.add(Leg.fromPairs(e));
            }
        }
        if (trend != null && trend.trades() != null) {
            for (TrendPaperJournalService.Trade t : trend.trades()) {
                legs.add(Leg.fromTrend(t));
            }
        }
        if (arbTrades != null) {
            for (Map<String, Object> t : arbTrades) {
                legs.add(Leg.fromArbMap(t));
            }
        }

        List<Leg> closed = legs.stream()
                .filter(l -> "CLOSED".equals(l.status))
                .sorted(Comparator.comparing(l -> l.t == null ? "" : l.t))
                .toList();
        List<Double> equity = new ArrayList<>();
        double acc = 0;
        for (Leg l : closed) {
            acc += l.pnl;
            equity.add(round2(acc));
        }

        List<Leg> opens = legs.stream().filter(l -> "OPEN".equals(l.status)).toList();
        List<Map<String, Object>> slots = new ArrayList<>();
        for (Leg l : opens) {
            slots.add(l.toSlot());
        }
        List<Leg> recentClosed = new ArrayList<>(closed);
        recentClosed.sort(Comparator.comparing((Leg l) -> l.t == null ? "" : l.t).reversed());
        for (int i = 0; i < Math.min(8, recentClosed.size()); i++) {
            slots.add(recentClosed.get(i).toSlot());
        }

        double realizedPairs = pairs != null && pairs.realizedPnlRub() != null ? pairs.realizedPnlRub() : 0;
        double realizedTrend = num(trendSt.get("realizedPnlRub"));
        double realizedArb = num(arbSt.get("realizedPnlRub"));
        boolean hasStatement =
                (pairs != null && pairs.realizedPnlRub() != null)
                        || trendSt.containsKey("realizedPnlRub")
                        || arbSt.containsKey("realizedPnlRub");
        double realized = hasStatement ? (realizedPairs + realizedTrend + realizedArb) : acc;

        double unrealized = pairs != null && pairs.unrealizedPnlRub() != null ? pairs.unrealizedPnlRub() : 0;
        for (Leg l : opens) {
            unrealized += l.unrealized;
        }

        int openCount = pairs != null && pairs.openCount() != null
                ? pairs.openCount()
                : opens.size();
        int closedFromSt =
                (pairs != null && pairs.closedCount() != null ? pairs.closedCount() : 0)
                        + (int) num(trendSt.get("closedCount"))
                        + (int) num(arbSt.get("closedCount"));
        int closedCount = closedFromSt > 0 ? closedFromSt : closed.size();

        String detail = regime.detail() == null ? "" : regime.detail().trim();
        if (!detail.isEmpty() && !detail.endsWith(".") && !detail.endsWith("!") && !detail.endsWith("?")) {
            detail = detail + ".";
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("user_id", userId);
        row.put("updated_at", Instant.now().toString());
        row.put("regime_label", regime.label() == null ? "UNKNOWN" : regime.label());
        row.put("regime_note", detail);
        row.put("book", "DAILY");
        row.put("realized_pnl_rub", round2(realized));
        row.put("unrealized_pnl_rub", round2(unrealized));
        row.put("open_count", openCount);
        row.put("closed_count", closedCount);
        row.put("open_slots", slots);
        row.put("equity_points", equity);
        return row;
    }

    private DeskCloudSessionStore.Session ensureFreshToken() throws Exception {
        DeskCloudSessionStore.Session s = cloudSessions.current();
        if (s == null) {
            return null;
        }
        if (!s.accessExpired(90) && s.userId() != null && !s.userId().isBlank()) {
            return s;
        }
        if (s.refreshToken() == null || s.refreshToken().isBlank()) {
            return s.accessExpired(0) ? null : s;
        }
        var sb = properties.auth().supabase();
        String base = sb.url().endsWith("/") ? sb.url().substring(0, sb.url().length() - 1) : sb.url();
        String anon = sb.anonKey() == null ? "" : sb.anonKey().trim();
        byte[] payload = mapper.writeValueAsBytes(Map.of("refresh_token", s.refreshToken()));
        PlainHttp.Reply res = PlainHttp.exchange(
                "POST",
                base + "/auth/v1/token?grant_type=refresh_token",
                UPSTREAM_TIMEOUT_MS,
                "TRINITY-desk/1.0",
                "application/json",
                payload,
                Map.of(
                        "apikey", anon,
                        "Authorization", "Bearer " + anon
                )
        );
        Map<String, Object> body = mapper.readValue(res.body() == null ? "{}" : res.body(), MAP);
        if (res.status() < 200 || res.status() >= 300
                || !(body.get("access_token") instanceof String access)
                || access.isBlank()) {
            throw new IllegalStateException(
                    "Не удалось обновить сессию кабинета: HTTP " + res.status()
            );
        }
        String refresh = body.get("refresh_token") instanceof String r && !r.isBlank()
                ? r
                : s.refreshToken();
        long exp = body.get("expires_in") instanceof Number n ? n.longValue() : 3600L;
        cloudSessions.replaceTokens(access, refresh, exp);
        return cloudSessions.current();
    }

    private static double num(Object v) {
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

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= 240 ? t : t.substring(0, 240) + "…";
    }

    private static String fmtRub(double n) {
        long r = Math.round(n);
        String sign = r > 0 ? "+" : "";
        return sign + String.format(Locale.forLanguageTag("ru-RU"), "%,d", r).replace(',', ' ')
                + " ₽";
    }

    private static String exitRu(String code) {
        if (code == null || code.isBlank()) {
            return "—";
        }
        return switch (code.toUpperCase(Locale.ROOT)) {
            case "TP2" -> "Цель 2";
            case "TP1", "TP1_FULL" -> "Цель 1";
            case "TP_30PTS" -> "Цель";
            case "SL" -> "Стоп";
            case "BE_STOP" -> "Безубыток";
            default -> code;
        };
    }

    private static String iso(Object t) {
        if (t == null) {
            return "";
        }
        if (t instanceof OffsetDateTime odt) {
            return ISO.format(odt);
        }
        return String.valueOf(t);
    }

    private record Leg(
            String id,
            String t,
            String status,
            String book,
            String pair,
            String z,
            String size,
            String side,
            Double entryPrice,
            Double exitPrice,
            String exitReason,
            String openedAt,
            String closedAt,
            Double qty,
            double pnl,
            double unrealized,
            String mode
    ) {
        static Leg fromPairs(PaperTradeEntry e) {
            String status = e.status() == null ? "WATCH" : e.status().toUpperCase(Locale.ROOT);
            String pair = (e.tickerY() == null ? "" : e.tickerY())
                    + (e.tickerX() == null || e.tickerX().isBlank() ? "" : " / " + e.tickerX());
            if (pair.isBlank()) {
                pair = "—";
            }
            String z = e.markZ() != null
                    ? String.format(Locale.US, "%.2f", e.markZ())
                    : String.format(Locale.US, "%.2f", e.entryZ());
            String size = e.remainingFraction() != null && !"CLOSED".equals(status)
                    ? Math.round(e.remainingFracOrOne() * 100) + "%"
                    : (e.pnlRub() == null ? "—" : fmtRub(e.pnlRub()));
            String t = e.closedAt() != null ? iso(e.closedAt())
                    : (e.openedAt() != null ? iso(e.openedAt()) : "");
            return new Leg(
                    e.id(),
                    t,
                    status,
                    e.book() == null ? "DAILY" : e.book(),
                    pair,
                    z,
                    size,
                    "",
                    e.entryPriceY(),
                    null,
                    e.closeComment() == null ? "" : e.closeComment(),
                    e.openedAt() == null ? "" : iso(e.openedAt()),
                    e.closedAt() == null ? "" : iso(e.closedAt()),
                    e.qtyY(),
                    e.pnlRub() == null ? 0 : e.pnlRub(),
                    e.unrealizedPnlRub() == null ? 0 : e.unrealizedPnlRub(),
                    ""
            );
        }

        static Leg fromTrend(TrendPaperJournalService.Trade t) {
            double pnl = t.pnlRub() == null ? 0 : t.pnlRub();
            String closed = t.closedAt() == null ? "" : t.closedAt();
            return new Leg(
                    t.id(),
                    closed.isBlank() ? (t.openedAt() == null ? "" : t.openedAt()) : closed,
                    "CLOSED",
                    "TREND",
                    t.instrument() == null ? "—" : t.instrument(),
                    exitRu(t.exitReason()),
                    fmtRub(pnl),
                    t.side() == null ? "" : t.side(),
                    t.entryPrice(),
                    t.exitPrice(),
                    t.exitReason() == null ? "" : t.exitReason(),
                    t.openedAt() == null ? "" : t.openedAt(),
                    closed,
                    t.qty() == null ? null : t.qty().doubleValue(),
                    pnl,
                    0,
                    t.mode() == null ? "" : t.mode()
            );
        }

        static Leg fromArbMap(Map<String, Object> t) {
            if (t == null) {
                t = Map.of();
            }
            double pnl = num(t.get("pnlRub"));
            String closed = str(t.get("closedAt"));
            String opened = str(t.get("openedAt"));
            String pair = str(t.get("pair"));
            if (pair.isBlank()) {
                pair = str(t.get("family"));
            }
            if (pair.isBlank()) {
                pair = "Арбитраж";
            }
            String exit = str(t.get("exitReason"));
            Double entry = t.get("entryPrice") instanceof Number n ? n.doubleValue() : null;
            Double exitPx = t.get("exitPrice") instanceof Number n ? n.doubleValue() : null;
            Double qty = t.get("qty") instanceof Number n ? n.doubleValue() : null;
            return new Leg(
                    str(t.get("id")),
                    closed.isBlank() ? opened : closed,
                    "CLOSED",
                    "ARB",
                    pair,
                    exitRu(exit),
                    fmtRub(pnl),
                    str(t.get("side")),
                    entry,
                    exitPx,
                    exit,
                    opened,
                    closed,
                    qty,
                    pnl,
                    0,
                    str(t.get("tag"))
            );
        }

        private static String str(Object v) {
            return v == null ? "" : String.valueOf(v);
        }

        Map<String, Object> toSlot() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id == null ? "" : id);
            m.put("pair", pair);
            m.put("ticker", pair);
            m.put("book", book);
            m.put("z", z);
            m.put("status", status);
            m.put("size", size);
            m.put("side", side);
            m.put("entryPrice", entryPrice);
            m.put("exitPrice", exitPrice);
            m.put("exitReason", exitReason);
            m.put("openedAt", openedAt);
            m.put("closedAt", closedAt);
            m.put("qty", qty);
            m.put("pnl", pnl);
            m.put("mode", mode);
            return m;
        }
    }
}
