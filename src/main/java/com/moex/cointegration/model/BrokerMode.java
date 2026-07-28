package com.moex.cointegration.model;

/**
 * Режим брокерского контура.
 */
public enum BrokerMode {
    PAPER,
    MANUAL_CONFIRM,
    AUTO;

    public static BrokerMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return AUTO;
        }
        return BrokerMode.valueOf(raw.trim().toUpperCase());
    }
}
