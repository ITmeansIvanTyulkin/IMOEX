package com.moex.cointegration.quant.microstructure;

import com.moex.cointegration.model.OrderBookSnapshot;

/**
 * Анализ DOM (depth of market) snapshot.
 */
public final class DomAnalyzer {

    private DomAnalyzer() {
    }

    public static boolean passesDepth(OrderBookSnapshot book, double minDepthRub, double maxSpreadBps) {
        if (book == null || !book.valid()) {
            return false;
        }
        if (book.spreadBps() > maxSpreadBps) {
            return false;
        }
        return book.bidDepthRub() >= minDepthRub && book.askDepthRub() >= minDepthRub;
    }

    public static boolean supportsLongLeg(OrderBookSnapshot book, double minImbalance) {
        return book != null && book.imbalance() >= minImbalance;
    }

    public static boolean supportsShortLeg(OrderBookSnapshot book, double minImbalance) {
        return book != null && book.imbalance() <= -minImbalance;
    }
}
