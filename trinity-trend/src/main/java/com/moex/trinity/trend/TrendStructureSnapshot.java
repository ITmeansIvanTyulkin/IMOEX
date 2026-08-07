package com.moex.trinity.trend;

import java.util.List;

/**
 * Visual/context structure for operator desk — checklist §3–8 without entry gates.
 */
public record TrendStructureSnapshot(
        int lookbackBars,
        double lookbackHigh,
        double lookbackLow,
        double historicalHigh,
        double historicalLow,
        double previousZeroPoint,
        boolean zeroPointBroken,
        boolean topBrokenHeld,
        boolean bottomBrokenHeld,
        List<Double> swingHighs,
        List<Double> swingLows,
        Zone zoneTop,
        Zone zoneBottom,
        String htf,
        /** H1 / M15 / M5_PROXY — where HTF bias came from. */
        String htfSource,
        String bias,
        String note,
        String marketState,
        List<LevelDto> checklistLevels
) {
    public TrendStructureSnapshot {
        swingHighs = swingHighs == null ? List.of() : List.copyOf(swingHighs);
        swingLows = swingLows == null ? List.of() : List.copyOf(swingLows);
        marketState = marketState == null ? "RANGE" : marketState;
        checklistLevels = checklistLevels == null ? List.of() : List.copyOf(checklistLevels);
        htfSource = htfSource == null || htfSource.isBlank() ? "M5_PROXY" : htfSource;
    }

    /** @deprecated use {@link #zoneTop()} / {@link #zoneBottom()} */
    public Zone zone() {
        if (zoneBottom != null) {
            return zoneBottom;
        }
        return zoneTop;
    }

    public record Zone(
            double low,
            double high,
            double mid,
            String source,
            double widthPoints,
            boolean validForEntry,
            String role
    ) {
        public Zone(double low, double high, double mid, String source, double widthPoints, boolean validForEntry) {
            this(low, high, mid, source, widthPoints, validForEntry, null);
        }
    }

    public record LevelDto(
            double price,
            String role,
            String source,
            boolean preferBuy,
            Double rangeLow,
            Double rangeHigh,
            boolean brokenHeld
    ) {
    }

    public static TrendStructureSnapshot empty(String note) {
        return new TrendStructureSnapshot(
                0,
                Double.NaN, Double.NaN,
                Double.NaN, Double.NaN,
                Double.NaN, false,
                false, false,
                List.of(), List.of(),
                null, null,
                "FLAT", "M5_PROXY", "NONE", note,
                "RANGE", List.of()
        );
    }
}
