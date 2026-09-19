package com.moex.cointegration.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moex.trinity.marketdata.DomBook;
import com.moex.trinity.marketdata.MarketDataFeed;
import com.moex.trinity.marketdata.TapeTickBus;
import com.moex.trinity.marketdata.TInvestBrokerMarketData;
import com.moex.trinity.marketdata.TradePrint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Coalesced tape + DOM to every chart surface (desk, terminal, arb, pairs).
 */
@Component
public class TrendTapeWebSocketHandler extends TextWebSocketHandler implements TapeTickBus.Listener {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int BOOK_LEVELS = 50;

    private final TapeTickBus bus;
    private final Optional<MarketDataFeed> feed;
    private final ConcurrentHashMap<String, Client> clients = new ConcurrentHashMap<>();
    /** sessionId|INSTRUMENT → last book in the 40ms window. */
    private final ConcurrentHashMap<String, DomBook> pendingBooks = new ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<QueuedPrint> tradeQ =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService flushExec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "trend-tape-ws");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public TrendTapeWebSocketHandler(TapeTickBus bus, @Autowired(required = false) MarketDataFeed feed) {
        this.bus = bus;
        this.feed = Optional.ofNullable(feed);
    }

    @PostConstruct
    void start() {
        bus.addListener(this);
        flushExec.scheduleAtFixedRate(this::flush, 40, 40, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        bus.removeListener(this);
        flushExec.shutdownNow();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        clients.put(session.getId(), new Client(session));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        drop(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        drop(session.getId());
    }

    private void drop(String id) {
        clients.remove(id);
        tradeQ.removeIf(q -> q != null && id.equals(q.sessionId));
        pendingBooks.keySet().removeIf(k -> k.startsWith(id + "|"));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Client c = clients.get(session.getId());
        if (c == null) {
            return;
        }
        applySubscribe(c, message.getPayload());
        snapshot(c);
    }

    @Override
    public void onTapePrint(TradePrint print) {
        if (print == null) {
            return;
        }
        for (var e : clients.entrySet()) {
            if (e.getValue().wants(print.instrumentId())) {
                if (tradeQ.size() > 4000) {
                    tradeQ.poll();
                }
                tradeQ.offer(new QueuedPrint(e.getKey(), print));
            }
        }
    }

    @Override
    public void onBook(DomBook book) {
        if (book == null) {
            return;
        }
        for (var e : clients.entrySet()) {
            if (e.getValue().wants(book.instrumentId())) {
                pendingBooks.put(e.getKey() + "|" + keyInst(book.instrumentId()), book);
            }
        }
    }

    private void snapshot(Client c) {
        if (feed.isEmpty()) {
            return;
        }
        MarketDataFeed md = feed.get();
        for (String inst : c.instruments) {
            md.lastTrade(inst).ifPresent(p -> tradeQ.offer(new QueuedPrint(c.session.getId(), p)));
            md.latestBook(inst).ifPresent(b ->
                    pendingBooks.put(c.session.getId() + "|" + keyInst(b.instrumentId()), b));
        }
        if (c.all) {
            for (DomBook b : md.snapshotBooks()) {
                if (b != null) {
                    pendingBooks.put(c.session.getId() + "|" + keyInst(b.instrumentId()), b);
                    md.lastTrade(b.instrumentId()).ifPresent(p ->
                            tradeQ.offer(new QueuedPrint(c.session.getId(), p)));
                }
            }
        }
    }

    private void flush() {
        int n = 0;
        QueuedPrint qp;
        while (n < 120 && (qp = tradeQ.poll()) != null) {
            Client c = clients.get(qp.sessionId);
            if (c != null && qp.print != null) {
                sendQuiet(c.session, toJson(qp.print));
                n++;
            }
        }
        pendingBooks.forEach((key, book) -> {
            pendingBooks.remove(key, book);
            Client c = clientOf(key);
            if (c != null && book != null) {
                sendQuiet(c.session, toBookJson(book));
            }
        });
    }

    private Client clientOf(String pendingKey) {
        int cut = pendingKey.indexOf('|');
        String id = cut < 0 ? pendingKey : pendingKey.substring(0, cut);
        return clients.get(id);
    }

    static void applySubscribe(Client c, String payload) {
        if (c == null) {
            return;
        }
        Set<String> next = new LinkedHashSet<>();
        boolean all = false;
        if (payload != null && !payload.isBlank()) {
            try {
                JsonNode n = JSON.readTree(payload);
                all = n.path("all").asBoolean(false);
                String one = n.path("instrument").asText("");
                if ("*".equals(one)) {
                    all = true;
                } else if (one != null && !one.isBlank()) {
                    next.add(one.trim().toUpperCase(Locale.ROOT));
                }
                JsonNode arr = n.get("instruments");
                if (arr != null && arr.isArray()) {
                    for (JsonNode el : arr) {
                        String v = el.asText("");
                        if ("*".equals(v)) {
                            all = true;
                        } else if (v != null && !v.isBlank()) {
                            next.add(v.trim().toUpperCase(Locale.ROOT));
                        }
                    }
                }
            } catch (Exception ignored) {
                // keep previous
            }
        }
        c.all = all;
        c.instruments = List.copyOf(next);
    }

    static boolean sameTapeInstrument(String want, String printInst) {
        if (want == null || want.isBlank() || printInst == null || printInst.isBlank()) {
            return false;
        }
        String a = want.trim().toUpperCase(Locale.ROOT);
        String b = printInst.trim().toUpperCase(Locale.ROOT);
        if (a.equals(b) || "*".equals(a)) {
            return true;
        }
        String fa = TInvestBrokerMarketData.familyOf(a);
        String fb = TInvestBrokerMarketData.familyOf(b);
        return fa != null && fa.equals(fb);
    }

    static String toJson(TradePrint p) {
        String inst = p.instrumentId() == null ? "" : p.instrumentId();
        String side = p.side() == null ? "UNKNOWN" : p.side().name();
        return "{\"t\":\"trade\",\"instrument\":\"" + jsonEscape(inst)
                + "\",\"px\":" + p.price()
                + ",\"qty\":" + p.quantityLots()
                + ",\"side\":\"" + jsonEscape(side) + "\"}";
    }

    static String toBookJson(DomBook book) {
        String inst = book.instrumentId() == null ? "" : book.instrumentId();
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"t\":\"book\",\"instrument\":\"").append(jsonEscape(inst)).append("\",\"bids\":");
        appendLevels(sb, book.bids());
        sb.append(",\"asks\":");
        appendLevels(sb, book.asks());
        sb.append("}");
        return sb.toString();
    }

    private static void appendLevels(StringBuilder sb, List<DomBook.DomLevel> levels) {
        sb.append('[');
        if (levels != null) {
            int n = 0;
            for (DomBook.DomLevel l : levels) {
                if (l == null || !(l.price() > 0)) {
                    continue;
                }
                if (n > 0) {
                    sb.append(',');
                }
                sb.append("{\"p\":").append(l.price()).append(",\"q\":").append(l.quantityLots()).append('}');
                n++;
                if (n >= BOOK_LEVELS) {
                    break;
                }
            }
        }
        sb.append(']');
    }

    private static String keyInst(String inst) {
        return inst == null ? "" : inst.trim().toUpperCase(Locale.ROOT);
    }

    private static String jsonEscape(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void sendQuiet(WebSocketSession session, String json) {
        if (session == null || !session.isOpen()) {
            return;
        }
        synchronized (session) {
            try {
                session.sendMessage(new TextMessage(json));
            } catch (IOException ignored) {
                // drop — next print or reconnect
            }
        }
    }

    private record QueuedPrint(String sessionId, TradePrint print) {
    }

    static final class Client {
        final WebSocketSession session;
        volatile boolean all;
        volatile List<String> instruments = List.of();

        Client(WebSocketSession session) {
            this.session = session;
        }

        boolean wants(String printInst) {
            if (all) {
                return printInst != null && !printInst.isBlank();
            }
            for (String w : instruments) {
                if (sameTapeInstrument(w, printInst)) {
                    return true;
                }
            }
            return false;
        }
    }
}
