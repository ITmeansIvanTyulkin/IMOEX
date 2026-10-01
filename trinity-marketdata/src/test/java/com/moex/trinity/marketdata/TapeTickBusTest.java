package com.moex.trinity.marketdata;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TapeTickBusTest {

    @Test
    void publishFansOutAndSkipsBadPrints() {
        TapeTickBus bus = new TapeTickBus();
        List<Double> seen = new ArrayList<>();
        bus.addListener(p -> seen.add(p.price()));
        bus.publish(null);
        bus.publish(new TradePrint("BRV6", 0, 1, Instant.now(), TradePrint.TradeSide.BUY));
        bus.publish(new TradePrint("BRV6", 106.63, 2, Instant.now(), TradePrint.TradeSide.SELL));
        assertEquals(List.of(106.63), seen);
        assertEquals(1, bus.listenerCount());
    }

    @Test
    void listenerExceptionDoesNotStopOthers() {
        TapeTickBus bus = new TapeTickBus();
        AtomicInteger n = new AtomicInteger();
        bus.addListener(p -> {
            throw new RuntimeException("boom");
        });
        bus.addListener(p -> n.incrementAndGet());
        bus.publish(new TradePrint("BRV6", 106.5, 1, Instant.now(), TradePrint.TradeSide.BUY));
        assertEquals(1, n.get());
    }
}
