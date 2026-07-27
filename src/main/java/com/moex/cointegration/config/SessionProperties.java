package com.moex.cointegration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Параметры сессии и INTRADAY overlays (оба горизонта работают параллельно).
 */
@ConfigurationProperties(prefix = "imoex.session")
public record SessionProperties(
        String mode,
        String preCloseStart,
        String preCloseEnd,
        String sessionOpen,
        String sessionClose,
        Boolean preventWeekendHold,
        String intradayJournalFile,
        Integer candleInterval,
        Integer hoursPerSession,
        Integer hourlyLookbackDays,
        Integer intradayRollingZWindow,
        Integer intradayMaxHoldBars,
        Double intradayMinHalfLifeDays,
        Double intradayTradeMaxHalfLifeDays
) {
    public SessionProperties {
        if (mode == null || mode.isBlank()) {
            mode = "DUAL";
        }
        if (preCloseStart == null || preCloseStart.isBlank()) {
            preCloseStart = "18:30";
        }
        if (preCloseEnd == null || preCloseEnd.isBlank()) {
            preCloseEnd = "18:45";
        }
        if (sessionOpen == null || sessionOpen.isBlank()) {
            sessionOpen = "10:00";
        }
        if (sessionClose == null || sessionClose.isBlank()) {
            sessionClose = "18:45";
        }
        if (preventWeekendHold == null) {
            preventWeekendHold = true;
        }
        if (intradayJournalFile == null || intradayJournalFile.isBlank()) {
            intradayJournalFile = "paper-journal-intraday.json";
        }
        if (candleInterval == null || candleInterval <= 0) {
            candleInterval = 60;
        }
        if (hoursPerSession == null || hoursPerSession <= 0) {
            hoursPerSession = 7;
        }
        if (hourlyLookbackDays == null || hourlyLookbackDays <= 0) {
            hourlyLookbackDays = 90;
        }
        if (intradayRollingZWindow == null || intradayRollingZWindow < 2) {
            intradayRollingZWindow = 48;
        }
        if (intradayMaxHoldBars == null || intradayMaxHoldBars <= 0) {
            intradayMaxHoldBars = 7;
        }
        if (intradayMinHalfLifeDays == null || intradayMinHalfLifeDays <= 0) {
            intradayMinHalfLifeDays = 0.25;
        }
        if (intradayTradeMaxHalfLifeDays == null || intradayTradeMaxHalfLifeDays <= 0) {
            intradayTradeMaxHalfLifeDays = 3.0;
        }
    }

    public static SessionProperties defaults() {
        return new SessionProperties(
                "DUAL", "18:30", "18:45", "10:00", "18:45", true,
                "paper-journal-intraday.json", 60,
                7, 90, 48, 7, 0.25, 3.0
        );
    }

    /** Legacy: true только если явно INTRADAY-only (не используется dual-book). */
    public boolean intradayMode() {
        return "INTRADAY".equalsIgnoreCase(mode);
    }

    public double barsPerYearIntraday() {
        return 252.0 * hoursPerSession;
    }
}
