package com.moex.cointegration.client;

import com.moex.cointegration.model.NewsItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RssNewsClientTest {

    @Test
    void parsesRssItemsWithinLookback() {
        RssNewsClient client = new RssNewsClient(new org.springframework.web.client.RestTemplate(),
                com.moex.cointegration.config.ImoexProperties.forTests(
                        "https://iss.moex.com/iss", "TQBR", "IMOEX", 5, 0.0005,
                        com.moex.cointegration.config.ImoexProperties.CointegrationProperties.of(0.05, 2.0, 0.0, 10),
                        new com.moex.cointegration.config.ImoexProperties.NewsProperties(true, 30, 10, 2),
                        "data", "data/charts"
                ));

        String xml = """
                <?xml version="1.0"?>
                <rss><channel>
                  <item>
                    <title><![CDATA[Сбербанк прибыль снизилась]]></title>
                    <pubDate>Mon, 20 Jul 2026 10:00:00 +0300</pubDate>
                    <guid>abc-1</guid>
                  </item>
                  <item>
                    <title>Старая новость</title>
                    <pubDate>Mon, 01 Jan 2020 10:00:00 +0300</pubDate>
                    <guid>old</guid>
                  </item>
                </channel></rss>
                """;

        List<NewsItem> items = client.parseRss(xml, "Interfax", 30);
        assertFalse(items.isEmpty());
        assertEquals("Interfax", items.get(0).source());
        assertEquals("Сбербанк прибыль снизилась", items.get(0).title());
    }
}
