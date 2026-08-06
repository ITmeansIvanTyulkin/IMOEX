package com.moex.trinity.trend;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Extension: lock the checklist §4 set of 2–4 level prices (+ preferBuy/role) for the MSK day.
 * Ranges are re-profiled from bars/tape; prices/roles stay fixed until new day wipe.
 */
public final class ChecklistDayLock {

    public record Locked(
            LocalDate day,
            List<ChecklistLevel> levels
    ) {
        public Locked {
            levels = levels == null ? List.of() : List.copyOf(levels);
        }
    }

    private final AtomicReference<Locked> ref = new AtomicReference<>();

    public Locked get() {
        return ref.get();
    }

    public void clear() {
        ref.set(null);
    }

    /** Drop structural HI/LO after day-shelf break so they re-seed from live. */
    public void dropRoles(String... roles) {
        Locked cur = ref.get();
        if (cur == null || cur.levels().isEmpty() || roles == null || roles.length == 0) {
            return;
        }
        java.util.Set<String> drop = java.util.Set.of(roles);
        List<ChecklistLevel> kept = new ArrayList<>();
        for (ChecklistLevel l : cur.levels()) {
            if (!drop.contains(l.role())) {
                kept.add(l);
            }
        }
        if (kept.size() == cur.levels().size()) {
            return;
        }
        ref.set(new Locked(cur.day(), kept));
    }

    /**
     * On new day → replace. Same day with empty lock → store first non-empty set.
     * Same day with lock → keep locked prices/roles; refresh ranges from {@code live}.
     */
    public List<ChecklistLevel> absorb(LocalDate day, List<ChecklistLevel> live) {
        if (day == null) {
            return live == null ? List.of() : live;
        }
        Locked cur = ref.get();
        if (cur == null || cur.day() == null || !cur.day().equals(day)) {
            if (live != null && !live.isEmpty()) {
                // Strip ranges for lock identity — keep price/role/side
                List<ChecklistLevel> bare = new ArrayList<>();
                for (ChecklistLevel l : live) {
                    bare.add(new ChecklistLevel(l.price(), l.role(), l.source(), l.preferBuy(), null, false));
                }
                ref.set(new Locked(day, bare));
            } else {
                ref.set(null);
            }
            return live == null ? List.of() : live;
        }
        // Merge: locked prices, live ranges / brokenHeld by nearest match
        if (cur.levels().isEmpty()) {
            if (live != null && !live.isEmpty()) {
                List<ChecklistLevel> bare = new ArrayList<>();
                for (ChecklistLevel l : live) {
                    bare.add(new ChecklistLevel(l.price(), l.role(), l.source(), l.preferBuy(), null, false));
                }
                ref.set(new Locked(day, bare));
                return live;
            }
            return List.of();
        }
        List<ChecklistLevel> out = new ArrayList<>();
        for (ChecklistLevel locked : cur.levels()) {
            ChecklistLevel match = nearestSameRole(live, locked);
            if (match != null && match.hasValidRange()) {
                out.add(new ChecklistLevel(
                        locked.price(), locked.role(),
                        locked.source().contains("PRIOR") ? locked.source() : match.source(),
                        locked.preferBuy(), match.range(), match.brokenHeld()));
            }
            // Drop stale ACCUM/ZERO / unmatched structural without live profile.
            // Fresh TREND_HI/LO are re-injected from live below.
        }
        // Pull in new structural HI/LO from live if missing after shelf break / re-seed
        if (live != null) {
            for (ChecklistLevel l : live) {
                if (!("TREND_HI".equals(l.role()) || "TREND_LO".equals(l.role()))) {
                    continue;
                }
                boolean have = false;
                for (ChecklistLevel o : out) {
                    if (o.role().equals(l.role())) {
                        have = true;
                        break;
                    }
                }
                if (!have && l.hasValidRange()) {
                    out.add(l);
                }
            }
            // Also pull ACCUM/ZERO if we dropped too many and fell below 2 levels
            if (out.size() < 2) {
                for (ChecklistLevel l : live) {
                    if (!l.hasValidRange()) {
                        continue;
                    }
                    boolean have = out.stream().anyMatch(o ->
                            Math.abs(o.price() - l.price()) < 1e-9 || o.role().equals(l.role()));
                    if (!have) {
                        out.add(l);
                    }
                    if (out.size() >= 4) {
                        break;
                    }
                }
            }
        }
        // Refresh locked bare set to include new structural roles
        List<ChecklistLevel> bare = new ArrayList<>();
        for (ChecklistLevel l : out) {
            bare.add(new ChecklistLevel(l.price(), l.role(), l.source(), l.preferBuy(), null, false));
        }
        ref.set(new Locked(day, bare));
        return out;
    }

    private static ChecklistLevel nearestSameRole(List<ChecklistLevel> live, ChecklistLevel locked) {
        if (live == null || live.isEmpty() || locked == null) {
            return null;
        }
        ChecklistLevel bestRole = null;
        double bestRoleD = Double.POSITIVE_INFINITY;
        ChecklistLevel bestAny = null;
        double bestAnyD = Double.POSITIVE_INFINITY;
        for (ChecklistLevel l : live) {
            if (!l.hasValidRange()) {
                continue;
            }
            double d = Math.abs(l.price() - locked.price());
            if (d < bestAnyD) {
                bestAnyD = d;
                bestAny = l;
            }
            if (locked.role().equals(l.role()) && d < bestRoleD) {
                bestRoleD = d;
                bestRole = l;
            }
        }
        // Same role within ~40 pts; structural HI/LO never inherit ACCUM/ZERO ranges
        double maxRoleDrift = 0.40; // 40 pts on BR
        if ("TREND_HI".equals(locked.role()) || "TREND_LO".equals(locked.role())) {
            if (bestRole != null && bestRoleD <= maxRoleDrift) {
                return bestRole;
            }
            return null; // drop → re-inject fresh live HI/LO
        }
        if (bestRole != null && bestRoleD <= maxRoleDrift) {
            return bestRole;
        }
        return bestAny;
    }

    private static ChecklistLevel nearest(List<ChecklistLevel> live, double price) {
        if (live == null || live.isEmpty()) {
            return null;
        }
        ChecklistLevel best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (ChecklistLevel l : live) {
            double d = Math.abs(l.price() - price);
            if (d < bestD) {
                bestD = d;
                best = l;
            }
        }
        return best;
    }
}
