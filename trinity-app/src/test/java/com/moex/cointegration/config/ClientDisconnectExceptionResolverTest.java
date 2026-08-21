package com.moex.cointegration.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientDisconnectExceptionResolverTest {

    @Test
    void detectsBrokenPipeAndAsyncGone() {
        assertTrue(ClientDisconnectExceptionResolver.isClientGone(
                new AsyncRequestNotUsableException("ServletOutputStream failed to write: java.io.IOException: Broken pipe")));
        assertTrue(ClientDisconnectExceptionResolver.isClientGone(
                new IOException("Broken pipe")));
        assertTrue(ClientDisconnectExceptionResolver.isClientGone(
                new RuntimeException(new IOException("Connection reset by peer"))));
        assertFalse(ClientDisconnectExceptionResolver.isClientGone(
                new IllegalStateException("desk not ready")));
    }
}
