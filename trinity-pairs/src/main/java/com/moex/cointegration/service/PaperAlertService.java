package com.moex.cointegration.service;

import com.moex.cointegration.model.AutoRunStatus;
import com.moex.cointegration.model.PaperTradeAlert;
import com.moex.cointegration.model.PaperTradeEntry;
import com.moex.cointegration.model.TradingSignal;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Хранит недавние paper OPEN/CLOSE для browser-алертов и статус cron-прогонов.
 */
@Service
public class PaperAlertService {

    private static final int MAX_ALERTS = 100;
    private static final int ALERT_HOURS = 24;
    /** Same scale as {@code PaperTradingService.Z_TO_PCT}. */
    private static final double Z_TO_PCT = 0.01;

    private final List<PaperTradeAlert> recent = new CopyOnWriteArrayList<>();

    private volatile LocalDateTime lastIntradayRunAt;
    private volatile String lastIntradayRunStatus = "—";
    private volatile LocalDateTime lastDailyRunAt;
    private volatile String lastDailyRunStatus = "—";

    public void recordNewOpens(List<PaperTradeEntry> opened) {
        if (opened == null || opened.isEmpty()) {
            return;
        }
        for (PaperTradeEntry e : opened) {
            if (!"OPEN".equals(e.status())) {
                continue;
            }
            Double potential = estimatePotentialToZero(e);
            PaperTradeAlert alert = new PaperTradeAlert(
                    e.id(),
                    "OPEN",
                    e.book() == null ? "DAILY" : e.book(),
                    e.tickerY(),
                    e.tickerX(),
                    e.signal(),
                    e.entryZ(),
                    e.openedAt() == null ? LocalDateTime.now() : e.openedAt(),
                    formatOpenSummary(e, potential),
                    null,
                    potential
            );
            recent.add(alert);
        }
        trim();
    }

    public void recordCloses(List<PaperTradeEntry> closed) {
        if (closed == null || closed.isEmpty()) {
            return;
        }
        for (PaperTradeEntry e : closed) {
            if (!"CLOSED".equals(e.status())) {
                continue;
            }
            LocalDateTime closedAt = e.closedAt() == null ? LocalDateTime.now() : e.closedAt();
            String alertId = e.id() + ":close:" + closedAt;
            Double pnl = e.pnlRub();
            PaperTradeAlert alert = new PaperTradeAlert(
                    alertId,
                    "CLOSE",
                    e.book() == null ? "DAILY" : e.book(),
                    e.tickerY(),
                    e.tickerX(),
                    e.signal(),
                    e.entryZ(),
                    closedAt,
                    formatCloseSummary(e, pnl),
                    pnl,
                    null
            );
            recent.add(alert);
        }
        trim();
    }

    public List<PaperTradeAlert> recentAlerts() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(ALERT_HOURS);
        return recent.stream()
                .filter(a -> a.at() != null && !a.at().isBefore(cutoff))
                .sorted(Comparator.comparing(PaperTradeAlert::at).reversed())
                .toList();
    }

    public void markIntradayRunStarted() {
        lastIntradayRunStatus = "RUNNING";
    }

    public void markIntradayRunFinished(boolean ok, String detail) {
        lastIntradayRunAt = LocalDateTime.now();
        lastIntradayRunStatus = ok ? "OK" : "FAILED: " + (detail == null ? "?" : detail);
    }

    public void markDailyRunStarted() {
        lastDailyRunStatus = "RUNNING";
    }

    public void markDailyRunFinished(boolean ok, String detail) {
        lastDailyRunAt = LocalDateTime.now();
        lastDailyRunStatus = ok ? "OK" : "FAILED: " + (detail == null ? "?" : detail);
    }

    public AutoRunStatus status(
            boolean intradayAutoEnabled,
            boolean dailyAutoEnabled,
            String intradayCron,
            String dailyCron
    ) {
        return new AutoRunStatus(
                intradayAutoEnabled,
                dailyAutoEnabled,
                intradayCron,
                dailyCron,
                lastIntradayRunAt,
                lastIntradayRunStatus,
                lastDailyRunAt,
                lastDailyRunStatus
        );
    }

    /**
     * Estimate cash PnL if Z reverts to 0 (same Z→% scale as paper MTM).
     */
    public static Double estimatePotentialToZero(PaperTradeEntry e) {
        if (e == null) {
            return null;
        }
        double pct = approximatePnlPct(e.entryZ(), 0.0, e.signal());
        return e.notionalY() * pct * e.remainingFracOrOne();
    }

    static double approximatePnlPct(double entryZ, double exitZ, TradingSignal signal) {
        double delta = exitZ - entryZ;
        if (signal == TradingSignal.SHORT_SPREAD) {
            delta = -delta;
        }
        return delta * Z_TO_PCT;
    }

    private void trim() {
        while (recent.size() > MAX_ALERTS) {
            recent.remove(0);
        }
    }

    private static String formatOpenSummary(PaperTradeEntry e, Double potential) {
        String book = e.book() == null ? "DAILY" : e.book();
        String sig = e.signal() == null ? "?" : e.signal().name();
        String pot = potential == null ? "" : String.format(Locale.ROOT, " · потенциал ~%.0f ₽", potential);
        return String.format(Locale.ROOT, "%s вход %s %s/%s Z=%.2f%s",
                book, sig, e.tickerY(), e.tickerX(), e.entryZ(), pot);
    }

    private static String formatCloseSummary(PaperTradeEntry e, Double pnl) {
        String book = e.book() == null ? "DAILY" : e.book();
        String sig = e.signal() == null ? "?" : e.signal().name();
        String pnlPart = pnl == null ? "" : String.format(Locale.ROOT, " · %+.0f ₽", pnl);
        return String.format(Locale.ROOT, "%s выход %s %s/%s%s",
                book, sig, e.tickerY(), e.tickerX(), pnlPart);
    }
}
