package com.moex.cointegration.web;

import java.util.UUID;

/**
 * Process-lifetime id so a JVM restart is distinct from in-app navigation.
 * Desk login must run again after the operator process comes back.
 */
public final class OperatorProcessId {

    public static final String ID = UUID.randomUUID().toString();

    private OperatorProcessId() {
    }
}
