package com.moex.trinity.trend;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Robot engine: one-zone lock + max fills/day + cooldown after SL.
 * Quota counts fills only — arm/cancel does not burn the day budget.
 */
public class TrendRobotEngine {

    private final TrendPlaybook playbook;
    private final TrendPlaybookSettings settings;
    private final TrendEventCalendar eventCalendar;
    private final AtomicReference<TrendRobotState> state = new AtomicReference<>(TrendRobotState.SCAN);
    private final AtomicReference<TrendRobotPlan> lastPlan = new AtomicReference<>();
    private final AtomicReference<SetupLock> lock = new AtomicReference<>();
    private final AtomicInteger fillsToday = new AtomicInteger(0);
    private final AtomicReference<LocalDate> setupsDay = new AtomicReference<>();
    private final AtomicReference<LocalDateTime> cooldownUntil = new AtomicReference<>();
    /** Zones that produced a fill today — no re-arm after SL/TP. */
    private final CopyOnWriteArrayList<SetupLock> spentZones = new CopyOnWriteArrayList<>();

    public TrendRobotEngine(TrendPlaybook playbook) {
        this(playbook, TrendPlaybookSettings.brDefaults(), null);
    }

    public TrendRobotEngine(TrendPlaybook playbook, TrendPlaybookSettings settings) {
        this(playbook, settings, null);
    }

    public TrendRobotEngine(TrendPlaybook playbook, TrendPlaybookSettings settings, TrendEventCalendar eventCalendar) {
        this.playbook = playbook;
        this.settings = settings == null ? TrendPlaybookSettings.brDefaults() : settings;
        this.eventCalendar = eventCalendar != null
                ? eventCalendar
                : TrendEventCalendar.fromSettings(this.settings);
    }

    public TrendPlaybook playbook() {
        return playbook;
    }

    public TrendRobotState state() {
        return state.get();
    }

    public Optional<TrendRobotPlan> lastPlan() {
        return Optional.ofNullable(lastPlan.get());
    }

    public Optional<SetupLock> activeLock() {
        return Optional.ofNullable(lock.get());
    }

    /** Filled setups today (quota counter). */
    public int setupsTodayCount() {
        return fillsToday.get();
    }

    /** Limit fill — counts toward max-setups-per-day. */
    public void registerFill(LocalDateTime ignored) {
        LocalDate day = setupsDay.get();
        if (day == null && ignored != null) {
            setupsDay.set(ignored.toLocalDate());
        }
        fillsToday.incrementAndGet();
        state.set(TrendRobotState.IN_POSITION);
    }

    /** Call when a setup is stopped out — starts cooldown and spends the zone. */
    public void registerStopLoss(LocalDateTime at) {
        int bars = Math.max(0, settings.cooldownBarsAfterSl());
        if (at != null && bars > 0) {
            cooldownUntil.set(at.plusMinutes(bars * 5L));
        }
        spendActiveLock();
        state.set(TrendRobotState.FLAT);
    }

    public void registerFlatWin(LocalDateTime ignored) {
        spendActiveLock();
        state.set(TrendRobotState.FLAT);
    }

    /** Unlock / cancel without fill — does not spend zone or burn quota. */
    public void clearSetupLock() {
        lock.set(null);
    }

    public Optional<TrendRobotPlan> evaluate(TrendBarSeries series, TrendAccountContext account) {
        state.set(TrendRobotState.SCAN);
        Optional<TrendRobotPlan> raw = playbook.evaluate(series, account);
        Optional<TrendRobotPlan> filtered = applyFilters(raw, series);
        filtered.ifPresent(p -> {
            lastPlan.set(p);
            state.set(p.state() == null ? TrendRobotState.NO_TRADE : p.state());
        });
        return filtered;
    }

