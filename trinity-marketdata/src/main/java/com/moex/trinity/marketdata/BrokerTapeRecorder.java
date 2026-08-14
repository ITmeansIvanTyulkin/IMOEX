package com.moex.trinity.marketdata;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Long-running recorder: T-Invest stream → {@code data/broker-tape/*.jsonl} + live DOM depth 50.
 *
 * <pre>
 * mvn -pl trinity-marketdata -q exec:java -Dexec.mainClass=com.moex.trinity.marketdata.BrokerTapeRecorder -Dexec.args="BRU6"
 * </pre>
 */
public final class BrokerTapeRecorder {

    public static void main(String[] args) throws Exception {
        String instrument = args.length > 0 ? args[0].trim().toUpperCase(Locale.ROOT) : "BRU6";
        TInvestCredentials creds = TInvestCredentials.resolve();
        if (!creds.present()) {
            System.err.println("Need T_INVEST_TOKEN or data/broker-ui-settings.json");
            System.exit(2);
        }
        // Prefer prod MD unless explicitly sandbox
        boolean sandbox = Boolean.parseBoolean(
                System.getProperty("imoex.marketdata.sandbox", String.valueOf(creds.sandbox())));
        System.out.printf(Locale.ROOT, "Recording %s (sandbox=%s)… Ctrl+C to stop%n", instrument, sandbox);

        try (TInvestBrokerMarketData md = new TInvestBrokerMarketData(new TInvestCredentials(creds.token(), sandbox))) {
            String figi = md.resolveFigi(instrument);
            System.out.printf(Locale.ROOT, "FIGI %s → %s%n", instrument, figi);
            DomBook book = md.fetchOrderBook(instrument, figi, TInvestBrokerMarketData.MAX_ORDERBOOK_DEPTH);
            System.out.printf(Locale.ROOT, "DOM seed depth=%d bids=%d asks=%d%n",
                    book.depth(), book.bids().size(), book.asks().size());

            TInvestMarketDataFeed feed = new TInvestMarketDataFeed(500_000, TInvestBrokerMarketData.MAX_ORDERBOOK_DEPTH);
            feed.start(creds.token(), sandbox, Map.of(instrument, figi));
            System.out.println(feed.statusMessage());

            CountDownLatch latch = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                feed.close();
                latch.countDown();
            }));
            while (!latch.await(30, TimeUnit.SECONDS)) {
                System.out.printf(Locale.ROOT, "  … tape=%d domArchive throttle=500ms streaming=%s book=%s%n",
                        feed.recentTrades(instrument).size(),
                        feed.streaming(),
                        feed.latestBook(instrument).map(b -> "depth=" + b.depth()
                                + " bids=" + b.bids().size()).orElse("none"));
            }
        }
    }
}
