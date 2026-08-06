package com.moex.trinity.trend;

/**
 * One of 2–4 TA levels from checklist §4, with §5 side/mode assignment.
 */
public record ChecklistLevel(
        double price,
        String role,
        String source,
        boolean preferBuy,
        MergedVolumeRange range,
        boolean brokenHeld
) {
    public ChecklistLevel {
        role = role == null ? "?" : role;
        source = source == null ? "" : source;
    }

    public boolean hasValidRange() {
        return range != null && range.validForEntry();
    }

    public double mid() {
        return hasValidRange() ? range.mid() : price;
    }

    public ChecklistLevel withRange(MergedVolumeRange r) {
        return new ChecklistLevel(price, role, source, preferBuy, r, brokenHeld);
    }

    public ChecklistLevel withBrokenHeld(boolean held) {
        return new ChecklistLevel(price, role, source, preferBuy, range, held);
    }
}
