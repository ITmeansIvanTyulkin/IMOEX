package com.moex.trinity.trend;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Lightweight FA/macro proxy for BR futures (no equity-style news FA).
 * Combines HTF + session day impulse so we do not buy knives on dump days
 * or short melt-ups — operator-style filter, additive to checklist.
 */
public enum BrMacroBias {
    BULLISH,
    BEARISH,
    NEUTRAL;

    public boolean blocksBuy() {
        return this == BEARISH;
    }

    public boolean blocksSell() {
        return this == BULLISH;
    }

    /**
     * @param minDayMovePoints day open→now move that alone marks directional bias (default ~80)
     */
    public static BrMacroBias resolve(
            List<TrendBar> bars,
            LocalDateTime now,
            HtfTrend htf,
            MarketState state,
            TrendPlaybookSettings settings,
            double minDayMovePoints
    ) {
        if (bars == null || bars.isEmpty() || now == null) {
            return NEUTRAL;
        }
        double point = settings != null && settings.instrument() != null
                ? settings.instrument().pointSize() : 0.01;
        if (point <= 0) {
            point = 0.01;
        }
        double minMove = minDayMovePoints > 0 ? minDayMovePoints : 80;
        double dayMovePts = dayMovePoints(bars, now,
                settings != null ? settings.tradeSessionOpen() : "09:00", point);

        boolean dump = dayMovePts <= -minMove || state == MarketState.TREND_DOWN;
        boolean rally = dayMovePts >= minMove || state == MarketState.TREND_UP;

        if (htf == HtfTrend.DOWN || (dump && htf != HtfTrend.UP)) {
            if (dayMovePts <= -minMove * 0.5 || htf == HtfTrend.DOWN || state == MarketState.TREND_DOWN) {
                return BEARISH;
            }
        }
        if (htf == HtfTrend.UP || (rally && htf != HtfTrend.DOWN)) {
            if (dayMovePts >= minMove * 0.5 || htf == HtfTrend.UP || state == MarketState.TREND_UP) {
                return BULLISH;
            }
        }
        // Strong impulse alone (HTF flat)
        if (dayMovePts <= -minMove) {
            return BEARISH;
        }
        if (dayMovePts >= minMove) {
            return BULLISH;
        }
        return NEUTRAL;
    }

    public static double dayMovePoints(List<TrendBar> bars, LocalDateTime now, String sessionOpenRaw, double pointSize) {
        LocalTime open = TrendSessionEdge.parse(sessionOpenRaw, LocalTime.of(9, 0));
        LocalDate day = now.toLocalDate();
        TrendBar openBar = null;
        TrendBar last = null;
        for (TrendBar b : bars) {
            if (b == null || b.time() == null || !b.valid()) {
                continue;
            }
            if (!b.time().toLocalDate().equals(day)) {
                continue;
            }
            if (openBar == null && !b.time().toLocalTime().isBefore(open)) {
                openBar = b;
            }
            if (!b.time().isAfter(now)) {
                last = b;
            }
        }
        if (openBar == null || last == null) {
            return 0;
        }
        return (last.close() - openBar.open()) / pointSize;
    }
}
