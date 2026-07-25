package com.moex.cointegration.service;

import com.moex.cointegration.client.MoexIssClient;
import com.moex.cointegration.client.MoexNewsClient;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.FinalTradeDecision;
import com.moex.cointegration.model.FinalTradeRecommendation;
import com.moex.cointegration.model.NewsItem;
import com.moex.cointegration.model.SecurityTradingStatus;
import com.moex.cointegration.model.TradingRecommendation;
import com.moex.cointegration.model.TradingSignal;
import com.moex.cointegration.news.NewsTriggerMatcher;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Тесты без Mockito: на Java 24 inline-mock ByteBuddy из Boot 3.3 ещё не поддерживается.
 */
class NewsRiskAnalysisServiceTest {

    @Test
    void blocksPairWithStaleCandleEvenWithoutNews() {
        FakeMoexNewsClient newsClient = new FakeMoexNewsClient(List.of());
        newsClient.status("SBER", tradable("SBER", "Сбербанк"));
        newsClient.status("LKOH", tradable("LKOH", "ЛУКОЙЛ"));

        NewsRiskAnalysisService service = new NewsRiskAnalysisService(
                newsClient,
                new FakeMoexIssClient(List.of("SBER", "LKOH")),
                new NewsTriggerMatcher(),
                props(true)
        );

        TradingRecommendation tech = new TradingRecommendation(
                "SBER", "LKOH", TradingSignal.LONG_SPREAD,
                -2.5, LocalDate.of(2022, 1, 10), -0.1,
                0.8, 12, 1.1, 0.01,
                "long", "details"
        );

        List<FinalTradeRecommendation> result = service.analyze(List.of(tech));
        assertEquals(1, result.size());
        assertEquals(FinalTradeDecision.BLOCK, result.get(0).decision());
    }

    @Test
    void blocksOnTradingHaltNews() {
        FakeMoexNewsClient newsClient = new FakeMoexNewsClient(List.of(
                new NewsItem(1L, "О приостановке торгов ценными бумагами SBER", LocalDateTime.now(), "TEST")
        ));
        newsClient.status("SBER", tradable("SBER", "Сбербанк"));
        newsClient.status("LKOH", tradable("LKOH", "ЛУКОЙЛ"));

        NewsRiskAnalysisService service = new NewsRiskAnalysisService(
                newsClient,
                new FakeMoexIssClient(List.of("SBER", "LKOH")),
                new NewsTriggerMatcher(),
                props(true)
        );

        TradingRecommendation tech = new TradingRecommendation(
                "SBER", "LKOH", TradingSignal.SHORT_SPREAD,
                2.4, LocalDate.now().minusDays(1), 0.1,
                0.8, 12, 1.1, 0.01,
                "short", "details"
        );

        assertEquals(FinalTradeDecision.BLOCK, service.analyze(List.of(tech)).get(0).decision());
    }

    private static SecurityTradingStatus tradable(String ticker, String shortName) {
        return new SecurityTradingStatus(ticker, true, "A", "T", shortName, shortName);
    }

    private static ImoexProperties props(boolean enabled) {
        return new ImoexProperties(
                "https://iss.moex.com/iss",
                "TQBR",
                "IMOEX",
                5,
                0.0005,
                new ImoexProperties.CointegrationProperties(0.05, 2.0, 0.0, 10),
                new ImoexProperties.NewsProperties(enabled, 10, 10, 3),
                "data",
                "data/charts"
        );
    }

    /** Ручной stub вместо Mockito. */
    private static final class FakeMoexNewsClient extends MoexNewsClient {
        private final List<NewsItem> news;
        private final Map<String, SecurityTradingStatus> statuses = new HashMap<>();

        private FakeMoexNewsClient(List<NewsItem> news) {
            super(new RestTemplate(), props(true));
            this.news = news;
        }

        void status(String ticker, SecurityTradingStatus status) {
            statuses.put(ticker, status);
        }

        @Override
        public List<NewsItem> fetchSiteNews(int lookbackDays, int maxPages) {
            return news;
        }

        @Override
        public SecurityTradingStatus fetchTradingStatus(String ticker) {
            return statuses.getOrDefault(ticker, SecurityTradingStatus.missing(ticker));
        }
    }

    private static final class FakeMoexIssClient extends MoexIssClient {
        private final List<String> tickers;

        private FakeMoexIssClient(List<String> tickers) {
            super(new RestTemplate(), props(true));
            this.tickers = tickers;
        }

        @Override
        public List<String> fetchImoexTickers() {
            return tickers;
        }
    }
}
