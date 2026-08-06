package com.moex.trinity.marketdata;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.LinkedHashMap;
import java.util.Map;

@AutoConfiguration
@ConditionalOnProperty(prefix = "imoex.marketdata", name = "enabled", havingValue = "true")
public class MarketDataAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(MarketDataFeed.class)
    MarketDataFeed marketDataFeed(
            @Value("${imoex.marketdata.provider:T_INVEST}") String provider,
            @Value("${imoex.marketdata.token:${imoex.broker.token:}}") String token,
            @Value("${imoex.marketdata.sandbox:false}") boolean sandbox,
            @Value("${imoex.marketdata.tape-capacity:200000}") int tapeCapacity,
            @Value("${imoex.marketdata.orderbook-depth:50}") int orderbookDepth,
            @Value("${imoex.marketdata.instruments:}") String instrumentsCsv,
            @Value("${imoex.marketdata.auto-resolve-instrument:BRU6}") String autoResolveInstrument
    ) {
        if (!"T_INVEST".equalsIgnoreCase(provider != null ? provider.trim() : "")) {
            return new NoopMarketDataFeed();
        }
        TInvestMarketDataFeed feed = new TInvestMarketDataFeed(tapeCapacity, orderbookDepth);
        Map<String, String> figiMap = parseInstrumentFigiMap(instrumentsCsv);
        String tok = token;
        boolean sb = sandbox;
        if (tok == null || tok.isBlank()) {
            TInvestCredentials creds = TInvestCredentials.resolve();
            if (creds.present()) {
                tok = creds.token();
                // keep imoex.marketdata.sandbox from yml (default false = prod MD)
            }
        }
        if ((figiMap == null || figiMap.isEmpty()) && tok != null && !tok.isBlank()
                && autoResolveInstrument != null && !autoResolveInstrument.isBlank()) {
            try (TInvestBrokerMarketData md = new TInvestBrokerMarketData(new TInvestCredentials(tok, sb))) {
                String figi = md.resolveFigi(autoResolveInstrument.trim());
                figiMap = new LinkedHashMap<>();
                figiMap.put(autoResolveInstrument.trim().toUpperCase(), figi);
            } catch (Exception ex) {
                // stay idle until FIGI mapped
            }
        }
        if (tok != null && !tok.isBlank() && figiMap != null && !figiMap.isEmpty()) {
            feed.start(tok.trim(), sb, figiMap);
        }
        return feed;
    }

    @Bean
    @ConditionalOnMissingBean(MarketDataResearchService.class)
    MarketDataResearchService marketDataResearchService(
            MarketDataFeed feed,
            @Value("${imoex.marketdata.auto-resolve-instrument:BRU6}") String instrument
    ) {
        return new MarketDataResearchService(
                feed,
                new com.moex.trinity.marketdata.BrokerTapeArchive(java.nio.file.Path.of("data", "broker-tape")),
                instrument
        );
    }

    /** Format: {@code BRU6=FIGIxxxx,BRQ6=FIGIyyyy} */
    static Map<String, String> parseInstrumentFigiMap(String csv) {
        Map<String, String> out = new LinkedHashMap<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String part : csv.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            int eq = p.indexOf('=');
            if (eq <= 0 || eq >= p.length() - 1) {
                continue;
            }
            out.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
        }
        return out;
    }
}
