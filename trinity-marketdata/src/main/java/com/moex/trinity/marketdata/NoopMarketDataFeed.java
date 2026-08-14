package com.moex.trinity.marketdata;

import java.util.Optional;

/**
 * Default feed while marketplace live contour is off / not wired.
 */
public final class NoopMarketDataFeed implements MarketDataFeed {

    @Override
    public MarketDataProviderId providerId() {
        return MarketDataProviderId.NOOP;
    }

    @Override
    public String statusMessage() {
        return "Market-data contour idle (NOOP). Enable imoex.marketdata + wire T-Invest stream later.";
    }

    @Override
    public boolean streaming() {
        return false;
    }

    @Override
    public Optional<DomBook> latestBook(String instrumentId) {
        return Optional.empty();
    }
}
