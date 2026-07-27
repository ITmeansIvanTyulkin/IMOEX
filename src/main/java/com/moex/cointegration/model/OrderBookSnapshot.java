package com.moex.cointegration.model;

/**
 * Top-of-book / depth snapshot с MOEX ISS (DOM proxy).
 */
public record OrderBookSnapshot(
        String ticker,
        double bestBid,
        double bestAsk,
        double bidDepthRub,
        double askDepthRub,
        double spreadBps
) {
    public double imbalance() {
        double total = bidDepthRub + askDepthRub;
        if (total <= 0) {
            return 0;
        }
        return (bidDepthRub - askDepthRub) / total;
    }

    public boolean valid() {
        return bestBid > 0 && bestAsk > 0 && bestAsk >= bestBid;
    }
}
