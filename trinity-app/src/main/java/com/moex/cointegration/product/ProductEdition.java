package com.moex.cointegration.product;

/**
 * Simulated commercial SKU (no billing). Controls which strategies are unlocked in operator UX.
 */
public enum ProductEdition {
    /** Cointegration / pairs only. */
    PAIRS,
    /** Pairs + trend robot. */
    PAIRS_TREND,
    /** All three pillars including calendar-arb. */
    FULL;

    public boolean hasPairs() {
        return true;
    }

    public boolean hasTrend() {
        return this != PAIRS;
    }

    public boolean hasArb() {
        return this == FULL;
    }

    public static ProductEdition parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return FULL;
        }
        String key = raw.trim().toUpperCase().replace('-', '_');
        return switch (key) {
            case "PAIRS", "COINTEGRATION", "LIGHT" -> PAIRS;
            case "PAIRS_TREND", "PAIRSTREND", "OPERATOR", "TREND" -> PAIRS_TREND;
            case "FULL", "FULL_CORE", "FULLCORE" -> FULL;
            default -> FULL;
        };
    }

    public String labelRu() {
        return switch (this) {
            case PAIRS -> "Коинтеграция (light)";
            case PAIRS_TREND -> "Коинтеграция + тренд";
            case FULL -> "Full Core";
        };
    }
}
