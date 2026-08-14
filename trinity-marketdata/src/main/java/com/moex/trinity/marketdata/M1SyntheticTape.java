package com.moex.trinity.marketdata;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Research proxy when ISS/broker historical prints are unavailable:
 * distribute each M1 bar's volume across H–L price buckets at bar time.
 * Not a real footprint — price-aligned for day-replay VAP only.
 */
public final class M1SyntheticTape {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");

    private M1SyntheticTape() {
    }

    /**
     * @param pointSize instrument point (BR = 0.01)
     * @param bars      M1 OHLC with Moscow local times
     */
    public static List<TradePrint> fromM1Bars(
            String instrumentId,
            List<M1Bar> bars,
            double pointSize
    ) {
        String id = instrumentId == null || instrumentId.isBlank() ? "BR" : instrumentId.trim();
        double pt = pointSize > 0 ? pointSize : 0.01;
        List<TradePrint> out = new ArrayList<>();
        if (bars == null) {
            return out;
        }
        for (M1Bar b : bars) {
            if (b == null || b.volume() <= 0 || b.time() == null) {
                continue;
            }
            double lo = Math.min(b.low(), b.high());
            double hi = Math.max(b.low(), b.high());
            long loB = Math.round(lo / pt);
            long hiB = Math.round(hi / pt);
            if (hiB < loB) {
                long t = loB;
                loB = hiB;
                hiB = t;
            }
            long steps = hiB - loB + 1;
            double per = b.volume() / (double) steps;
            if (per <= 0) {
                continue;
            }
            // lots as long; keep at least 1 on POC-ish mid if fractional
            Instant t0 = b.time().atZone(MSK).toInstant();
            for (long bucket = loB; bucket <= hiB; bucket++) {
                long lots = Math.max(1, Math.round(per));
                double px = bucket * pt;
                out.add(new TradePrint(id, px, lots, t0, TradePrint.TradeSide.UNKNOWN));
            }
        }
        out.sort(Comparator.comparing(TradePrint::time, Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    /** Minimal M1 bar for synthetic tape (avoids depending on trinity-trend). */
    public record M1Bar(
            LocalDateTime time,
            double high,
            double low,
            double volume
    ) {
    }
}
