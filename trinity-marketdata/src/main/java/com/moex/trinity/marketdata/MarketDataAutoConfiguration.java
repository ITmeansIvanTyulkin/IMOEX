package com.moex.trinity.marketdata;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

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
            @Value("${imoex.marketdata.auto-resolve-instrument:BR}") String autoResolveInstrument
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
                figiMap = new LinkedHashMap<>();
                Optional<TInvestBrokerMarketData.FrontMonth> fm = md.resolveFrontMonth(autoResolveInstrument.trim());
                if (fm.isPresent()) {
                    figiMap.put(fm.get().ticker().toUpperCase(), fm.get().figi());
                } else {
                    String figi = md.resolveFigi(autoResolveInstrument.trim());
                    figiMap.put(autoResolveInstrument.trim().toUpperCase(), figi);
                }
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
    @ConditionalOnMissingBean(PublicEnergyContext.class)
    PublicEnergyContext publicEnergyContext(
            @Value("${imoex.data-dir:data}") String dataDir,
            @Value("${imoex.strategies.calendar-arb.eia-consensus-url:}") String consensusUrl
    ) {
        return new PublicEnergyContext(Path.of(dataDir, "eia-consensus.json"), consensusUrl);
    }

    @Bean
    @ConditionalOnMissingBean(MarketDataResearchService.class)
    MarketDataResearchService marketDataResearchService(
            MarketDataFeed feed,
            @Value("${imoex.marketdata.auto-resolve-instrument:BR}") String instrument
    ) {
        String live = instrument == null || instrument.isBlank() ? "BRU6" : instrument.trim();
        TInvestCredentials creds = TInvestCredentials.resolve();
        if (creds.present()) {
            try (TInvestBrokerMarketData md = new TInvestBrokerMarketData(creds)) {
                live = md.resolveFrontMonthTicker(live);
            } catch (Exception ignored) {
                // keep yml hint
            }
        }
        return new MarketDataResearchService(
                feed,
                new com.moex.trinity.marketdata.BrokerTapeArchive(java.nio.file.Path.of("data", "broker-tape")),
                live
        );
    }

    @Bean
    @ConditionalOnMissingBean(FrontMonthBookRoller.class)
    FrontMonthBookRoller frontMonthBookRoller(
            MarketDataFeed feed,
            MarketDataResearchService research,
            @Value("${imoex.marketdata.auto-resolve-instrument:BR}") String instrument
    ) {
        return new FrontMonthBookRoller(feed, research, instrument);
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
