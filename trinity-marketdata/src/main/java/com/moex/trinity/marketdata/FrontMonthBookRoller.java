package com.moex.trinity.marketdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * After FORTS expiry the process stays up — re-subscribe the DOM stream to the
 * new front-month instead of waiting for a restart.
 */
public final class FrontMonthBookRoller {

    private static final Logger log = LoggerFactory.getLogger(FrontMonthBookRoller.class);

    private final MarketDataFeed feed;
    private final MarketDataResearchService research;
    private final String autoResolve;

    public FrontMonthBookRoller(
            MarketDataFeed feed,
            MarketDataResearchService research,
            String autoResolve
    ) {
        this.feed = feed;
        this.research = research;
        this.autoResolve = autoResolve == null || autoResolve.isBlank() ? "BR" : autoResolve.trim();
    }

    @Scheduled(fixedDelayString = "${imoex.marketdata.front-month-roll-ms:300000}", initialDelay = 20_000L)
    public void rollSubscribedFamilies() {
        Set<String> families = new LinkedHashSet<>();
        String autoFam = TInvestBrokerMarketData.familyOf(autoResolve);
        if (autoFam != null) {
            families.add(autoFam);
        }
        if (feed instanceof TInvestMarketDataFeed t) {
            for (String ticker : t.subscribedTickers()) {
                String fam = TInvestBrokerMarketData.familyOf(ticker);
                if (fam != null) {
                    families.add(fam);
                }
            }
        }
        for (String fam : families) {
            try {
                research.ensureLiveFront(fam);
            } catch (Exception ex) {
                log.debug("front-month roll {}: {}", fam, ex.toString());
            }
        }
    }
}
