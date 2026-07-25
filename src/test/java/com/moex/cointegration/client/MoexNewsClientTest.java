package com.moex.cointegration.client;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.NewsItem;
import com.moex.cointegration.model.SecurityTradingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MoexNewsClientTest {

    private MockRestServiceServer server;
    private MoexNewsClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        ImoexProperties props = ImoexProperties.forTests(
                "https://iss.moex.com/iss", "TQBR", "IMOEX", 5, 0.0005,
                ImoexProperties.CointegrationProperties.of(0.05, 2.0, 0.0, 10),
                new ImoexProperties.NewsProperties(true, 10, 10, 2),
                "data", "data/charts"
        );
        client = new MoexNewsClient(restTemplate, props);
    }

    @Test
    void fetchSiteNewsParsesRows() {
        String today = LocalDate.now().atTime(12, 0).format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String body = """
                {"sitenews":{"columns":["id","title","published_at"],
                "data":[[1,"Тест новость","%s"]]}}
                """.formatted(today);
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/sitenews.json")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<NewsItem> news = client.fetchSiteNews(10, 1);
        assertEquals(1, news.size());
        assertEquals("Тест новость", news.get(0).title());
        server.verify();
    }

    @Test
    void fetchTradingStatusParsesSecurity() {
        String body = """
                {"securities":{"columns":["SECID","STATUS","SHORTNAME","SECNAME"],
                "data":[["SBER","A","Сбербанк","Сбербанк России"]]},
                "marketdata":{"columns":["SECID","TRADINGSTATUS"],"data":[["SBER","T"]]}}
                """;
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/securities/SBER.json")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        SecurityTradingStatus status = client.fetchTradingStatus("SBER");
        assertTrue(status.found());
        assertEquals("A", status.status());
        assertEquals("T", status.tradingStatus());
        server.verify();
    }
}
