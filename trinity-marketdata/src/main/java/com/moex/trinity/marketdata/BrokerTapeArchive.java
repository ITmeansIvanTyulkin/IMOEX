package com.moex.trinity.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Append-only broker archives (JSONL): trades tape + DOM snapshots for day-replay hist.
 * Broker has no hist DOM API — we accumulate live stream ourselves.
 * Live day stays {@code .jsonl}; closed days are gzipped in place to {@code .jsonl.gz}.
 */
public final class BrokerTapeArchive {

    private static final Logger log = LoggerFactory.getLogger(BrokerTapeArchive.class);
    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern DAY_FILE = Pattern.compile("^(tape|dom)-(\\d{4}-\\d{2}-\\d{2})-.+\\.jsonl$");

    private final Path dir;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicReference<LocalDate> lastClosedSweep = new AtomicReference<>();

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

    public long lineCount(Path file) {
        Path resolved = existingReadable(file);
        if (resolved == null || !Files.isRegularFile(resolved)) {
            return 0L;
        }
        try (BufferedReader in = openLines(resolved)) {
            long n = 0L;
            while (in.readLine() != null) {
                n++;
            }
            return n;
        } catch (Exception ex) {
            return 0L;
        }
    }

    public long tapeLines(String instrumentId, LocalDate day) {
        return lineCount(pathFor(instrumentId, day));
    }

    public long domLines(String instrumentId, LocalDate day) {
        return lineCount(domPathFor(instrumentId, day));
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
        maybeCompressClosed(day);
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
        maybeCompressClosed(day);
    }

    /**
     * Gzip {@code .jsonl} whose session date is before {@code openDay} (MSK). Today's files stay appendable.
     * Safe to call on stream start and after midnight.
     *
     * @return number of files compressed
     */
    public int compressClosedBefore(LocalDate openDay) {
        if (openDay == null || dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        lastClosedSweep.set(openDay);
        List<Path> jsonlFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jsonl")) {
            for (Path jsonl : stream) {
                jsonlFiles.add(jsonl);
            }
        } catch (Exception ex) {
            log.warn("broker-tape gzip listing failed: {}", ex.toString());
            return 0;
        }
        int n = 0;
        lock.lock();
        try {
            for (Path jsonl : jsonlFiles) {
                if (gzipIfClosed(jsonl, openDay)) {
                    n++;
                }
            }
        } finally {
            lock.unlock();
        }
        if (n > 0) {
            log.info("broker-tape gzipped {} closed-day file(s) before {}", n, openDay);
        }
        return n;
    }

    /** Compress closed days relative to today MSK — used when the live stream starts. */
    public int compressClosedDays() {
        return compressClosedBefore(LocalDate.now(MSK));
    }

    private void maybeCompressClosed(LocalDate liveDay) {
        LocalDate prev = lastClosedSweep.get();
        if (liveDay.equals(prev)) {
            return;
        }
        if (!lastClosedSweep.compareAndSet(prev, liveDay)) {
            return;
        }
        Thread t = new Thread(() -> compressClosedBefore(liveDay), "broker-tape-gzip");
        t.setDaemon(true);
        t.start();
    }

    private boolean gzipIfClosed(Path jsonl, LocalDate openDay) {
        String name = jsonl.getFileName().toString();
        Matcher m = DAY_FILE.matcher(name);
        if (!m.matches()) {
            return false;
        }
        LocalDate day;
        try {
            day = LocalDate.parse(m.group(2));
        } catch (Exception ex) {
            return false;
        }
        if (!day.isBefore(openDay) || !Files.isRegularFile(jsonl)) {
            return false;
        }
        Path gz = Path.of(jsonl.toString() + ".gz");
        Path tmp = Path.of(jsonl.toString() + ".gz.tmp");
        try {
            try (InputStream in = Files.newInputStream(jsonl);
                 OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmp))) {
                in.transferTo(out);
            }
            try {
                Files.move(tmp, gz, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomic) {
                Files.move(tmp, gz, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.deleteIfExists(jsonl);
            return true;
        } catch (Exception ex) {
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignored) {
                // leave jsonl
            }
            log.warn("gzip {} failed: {}", name, ex.toString());
            return false;
        }
    }

    public List<TradePrint> loadDay(String instrumentId, LocalDate day) throws Exception {
        Path file = existingReadable(pathFor(instrumentId, day));
        if (file == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        List<TradePrint> out = new ArrayList<>();
        try (BufferedReader in = openLines(file)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
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
        }
        return out;
    }

    /**
     * DOM snapshots for the day, sorted by time ascending.
     * Subsamples to ≤1 book per minute while streaming the file — live days grow to
     * tens of MB at 500ms cadence across many FIGIs; loading every snapshot OOMs the heap
     * (calendar-arb OOS hist lookup). H1/replay only needs the latest book at bar clock.
     */
    public List<DomBook> loadDomDay(String instrumentId, LocalDate day) throws Exception {
        Path file = existingReadable(domPathFor(instrumentId, day));
        if (file == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        List<DomBook> out = new ArrayList<>();
        long lastKeptEpochMin = Long.MIN_VALUE;
        try (BufferedReader in = openLines(file)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = MAPPER.readTree(line);
                Instant t = Instant.parse(node.path("time").asText());
                long epochMin = t.getEpochSecond() / 60L;
                if (epochMin == lastKeptEpochMin && !out.isEmpty()) {
                    // Keep the latest snapshot inside the same minute bucket.
                    out.set(out.size() - 1, new DomBook(
                            instrumentId,
                            node.path("depth").asInt(TInvestBrokerMarketData.MAX_ORDERBOOK_DEPTH),
                            mapsToLevels(node.path("bids")),
                            mapsToLevels(node.path("asks")),
                            t,
                            node.path("consistent").asBoolean(true)
                    ));
                    continue;
                }
                lastKeptEpochMin = epochMin;
                out.add(new DomBook(
                        instrumentId,
                        node.path("depth").asInt(TInvestBrokerMarketData.MAX_ORDERBOOK_DEPTH),
                        mapsToLevels(node.path("bids")),
                        mapsToLevels(node.path("asks")),
                        t,
                        node.path("consistent").asBoolean(true)
                ));
            }
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

    /** Uncompressed live path, else closed {@code .jsonl.gz} from the cold archive. */
    static Path existingReadable(Path jsonl) {
        if (jsonl == null) {
            return null;
        }
        if (Files.isRegularFile(jsonl)) {
            return jsonl;
        }
        Path gz = Path.of(jsonl.toString() + ".gz");
        return Files.isRegularFile(gz) ? gz : jsonl;
    }

    private static List<String> readAllLines(Path file) throws Exception {
        try (BufferedReader in = openLines(file)) {
            List<String> out = new ArrayList<>();
            String line;
            while ((line = in.readLine()) != null) {
                out.add(line);
            }
            return out;
        }
    }

    private static BufferedReader openLines(Path file) throws Exception {
        if (file.getFileName().toString().endsWith(".gz")) {
            return new BufferedReader(new InputStreamReader(
                    new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8));
        }
        return Files.newBufferedReader(file, StandardCharsets.UTF_8);
    }
}