    Optional<TrendRobotPlan> applyFilters(Optional<TrendRobotPlan> raw, TrendBarSeries series) {
        if (series == null || series.isEmpty()) {
            return raw;
        }
        LocalDate day = series.last().time().toLocalDate();
        LocalDateTime now = series.last().time();
        rollDay(day);

        String edge = TrendSessionEdge.blockReason(now, settings);
        if (edge != null) {
            // Drop unfilled arm in blackout — do not spend zone / burn quota
            clearSetupLock();
            return Optional.of(noTradePlan(series, raw, edge));
        }

        String eventEdge = eventCalendar.blockReason(now, series.instrument());
        if (eventEdge != null) {
            clearSetupLock();
            return Optional.of(noTradePlan(series, raw, eventEdge));
        }

        LocalDateTime cd = cooldownUntil.get();
        if (cd != null && !now.isBefore(cd)) {
            cooldownUntil.set(null);
            cd = null;
        }
        if (cd != null && now.isBefore(cd)) {
            return Optional.of(noTradePlan(series, raw,
                    "cooldown after SL until " + cd + " (" + settings.cooldownBarsAfterSl() + " M5 bars)"));
        }

        if (!settings.oneSetupPerZone()) {
            return gateMaxFills(raw, series);
        }

        double point = settings.instrument().pointSize();
        double unlockPts = settings.unlockDistancePoints() > 0 ? settings.unlockDistancePoints() : 40;

        SetupLock current = lock.get();
        if (current != null) {
            if (!current.day().equals(day)) {
                lock.set(null);
                current = null;
            } else {
                double distPts = Math.abs(series.last().close() - current.mid()) / point;
                if (distPts >= unlockPts) {
                    // Cancel without fill — free the zone for a later touch
                    lock.set(null);
                    return Optional.of(noTradePlan(series, Optional.of(current.plan()),
                            String.format("unlocked zone (%.0f pts from mid) — wait next setup", distPts)));
                }
            }
        }

        if (current != null) {
            return Optional.of(withHoldRationale(current.plan(), series.last().close(), unlockPts));
        }

        if (raw.isEmpty()) {
            return raw;
        }
        TrendRobotPlan plan = raw.get();
        if (!plan.actionable() || plan.range() == null || !plan.range().validForEntry()) {
            return raw;
        }

        double pointSize = settings.instrument().pointSize();
        for (SetupLock spent : spentZones) {
            if (spent.day().equals(day) && spent.sameZone(plan.range(), pointSize)) {
                return Optional.of(noTradePlan(series, raw, "zone already spent today (filled earlier)"));
            }
        }

        int max = Math.max(1, settings.maxSetupsPerDay());
        if (fillsToday.get() >= max) {
            return Optional.of(noTradePlan(series, raw,
                    "max fills/day reached (" + max + ")"));
        }

        SetupLock next = new SetupLock(
                day,
                plan.range().low(),
                plan.range().high(),
                plan.buy(),
                plan.mode(),
                plan
        );
        lock.set(next);
        // Quota increments only on registerFill — not on arm
        return Optional.of(plan);
    }

    private Optional<TrendRobotPlan> gateMaxFills(Optional<TrendRobotPlan> raw, TrendBarSeries series) {
        if (raw.isEmpty() || !raw.get().actionable()) {
            return raw;
        }
        int max = Math.max(1, settings.maxSetupsPerDay());
        if (fillsToday.get() >= max) {
            return Optional.of(noTradePlan(series, raw, "max fills/day reached (" + max + ")"));
        }
        return raw;
    }

    private void spendActiveLock() {
        SetupLock cur = lock.getAndSet(null);
        if (cur != null) {
            spentZones.add(cur);
        }
    }

    private void rollDay(LocalDate day) {
        LocalDate prev = setupsDay.get();
        if (prev == null || !prev.equals(day)) {
            setupsDay.set(day);
            fillsToday.set(0);
            spentZones.clear();
            if (prev != null) {
                lock.set(null);
            }
        }
    }

    private TrendRobotPlan noTradePlan(TrendBarSeries series, Optional<TrendRobotPlan> raw, String reason) {
        MergedVolumeRange range = raw.map(TrendRobotPlan::range).orElse(null);
        return new TrendRobotPlan(
                playbook.id(),
                series.instrument(),
                series.timeframe(),
                LocalDateTime.now(),
                TrendRobotState.NO_TRADE,
                null,
                true,
                range,
                null,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                settings.tp1Fraction(),
                reason,
                List.of(reason)
        );
    }

    private TrendRobotPlan withHoldRationale(TrendRobotPlan plan, double lastClose, double unlockPts) {
        String note = String.format(
                "ONE_SETUP lock active [%.2f–%.2f] %s %s — unlock ≥%.0f pts from mid or new day; last=%.2f",
                plan.range().low(), plan.range().high(),
                plan.buy() ? "BUY" : "SELL",
                plan.mode() == null ? "?" : plan.mode().name(),
                unlockPts,
                lastClose
        );
        List<String> notes = new java.util.ArrayList<>(plan.notes());
        notes.add(note);
        return new TrendRobotPlan(
                plan.playbookId(),
                plan.instrument(),
                plan.timeframe(),
                plan.createdAt(),
                TrendRobotState.WORKING_ORDERS,
                plan.mode(),
                plan.buy(),
                plan.range(),
                plan.grid(),
                plan.stopLossPrice(),
                plan.tp1Price(),
                plan.tp2Price(),
                plan.tp1Fraction(),
                note,
                notes
        );
    }

    public void markWorking() {
        state.set(TrendRobotState.WORKING_ORDERS);
    }

    public void markInPosition() {
        state.set(TrendRobotState.IN_POSITION);
    }

    public void markManage() {
        state.set(TrendRobotState.MANAGE);
    }

    public void markFlat() {
        state.set(TrendRobotState.FLAT);
        lastPlan.set(null);
    }

    public void abort(String ignored) {
        state.set(TrendRobotState.ABORT);
        clearSetupLock();
    }
}
