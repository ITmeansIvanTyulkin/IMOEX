package com.moex.trinity.marketdata;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TInvestMarketDataFeedReconnectTest {

    @Test
    void treatsGrpcEosAsTransient() {
        assertTrue(TInvestMarketDataFeed.isTransientStreamDrop(
                new StatusRuntimeException(Status.INTERNAL.withDescription(
                        "Received unexpected EOS on empty DATA frame from server"))));
        assertTrue(TInvestMarketDataFeed.isTransientStreamDrop(
                new StatusRuntimeException(Status.UNAVAILABLE.withDescription("channel closed"))));
        assertFalse(TInvestMarketDataFeed.isTransientStreamDrop(
                new StatusRuntimeException(Status.UNAUTHENTICATED.withDescription("bad token"))));
        assertFalse(TInvestMarketDataFeed.isTransientStreamDrop(null));
    }
}
