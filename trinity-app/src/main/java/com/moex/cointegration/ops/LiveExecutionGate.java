package com.moex.cointegration.ops;

import com.moex.cointegration.smoke.StartupSmokeStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pre-live checklist before {@code liveExecution=true}. Soft research default stays off;
 * enabling live requires smoke OK and explicit operator awareness of remaining gates.
 */
@Component
public class LiveExecutionGate {

    private final StartupSmokeStatus smokeStatus;

    public LiveExecutionGate(StartupSmokeStatus smokeStatus) {
        this.smokeStatus = smokeStatus;
    }

    public Map<String, Object> checklist() {
        List<Map<String, Object>> items = new ArrayList<>();
        StartupSmokeStatus.Snapshot smoke = smokeStatus.get();
        boolean smokeOk = smoke != null && smoke.ok()
                && smoke.phase() != StartupSmokeStatus.Phase.FAIL
                && smoke.phase() != StartupSmokeStatus.Phase.PENDING
                && smoke.phase() != StartupSmokeStatus.Phase.RUNNING;
        items.add(item("startup_smoke", smokeOk,
                smokeOk ? "startup smoke OK"
                        : ("smoke " + (smoke == null ? "missing" : smoke.phase() + ": " + smoke.message()))));

        items.add(item("live_default_off", true,
                "liveExecution defaults false until operator enables after gate"));

        items.add(item("checklist_fidelity", true,
                "EXT filters must not veto §8 / Exclusive side law — see roadmap fragility pack"));

        items.add(item("day_lock_observed", true,
                "After restart: TOP·день frozen vs HI; HIST·серия labeled separately"));

        items.add(item("qty_filled_vs_planned", true,
                "Statement shows filled/planned when grid partially fills"));

        items.add(item("max_day_loss_go_risk", true,
                "Confirm maxDayLossRub + GO/risk % in trend settings before live size"));

        boolean ready = items.stream().allMatch(i -> Boolean.TRUE.equals(i.get("ok")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("readyForLive", ready);
        out.put("blockers", items.stream().filter(i -> !Boolean.TRUE.equals(i.get("ok"))).toList());
        out.put("checks", items);
        return out;
    }

    /**
     * @return null if allowing live=true; else human-readable block reason
     */
    public String blockEnableLiveReason() {
        Map<String, Object> c = checklist();
        if (Boolean.TRUE.equals(c.get("readyForLive"))) {
            return null;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blockers = (List<Map<String, Object>>) c.get("blockers");
        if (blockers == null || blockers.isEmpty()) {
            return "live gate not ready";
        }
        Object detail = blockers.get(0).get("detail");
        return detail == null ? "live gate blocked" : String.valueOf(detail);
    }

    private static Map<String, Object> item(String id, boolean ok, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("ok", ok);
        m.put("detail", detail);
        return m;
    }
}
