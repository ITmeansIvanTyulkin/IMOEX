package com.moex.cointegration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Режим сессии: DAILY (овернайт ok) или INTRADAY (flatten к pre-close).
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
        Integer candleInterval
) {
    public SessionProperties {
        if (mode == null || mode.isBlank()) {
            mode = "DAILY";
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
    }

    public static SessionProperties defaults() {
        return new SessionProperties("DAILY", "18:30", "18:45", "10:00", "18:45", true,
                "paper-journal-intraday.json", 60);
    }

    public boolean intradayMode() {
        return "INTRADAY".equalsIgnoreCase(mode);
    }
}
