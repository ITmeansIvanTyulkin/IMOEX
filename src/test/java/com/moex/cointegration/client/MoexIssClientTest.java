package com.moex.cointegration.client;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.Candle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MoexIssClientTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private MoexIssClient client;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        ImoexProperties props = ImoexProperties.forTests(
                "https://iss.moex.com/iss", "TQBR", "IMOEX", 5, 0.0005,
                ImoexProperties.CointegrationProperties.of(0.05, 2.0, 0.0, 10),
                new ImoexProperties.NewsProperties(false, 10, 10, 3),
                "data", "data/charts"
        );
        client = new MoexIssClient(restTemplate, props);
    }

    @Test
    void fetchImoexTickersParsesTickerColumn() {
        String body = """
                {"tickers":{"columns":["ticker"],"data":[["SBER"],["LKOH"],["SBER"]]}}
                """;
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/analytics/IMOEX/tickers.json")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<String> tickers = client.fetchImoexTickers();
        assertEquals(List.of("SBER", "LKOH"), tickers);
        server.verify();
    }

    @Test
    void fetchDailyCandlesParsesOhlcv() {
        String body = """
                {"candles":{"columns":["open","close","high","low","value","volume","begin","end"],
                "data":[[250.0,255.0,256.0,249.0,1.0,1000.0,"2024-01-15 00:00:00","2024-01-15 23:59:59"]]}}
                """;
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/securities/SBER/candles.json")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<Candle> candles = client.fetchDailyCandles("SBER", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31));
        assertEquals(1, candles.size());
        assertEquals(LocalDate.of(2024, 1, 15), candles.get(0).date());
        assertEquals(255.0, candles.get(0).close());
        server.verify();
    }
}