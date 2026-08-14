package com.moex.trinity.marketdata;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicEnergyContextTest {

    @Test
    void parseYahooClReadsLastAndDirection() {
        String json = "{\"chart\":{\"result\":[{\"meta\":{"
                + "\"regularMarketPrice\":72.5,\"chartPreviousClose\":70.0}}]}}";
        PublicEnergyContext.ClSnapshot s = PublicEnergyContext.parseYahooCl(json, Instant.parse("2026-08-13T12:00:00Z"));
        assertTrue(s.ok());
        assertEquals(72.5, s.last(), 1e-9);
        assertEquals("UP", s.direction());
        assertTrue(s.ruBrief().contains("WTI"));
    }

    @Test
    void parseFredWeeklyComputesSurprise() {
        String csv = """
                DATE,WCESTUS1
                2026-07-17,420000
                2026-07-24,421000
                2026-07-31,422000
                2026-08-07,425000
                """;
        PublicEnergyContext.EiaPrint p = PublicEnergyContext.parseFredWeekly(csv);
        assertTrue(p.ok());
        assertEquals(425000, p.stocksKbbl(), 1e-6);
        assertEquals(3000, p.wowKbbl(), 1e-6);
        assertTrue(p.ruLine().contains("EIA"));
    }

    @Test
    void parseCotAndCrack() {
        String cot = "[{\"report_date_as_yyyy_mm_dd\":\"2026-08-05\","
                + "\"noncomm_positions_long_all\":\"500000\","
                + "\"noncomm_positions_short_all\":\"200000\"}]";
        PublicEnergyContext.CotSnapshot c = PublicEnergyContext.parseCotJson(cot);
        assertTrue(c.ok());
        assertEquals(300000, c.noncommNet(), 1e-6);

        PublicEnergyContext.CrackSnapshot k = PublicEnergyContext.CrackSnapshot.of(70, 2.0, 2.2);
        assertTrue(k.ok());
        assertTrue(k.crack321Usd() > 0);

        PublicEnergyContext.Consensus cons = PublicEnergyContext.parseConsensusJson(
                "{\"kbbl\":-1500,\"source\":\"street\"}", "hint");
        assertEquals(-1500, cons.kbbl(), 1e-9);
        assertEquals("street", cons.source());
    }
}
