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
 * Хранит недавние paper OPEN для browser-алертов и статус cron-прогонов.
 */
@Service
public class PaperAlertService {

    private static final int MAX_ALERTS = 100;
    private static final int ALERT_HOURS = 24;

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
            PaperTradeAlert alert = new PaperTradeAlert(
                    e.id(),
                    e.book() == null ? "DAILY" : e.book(),
                    e.tickerY(),
                    e.tickerX(),
                    e.signal(),
                    e.entryZ(),
                    e.openedAt() == null ? LocalDateTime.now() : e.openedAt(),
                    formatSummary(e)
            );
            recent.add(alert);
        }
        trim();
    }

    public List<PaperTradeAlert> recentAlerts() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(ALERT_HOURS);
        return recent.stream()
                .filter(a -> a.openedAt() != null && !a.openedAt().isBefore(cutoff))
                .sorted(Comparator.comparing(PaperTradeAlert::openedAt).reversed())
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

    private void trim() {
        while (recent.size() > MAX_ALERTS) {
            recent.remove(0);
        }
    }

    private static String formatSummary(PaperTradeEntry e) {
        String book = e.book() == null ? "DAILY" : e.book();
        String sig = e.signal() == null ? "?" : e.signal().name();
        return String.format(Locale.ROOT, "%s %s %s/%s Z=%.2f",
                book, sig, e.tickerY(), e.tickerX(), e.entryZ());
    }
}
