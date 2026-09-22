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
        assertTrue(TInvestMarketDataFeed.isTransientStreamDrop(
                new StatusRuntimeException(Status.CANCELLED.withDescription("stream cancelled"))));
        assertTrue(TInvestMarketDataFeed.isTransientStreamDrop(
                new StatusRuntimeException(Status.DEADLINE_EXCEEDED.withDescription("deadline"))));
    }

    @Test
    void fortsWeekdayHoursAreOpenForStaleWatchdog() {
        assertTrue(TInvestMarketDataFeed.sessionOpenMsk(
                java.time.LocalDateTime.of(2026, 9, 22, 17, 1)));
        assertTrue(TInvestMarketDataFeed.sessionOpenMsk(
                java.time.LocalDateTime.of(2026, 9, 22, 9, 0)));
        assertFalse(TInvestMarketDataFeed.sessionOpenMsk(
                java.time.LocalDateTime.of(2026, 9, 22, 8, 59)));
        assertFalse(TInvestMarketDataFeed.sessionOpenMsk(
                java.time.LocalDateTime.of(2026, 9, 26, 12, 0)));
    }

    @Test
    void silentHangDuringSessionReconnectsWithoutWaitingOnError() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/moex/trinity/marketdata/TInvestMarketDataFeed.java"));
        org.junit.jupiter.api.Assertions.assertTrue(src.contains("watchStaleStream"));
        org.junit.jupiter.api.Assertions.assertTrue(src.contains("STALE_STREAM_MS = 60_000L"));
        org.junit.jupiter.api.Assertions.assertTrue(src.contains("lastEventMs.set"));
        org.junit.jupiter.api.Assertions.assertTrue(src.contains("gen != streamGen.get()"),
                "stale gRPC onError from cancelled stream must not reconnect");
    }

    @Test
    void domDiskArchiveIsThrottledLiveBookIsNot() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/moex/trinity/marketdata/TInvestMarketDataFeed.java"));
        org.junit.jupiter.api.Assertions.assertTrue(src.contains("storeBook(inst, book)"));
        org.junit.jupiter.api.Assertions.assertTrue(src.contains("maybeArchiveDom(inst, book)"));
        org.junit.jupiter.api.Assertions.assertTrue(src.contains("DOM_ARCHIVE_MIN_MS = 10_000"),
                "disk DOM jsonl must not write every 2s into MEGA; live book still ticks");
    }
}
