package com.moex.cointegration.service;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.config.SessionProperties;
import com.moex.cointegration.model.BookKind;

/**
 * Параметры сканирования юниверса пар (DAILY / INTRADAY).
 */
public final class PairScanParams {

    private final BookKind book;
    private final int rollingZWindow;
    private final int maxHoldBars;
    private final double barsPerYear;
    private final int adaptiveLongWin;
    private int lastPairsTested;
    private int lastPairsSkipped;

    private PairScanParams(
            BookKind book,
            int rollingZWindow,
            int maxHoldBars,
            double barsPerYear,
            int adaptiveLongWin
    ) {
        this.book = book;
        this.rollingZWindow = rollingZWindow;
        this.maxHoldBars = maxHoldBars;
        this.barsPerYear = barsPerYear;
        this.adaptiveLongWin = adaptiveLongWin;
    }

    public static PairScanParams daily(ImoexProperties properties) {
        return new PairScanParams(
                BookKind.DAILY,
                properties.cointegration().rollingZWindow(),
                properties.risk().maxHoldBars(),
                252.0,
                252
        );
    }

    public static PairScanParams intraday(ImoexProperties properties, SessionProperties session) {
        int hours = session.hoursPerSession();
        return new PairScanParams(
                BookKind.INTRADAY,
                session.intradayRollingZWindow(),
                session.intradayMaxHoldBars(),
                session.barsPerYearIntraday(),
                Math.max(60, hours * 20)
        );
    }

    public BookKind book() {
        return book;
    }

    public int rollingZWindow() {
        return rollingZWindow;
    }

    public int maxHoldBars() {
        return maxHoldBars;
    }

    public double barsPerYear() {
        return barsPerYear;
    }

    public int adaptiveLongWin() {
        return adaptiveLongWin;
    }

    public int lastPairsTested() {
        return lastPairsTested;
    }

    public int lastPairsSkipped() {
        return lastPairsSkipped;
    }

    void setLastPairsTested(int lastPairsTested) {
        this.lastPairsTested = lastPairsTested;
    }

    void setLastPairsSkipped(int lastPairsSkipped) {
        this.lastPairsSkipped = lastPairsSkipped;
    }
}
