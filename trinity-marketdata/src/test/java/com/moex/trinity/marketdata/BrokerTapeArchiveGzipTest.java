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
}
