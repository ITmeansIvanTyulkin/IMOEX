package com.moex.cointegration.upsell;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight JSON persistence for upsell events and dismissals.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpsellStore {

    private List<UpsellEvent> events = new ArrayList<>();
    /** featureKey → last dismiss instant */
    private Map<String, Instant> dismissals = new HashMap<>();

    public List<UpsellEvent> getEvents() {
        return events;
    }

    public void setEvents(List<UpsellEvent> events) {
        this.events = events != null ? events : new ArrayList<>();
    }

    public Map<String, Instant> getDismissals() {
        return dismissals;
    }

    public void setDismissals(Map<String, Instant> dismissals) {
        this.dismissals = dismissals != null ? dismissals : new HashMap<>();
    }
}
