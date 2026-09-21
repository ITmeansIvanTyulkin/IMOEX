package com.moex.cointegration.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrendTapeHandshakeInterceptorTest {

    @Test
    void onlyLoopbackHttpOrigins() {
        assertTrue(TrendTapeHandshakeInterceptor.originAllowed("http://127.0.0.1:8080"));
        assertTrue(TrendTapeHandshakeInterceptor.originAllowed("http://localhost:8080"));
        assertFalse(TrendTapeHandshakeInterceptor.originAllowed("https://evil.example"));
        assertFalse(TrendTapeHandshakeInterceptor.originAllowed("http://192.168.0.10:8080"));
        assertFalse(TrendTapeHandshakeInterceptor.originAllowed(null));
        assertFalse(TrendTapeHandshakeInterceptor.originAllowed(""));
    }
}
