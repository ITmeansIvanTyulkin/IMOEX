package com.moex.trinity.marketdata;

/**
 * Facade for marketplace market-data contour (roadmap #4 foundation).
 */
public class MarketDataResearchService {

    private final MarketDataFeed feed;

    public MarketDataResearchService(MarketDataFeed feed) {
        this.feed = feed;
    }

    public MarketDataFeed feed() {
        return feed;
    }

    public String statusMessage() {
        return feed.statusMessage();
    }

    public boolean liveReady() {
        return feed.streaming();
    }
}
