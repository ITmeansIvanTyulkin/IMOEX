package com.moex.trinity.marketdata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrokerTapeArchiveGzipTest {

    @TempDir
    Path tmp;

    @Test
    void loadDayReadsGzippedTapeWhenJsonlGone() throws Exception {
        BrokerTapeArchive archive = new BrokerTapeArchive(tmp);
        LocalDate day = LocalDate.of(2026, 8, 19);
        TradePrint print = new TradePrint(
                "NGV6", 3.053, 1, Instant.parse("2026-08-19T06:13:54Z"), TradePrint.TradeSide.BUY);
        archive.append(print);
        Path jsonl = archive.pathFor("NGV6", day);
        Path gz = Path.of(jsonl.toString() + ".gz");
        try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(gz))) {
            out.write(Files.readAllBytes(jsonl));
        }
        Files.delete(jsonl);

        List<TradePrint> loaded = archive.loadDay("NGV6", day);
        assertEquals(1, loaded.size());
        assertEquals(3.053, loaded.get(0).price(), 1e-9);
        assertEquals(1, archive.tapeLines("NGV6", day));
        assertTrue(BrokerTapeArchive.existingReadable(jsonl).toString().endsWith(".gz"));
    }

    @Test
    void compressClosedBeforeGzipsYesterdayAndKeepsToday() throws Exception {
        BrokerTapeArchive archive = new BrokerTapeArchive(tmp);
        LocalDate yesterday = LocalDate.of(2026, 8, 21);
        LocalDate today = LocalDate.of(2026, 8, 22);
        Path oldJsonl = archive.pathFor("BRU6", yesterday);
        Path todayJsonl = archive.pathFor("BRU6", today);
        Files.createDirectories(tmp);
        Files.writeString(oldJsonl, "{\"time\":\"2026-08-21T10:00:00Z\",\"price\":93.5,\"qty\":1,"
                + "\"side\":\"BUY\",\"instrumentId\":\"BRU6\"}\n");
        Files.writeString(todayJsonl, "{\"time\":\"2026-08-22T10:00:00Z\",\"price\":93.6,\"qty\":1,"
                + "\"side\":\"SELL\",\"instrumentId\":\"BRU6\"}\n");

        assertEquals(1, archive.compressClosedBefore(today));
        assertTrue(Files.isRegularFile(Path.of(oldJsonl.toString() + ".gz")));
        assertTrue(Files.notExists(oldJsonl));
        assertTrue(Files.isRegularFile(todayJsonl));
        assertEquals(1, archive.loadDay("BRU6", yesterday).size());
        assertEquals(1, archive.loadDay("BRU6", today).size());
    }

    @Test
    void thinLiveDomKeepsLatestSnapshotPerMinute() throws Exception {
        BrokerTapeArchive archive = new BrokerTapeArchive(tmp);
        LocalDate day = LocalDate.of(2026, 9, 22);
        Path dom = archive.domPathFor("BRV6", day);
        Files.createDirectories(tmp);
        StringBuilder sb = new StringBuilder(400_000);
        // 3 minutes × dense snaps; pad asks so fixture exceeds the 256 KiB thin gate.
        String padAsks = ",\"asks\":[" + "{\"p\":100.0,\"q\":1},".repeat(40) + "{\"p\":101.0,\"q\":1}]";
        for (int i = 0; i < 900; i++) {
            int min = i / 300;          // 0,1,2
            int sec = (i / 5) % 60;
            int price = 90 + i;
            sb.append("{\"time\":\"2026-09-22T10:0").append(min).append(':');
            if (sec < 10) {
                sb.append('0');
            }
            sb.append(sec).append('.').append(String.format("%03d", (i % 5) * 100))
                    .append("Z\",\"instrumentId\":\"BRV6\",\"depth\":1,\"consistent\":true,")
                    .append("\"bids\":[{\"p\":").append(price).append(".0,\"q\":1}]")
                    .append(padAsks).append("}\n");
        }
        Files.writeString(dom, sb.toString());
        long before = Files.size(dom);
        assertTrue(before > 256_000L, "fixture must exceed thin threshold");

        assertEquals(1, archive.thinLiveDomToOnePerMinute(day));
        long after = Files.size(dom);
        assertTrue(after < before / 20, "dense DOM must collapse toward 1/min");
        List<DomBook> books = archive.loadDomDay("BRV6", day);
        assertEquals(3, books.size());
        assertEquals(389.0, books.get(0).bids().get(0).price(), 1e-9); // last of minute 0
        assertEquals(689.0, books.get(1).bids().get(0).price(), 1e-9);
        assertEquals(989.0, books.get(2).bids().get(0).price(), 1e-9);
    }
}
