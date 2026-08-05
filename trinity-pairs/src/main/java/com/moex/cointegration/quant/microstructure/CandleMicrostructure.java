package com.moex.cointegration.quant.microstructure;

/**
 * OHLCV-derived microstructure metrics (ATAS footprint proxy without tick tape).
 */
public record CandleMicrostructure(
        double relativeVolume,
        double spreadProxyBps,
        double deltaProxy,
        double volume
) {
}
