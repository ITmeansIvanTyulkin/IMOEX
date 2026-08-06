package com.moex.trinity.trend;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    /** Open-position manage snapshot (§12). */
    private final AtomicReference<TrendPositionManager.ManageAdvice> lastManage = new AtomicReference<>();
    private final AtomicReference<OpenManageState> openManage = new AtomicReference<>();
    /** Consecutive non-actionable bars with same stuck signature — auto-kick trigger. */
    private final AtomicInteger stuckBars = new AtomicInteger(0);
    private final AtomicReference<String> stuckSignature = new AtomicReference<>("");
    private final AtomicInteger kickCountToday = new AtomicInteger(0);

    private static final int AUTO_KICK_AFTER_BARS = 8; // ~40 min M5

    private record OpenManageState(boolean buy, double entryAvg, double stop, double tp1) {
    }

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

    public Optional<TrendPositionManager.ManageAdvice> lastManageAdvice() {
        return Optional.ofNullable(lastManage.get());
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
        TrendRobotPlan p = lastPlan.get();
        if (p != null && p.grid() != null) {
            openManage.set(new OpenManageState(
                    p.buy(),
                    p.grid().averagePrice(),
                    p.stopLossPrice(),
                    p.tp1Price()
            ));
        }
    }

    /**
     * Checklist §12: update stop to BE after TP1, then trail.
     * Call each bar / tick while IN_POSITION.
     */
    public TrendPositionManager.ManageAdvice manageOpen(double lastPrice) {
        OpenManageState om = openManage.get();
        if (om == null || !Double.isFinite(lastPrice)) {
            TrendPositionManager.ManageAdvice empty =
                    new TrendPositionManager.ManageAdvice(Double.NaN, false, false, false, 0, "no open position");
            lastManage.set(empty);
            return empty;
        }
        double trailPts = settings.instrument().stopPoints();
        TrendPositionManager.ManageAdvice advice = TrendPositionManager.update(
                om.buy(), om.entryAvg(), om.stop(), om.tp1(),
                lastPrice, settings.instrument().pointSize(), trailPts,
                3, settings.tp1Fraction(), false
        );
        if (Double.isFinite(advice.stop()) && advice.stop() != om.stop()) {
            openManage.set(new OpenManageState(om.buy(), om.entryAvg(), advice.stop(), om.tp1()));
            state.set(TrendRobotState.MANAGE);
        }
        lastManage.set(advice);
        return advice;
    }

    /** Seed manage state (replay / external fill). */
    public void beginManage(boolean buy, double entryAvg, double stop, double tp1) {
        openManage.set(new OpenManageState(buy, entryAvg, stop, tp1));
        state.set(TrendRobotState.IN_POSITION);
    }

    /** Call when a setup is stopped out — starts cooldown and spends the zone. */
    public void registerStopLoss(LocalDateTime at) {
        int bars = Math.max(0, settings.cooldownBarsAfterSl());
        if (at != null && bars > 0) {
            cooldownUntil.set(at.plusMinutes(bars * 5L));
        }
        spendActiveLock();
        openManage.set(null);
        lastManage.set(null);
        state.set(TrendRobotState.FLAT);
    }

    public void registerFlatWin(LocalDateTime ignored) {
        spendActiveLock();
        openManage.set(null);
        lastManage.set(null);
        state.set(TrendRobotState.FLAT);
    }

    /** Unlock / cancel without fill — does not spend zone or burn quota. */
    public void clearSetupLock() {
        lock.set(null);
    }

    /**
     * Kick stuck robot to max activation: clear arm lock, spent zones, cooldown,
     * stuck counters, and hard-reset day shelves / checklist levels on LevelsProfile playbook.
     * Re-arms SCAN so the next evaluate rediscovers structure aggressively.
     * Does not touch paper journal / fills quota.
     */
    public Map<String, Object> kickAwake(String reason) {
        String why = reason == null || reason.isBlank() ? "manual" : reason;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reason", why);
        out.put("clearedSetupLock", lock.get() != null);
        out.put("clearedSpentZones", spentZones.size());
        out.put("clearedCooldown", cooldownUntil.get() != null);
        lock.set(null);
        spentZones.clear();
        cooldownUntil.set(null);
        openManage.set(null);
        lastManage.set(null);
        stuckBars.set(0);
        stuckSignature.set("");
        state.set(TrendRobotState.SCAN);
        kickCountToday.incrementAndGet();
        out.put("kickCountToday", kickCountToday.get());
        if (playbook instanceof LevelsProfileBrPlaybook levelsPb) {
            LevelsProfileBrPlaybook.KickResult kr = levelsPb.kickHard(why);
            out.put("dayLockCleared", kr.cleared());
            out.put("hadTop", kr.hadTop());
            out.put("hadBottom", kr.hadBottom());
            out.put("levelsCleared", kr.levelsCleared());
        } else {
            out.put("dayLockCleared", false);
        }
        out.put("engineState", state.get().name());
        return out;
    }

    /** Soft kick: locks/cooldown only — keep day shelves. */
    public Map<String, Object> kickSoft(String reason) {
        String why = reason == null || reason.isBlank() ? "soft" : reason;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reason", why);
        out.put("mode", "soft");
        out.put("clearedSetupLock", lock.get() != null);
        out.put("clearedSpentZones", spentZones.size());
        lock.set(null);
        spentZones.clear();
        cooldownUntil.set(null);
        stuckBars.set(0);
        stuckSignature.set("");
        state.set(TrendRobotState.SCAN);
        kickCountToday.incrementAndGet();
        out.put("kickCountToday", kickCountToday.get());
        out.put("engineState", state.get().name());
        return out;
    }

    public int kickCountToday() {
        return kickCountToday.get();
    }

    public Optional<TrendRobotPlan> evaluate(TrendBarSeries series, TrendAccountContext account) {
        state.set(TrendRobotState.SCAN);
        Optional<TrendRobotPlan> raw = playbook.evaluate(series, account);
        Optional<TrendRobotPlan> filtered = applyFilters(raw, series);
        filtered = maybeAutoKick(filtered, series, account);
        filtered.ifPresent(p -> {
            lastPlan.set(p);
            state.set(p.state() == null ? TrendRobotState.NO_TRADE : p.state());
        });
        return filtered;
    }

    /**
     * If robot sits in ZONE_READY / waiting-retest with price far from zone for too long — soft then hard kick.
     */
    private Optional<TrendRobotPlan> maybeAutoKick(
            Optional<TrendRobotPlan> plan,
            TrendBarSeries series,
            TrendAccountContext account
    ) {
        if (plan.isEmpty() || series == null || series.isEmpty()) {
            stuckBars.set(0);
            return plan;
        }
        TrendRobotPlan p = plan.get();
        boolean stuckish = !p.actionable()
                && (p.state() == TrendRobotState.ZONE_READY || p.state() == TrendRobotState.NO_TRADE);
        String rationale = p.rationale() == null ? "" : p.rationale();
        boolean waitingRetest = rationale.contains("waiting retest")
                || rationale.contains("break+hold — waiting")
                || rationale.contains("no valid profiled range");
        double distPts = Double.POSITIVE_INFINITY;
        if (p.range() != null && p.range().validForEntry()) {
            double point = settings.instrument().pointSize();
            distPts = Math.abs(series.last().close() - p.range().mid()) / (point > 0 ? point : 0.01);
        }
        double unlock = settings.unlockDistancePoints() > 0 ? settings.unlockDistancePoints() : 40;
        boolean far = distPts >= unlock;
        String sig = (p.state() == null ? "?" : p.state().name()) + "|"
                + (waitingRetest ? "retestWait" : "other") + "|"
                + (far ? "far" : "near");

        boolean profileStuck = rationale.contains("§6–7") || rationale.contains("no 2–4 TA levels");
        if (stuckish && (waitingRetest || far || profileStuck)) {
            String prev = stuckSignature.get();
            if (sig.equals(prev)) {
                int n = stuckBars.incrementAndGet();
                // §6–7 / empty levels: soft kick cannot wipe day shelves — hard kick early
                if (profileStuck && n >= AUTO_KICK_AFTER_BARS / 2) {
                    kickAwake("auto-profile-stuck-" + n + "bars");
                    stuckBars.set(0);
                    Optional<TrendRobotPlan> again = playbook.evaluate(series, account);
                    return applyFilters(again, series);
                }
                if (n == AUTO_KICK_AFTER_BARS) {
                    kickSoft("auto-stuck-" + n + "bars");
                    Optional<TrendRobotPlan> again = playbook.evaluate(series, account);
                    return applyFilters(again, series);
                }
                if (n >= AUTO_KICK_AFTER_BARS * 2) {
                    kickAwake("auto-stuck-hard-" + n + "bars");
                    stuckBars.set(0);
                    Optional<TrendRobotPlan> again = playbook.evaluate(series, account);
                    return applyFilters(again, series);
                }
            } else {
                stuckSignature.set(sig);
                stuckBars.set(1);
            }
        } else if (p.actionable()) {
            stuckBars.set(0);
            stuckSignature.set("");
        }
        return plan;
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

        // Checklist: same TOP/BOT may bounce several times/day after price leaves and returns.
        // Drop spent marks once price unlocked from that zone mid (same distance as cancel).
        spentZones.removeIf(s -> {
            if (!s.day().equals(day)) {
                return true;
            }
            double distPts = Math.abs(series.last().close() - s.mid()) / point;
            return distPts >= unlockPts;
        });

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
                return Optional.of(noTradePlan(series, raw,
                        "same zone just filled — wait unlock ≥" + (int) unlockPts + " pts from mid"));
            }
        }

        // 0 or negative = unlimited fills/day (checklist: take every valid bounce)
        int max = settings.maxSetupsPerDay();
        if (max > 0 && fillsToday.get() >= max) {
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
        int max = settings.maxSetupsPerDay();
        if (max > 0 && fillsToday.get() >= max) {
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
            kickCountToday.set(0);
            stuckBars.set(0);
            stuckSignature.set("");
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
        openManage.set(null);
        lastManage.set(null);
    }

    public void abort(String ignored) {
        state.set(TrendRobotState.ABORT);
        clearSetupLock();
    }
}
