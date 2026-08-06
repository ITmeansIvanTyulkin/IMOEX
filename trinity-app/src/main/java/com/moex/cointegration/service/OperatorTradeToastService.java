package com.moex.cointegration.service;

import com.moex.cointegration.model.OperatorTradeToast;
import com.moex.cointegration.model.PaperTradeAlert;
import com.moex.trinity.trend.LimitGridPlan;
import com.moex.trinity.trend.TrendRobotPlan;
import com.moex.trinity.trend.TrendSignal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory trend toasts + merge with pairs paper alerts for dashboard.
 */
@Service
public class OperatorTradeToastService {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final int MAX_ALERTS = 100;
    private static final int ALERT_HOURS = 24;

    private final PaperAlertService paperAlerts;
    private final double pointSize;
    private final double rubPerPoint;
    private final List<OperatorTradeToast> trendRecent = new CopyOnWriteArrayList<>();

    public OperatorTradeToastService(
            @Autowired(required = false) PaperAlertService paperAlerts,
            @Value("${imoex.strategies.trend.br.point-size:0.01}") double pointSize,
            @Value("${imoex.strategies.trend.br.rub-per-point:7.0}") double rubPerPoint
    ) {
        this.paperAlerts = paperAlerts;
        this.pointSize = pointSize > 0 ? pointSize : 0.01;
        this.rubPerPoint = rubPerPoint > 0 ? rubPerPoint : 7.0;
    }

    public List<OperatorTradeToast> recentToasts() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(ALERT_HOURS);
        List<OperatorTradeToast> out = new ArrayList<>();
        if (paperAlerts != null) {
            for (PaperTradeAlert a : paperAlerts.recentAlerts()) {
                out.add(fromPaper(a));
            }
        }
        for (OperatorTradeToast t : trendRecent) {
            if (t.at() != null && !t.at().isBefore(cutoff)) {
                out.add(t);
            }
        }
        out.sort(Comparator.comparing(OperatorTradeToast::at, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return out;
    }

    /**
     * AUTO mode: journaled entry after successful submit.
     */
    public void recordTrendEntry(TrendRobotPlan plan) {
        if (plan == null || !plan.actionable()) {
            return;
        }
        Double potential = estimateTp1Potential(plan);
        LocalDateTime at = LocalDateTime.now();
        String id = "trend:entry:" + Objects.toString(plan.playbookId(), "pb")
                + ":" + Objects.toString(plan.instrument(), "?")
                + ":" + at;
        String side = plan.buy() ? "BUY" : "SELL";
        String pot = potential == null ? "" : String.format(Locale.ROOT, " · потенциал ~%.0f ₽ к TP1", potential);
        OperatorTradeToast toast = new OperatorTradeToast(
                id,
                "TREND",
                "ENTRY",
                "Trend · вход (sandbox)",
                String.format(Locale.ROOT, "%s %s%s", plan.instrument(), side, pot),
                plan.instrument(),
                side,
                null,
                null,
                potential,
                at,
                "/view"
        );
        trendRecent.add(toast);
        trimTrend();
    }

    /**
     * SIGNAL_ONLY: actionable signal with potential (deduped per playbook/side/day/rationale).
     */
    public void recordTrendSignal(TrendRobotPlan plan) {
        if (plan == null || !plan.actionable()) {
            return;
        }
        LocalDate day = LocalDate.now(MSK);
        String side = plan.buy() ? "BUY" : "SELL";
        String dedupeKey = "trend:signal:"
                + Objects.toString(plan.playbookId(), "pb") + ":"
                + side + ":"
                + day + ":"
                + Integer.toHexString(Objects.toString(plan.rationale(), "").hashCode());
        boolean exists = trendRecent.stream().anyMatch(t -> dedupeKey.equals(t.id()));
        if (exists) {
            return;
        }
        Double potential = estimateTp1Potential(plan);
        String pot = potential == null ? "" : String.format(Locale.ROOT, " · потенциал ~%.0f ₽ к TP1", potential);
        TrendSignal signal = TrendSignal.from(plan);
        OperatorTradeToast toast = new OperatorTradeToast(
                dedupeKey,
                "TREND",
                "SIGNAL",
                "Trend · сигнал",
                (signal.summary() == null ? (plan.instrument() + " " + side) : signal.summary()) + pot,
                plan.instrument(),
                side,
                null,
                null,
                potential,
                LocalDateTime.now(),
                "/view"
        );
        trendRecent.add(toast);
        trimTrend();
    }

    public Double estimateTp1Potential(TrendRobotPlan plan) {
        if (plan == null || plan.grid() == null) {
            return null;
        }
        LimitGridPlan g = plan.grid();
        if (g.totalQty() <= 0 || pointSize <= 0) {
            return null;
        }
        double entry = g.averagePrice();
        double tp1 = plan.tp1Price();
        if (!(entry > 0) || !(tp1 > 0)) {
            return null;
        }
        double pts = Math.abs(tp1 - entry) / pointSize;
        double frac = plan.tp1Fraction() > 0 && plan.tp1Fraction() <= 1 ? plan.tp1Fraction() : 1.0;
        int qtyAtTp1 = Math.max(1, (int) Math.round(g.totalQty() * frac));
        return pts * qtyAtTp1 * rubPerPoint;
    }

    private void trimTrend() {
        while (trendRecent.size() > MAX_ALERTS) {
            trendRecent.remove(0);
        }
    }

    private static OperatorTradeToast fromPaper(PaperTradeAlert a) {
        boolean close = "CLOSE".equalsIgnoreCase(a.kind());
        String title = close ? "Paper · выход" : "Paper · вход";
        String instrument = (a.tickerY() == null ? "?" : a.tickerY())
                + "/" + (a.tickerX() == null ? "?" : a.tickerX());
        String side = a.signal() == null ? null : a.signal().name();
        return new OperatorTradeToast(
                a.id(),
                "PAIRS",
                close ? "CLOSE" : "OPEN",
                title,
                a.summary(),
                instrument,
                side,
                a.book(),
                a.pnlRub(),
                a.potentialPnlRub(),
                a.at(),
                "/view/paper"
        );
    }
}
