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
}
