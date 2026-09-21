package com.moex.cointegration.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeskSessionStoreTest {

    @Test
    void issuedCookieIsValidUntilRevoked() {
        DeskSessionStore store = new DeskSessionStore();
        var cookie = store.issueCookie("a@b.c", Duration.ofMinutes(5));
        assertTrue(store.valid(cookie.getValue()));
        store.revoke(cookie.getValue());
        assertFalse(store.valid(cookie.getValue()));
    }

    @Test
    void blankAndUnknownAreInvalid() {
        DeskSessionStore store = new DeskSessionStore();
        assertFalse(store.valid(null));
        assertFalse(store.valid(""));
        assertFalse(store.valid("missing"));
    }

    @Test
    void defaultCookieLivesEightHours() {
        DeskSessionStore store = new DeskSessionStore();
        var cookie = store.issueCookie("a@b.c", null);
        assertTrue(store.valid(cookie.getValue()));
        org.junit.jupiter.api.Assertions.assertEquals(
                DeskSessionStore.DESK_TTL.getSeconds(),
                cookie.getMaxAge().getSeconds());
        org.junit.jupiter.api.Assertions.assertEquals(8L * 3600L, DeskSessionStore.DESK_TTL.toSeconds());
    }
}
