package com.moex.trinity.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Append-only broker archives (JSONL): trades tape + DOM snapshots for day-replay hist.
 * Broker has no hist DOM API — we accumulate live stream ourselves.
 */
public final class BrokerTapeArchive {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path dir;
    private final ReentrantLock lock = new ReentrantLock();

    public BrokerTapeArchive(Path dir) {
        this.dir = dir == null ? Path.of("data", "broker-tape") : dir;
    }

    public Path dir() {
        return dir;
    }

    public Path pathFor(String instrumentId, LocalDate day) {
        String id = instrumentId == null ? "UNK" : instrumentId.trim().toUpperCase();
        return dir.resolve("tape-" + day + "-" + id + ".jsonl");
    }

    public Path domPathFor(String instrumentId, LocalDate day) {
        String id = instrumentId == null ? "UNK" : instrumentId.trim().toUpperCase();
        return dir.resolve("dom-" + day + "-" + id + ".jsonl");
    }

    public void append(TradePrint print) {
        if (print == null || print.time() == null) {
            return;
        }
        LocalDate day = LocalDate.ofInstant(print.time(), MSK);
        Path file = pathFor(print.instrumentId(), day);
        lock.lock();
        try {
            Files.createDirectories(dir);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time", print.time().toString());
            row.put("price", print.price());
            row.put("qty", print.quantityLots());
            row.put("side", print.side() == null ? "UNKNOWN" : print.side().name());
            row.put("instrumentId", print.instrumentId());
            Files.writeString(file, MAPPER.writeValueAsString(row) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // best-effort
        } finally {
            lock.unlock();
        }
    }

    public void appendDom(DomBook book) {
        if (book == null || book.asOf() == null) {
            return;
        }
        LocalDate day = LocalDate.ofInstant(book.asOf(), MSK);
        Path file = domPathFor(book.instrumentId(), day);
        lock.lock();
        try {
            Files.createDirectories(dir);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time", book.asOf().toString());
            row.put("instrumentId", book.instrumentId());
            row.put("depth", book.depth());
            row.put("consistent", book.consistent());
            row.put("bids", levelsToMaps(book.bids()));
            row.put("asks", levelsToMaps(book.asks()));
            Files.writeString(file, MAPPER.writeValueAsString(row) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // best-effort
        } finally {
            lock.unlock();
        }
    }

    public List<TradePrint> loadDay(String instrumentId, LocalDate day) throws Exception {
        Path file = pathFor(instrumentId, day);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<TradePrint> out = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (line == null || line.isBlank()) {
                continue;
            }
            var node = MAPPER.readTree(line);
            String side = node.path("side").asText("UNKNOWN");
            TradePrint.TradeSide ts = switch (side) {
                case "BUY" -> TradePrint.TradeSide.BUY;
                case "SELL" -> TradePrint.TradeSide.SELL;
                default -> TradePrint.TradeSide.UNKNOWN;
            };
            out.add(new TradePrint(
                    instrumentId,
                    node.path("price").asDouble(),
                    node.path("qty").asLong(),
                    Instant.parse(node.path("time").asText()),
                    ts
            ));
        }
        return out;
    }

    /** DOM snapshots for the day, sorted by time ascending. */
    public List<DomBook> loadDomDay(String instrumentId, LocalDate day) throws Exception {
        Path file = domPathFor(instrumentId, day);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<DomBook> out = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (line == null || line.isBlank()) {
                continue;
            }
            JsonNode node = MAPPER.readTree(line);
            Instant t = Instant.parse(node.path("time").asText());
            out.add(new DomBook(
                    instrumentId,
                    node.path("depth").asInt(TInvestBrokerMarketData.MAX_ORDERBOOK_DEPTH),
                    mapsToLevels(node.path("bids")),
                    mapsToLevels(node.path("asks")),
                    t,
                    node.path("consistent").asBoolean(true)
            ));
        }
        out.sort(Comparator.comparing(DomBook::asOf, Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    private static List<Map<String, Object>> levelsToMaps(List<DomBook.DomLevel> levels) {
        if (levels == null || levels.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(levels.size());
        for (DomBook.DomLevel l : levels) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("p", l.price());
            m.put("q", l.quantityLots());
            out.add(m);
        }
        return out;
    }

    private static List<DomBook.DomLevel> mapsToLevels(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<DomBook.DomLevel> out = new ArrayList<>(arr.size());
        for (JsonNode n : arr) {
            out.add(new DomBook.DomLevel(n.path("p").asDouble(), n.path("q").asLong()));
        }
        return List.copyOf(out);
    }
}
