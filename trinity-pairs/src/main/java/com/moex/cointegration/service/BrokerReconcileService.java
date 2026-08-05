package com.moex.cointegration.service;

import com.moex.cointegration.model.BookKind;
import com.moex.cointegration.model.BrokerAccountSnapshot;
import com.moex.cointegration.model.BrokerOrderSnapshot;
import com.moex.cointegration.model.BrokerPositionSnapshot;
import com.moex.cointegration.model.BrokerReconcileItem;
import com.moex.cointegration.model.BrokerReconcileReport;
import com.moex.cointegration.model.PaperTradeEntry;
import com.moex.cointegration.model.TradingSignal;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Сверка ожидаемых DAILY позиций по paper с реальными broker positions и active orders.
 */
@Service
public class BrokerReconcileService {

    private final BrokerClient brokerClient;
    private final PaperTradingService paperTradingService;

    public BrokerReconcileService(BrokerClient brokerClient, PaperTradingService paperTradingService) {
        this.brokerClient = brokerClient;
        this.paperTradingService = paperTradingService;
    }

    public BrokerReconcileReport reconcileDaily() {
        List<PaperTradeEntry> opens = paperTradingService.getOpenTrades(BookKind.DAILY);
        BrokerAccountSnapshot snapshot = brokerClient.snapshot();
        Map<String, Double> expected = expectedPaperQty(opens);
        Map<String, Long> actual = new HashMap<>();
        for (BrokerPositionSnapshot p : snapshot.positions()) {
            actual.put(p.ticker().toUpperCase(Locale.ROOT), p.balance());
        }
        Map<String, List<BrokerOrderSnapshot>> activeOrders = new HashMap<>();
        for (BrokerOrderSnapshot order : snapshot.activeOrders()) {
            activeOrders.computeIfAbsent(order.ticker().toUpperCase(Locale.ROOT), key -> new ArrayList<>()).add(order);
        }

        List<BrokerReconcileItem> items = new ArrayList<>();
        int matched = 0;
        int mismatched = 0;
        for (Map.Entry<String, Double> e : expected.entrySet()) {
            String ticker = e.getKey();
            double want = e.getValue();
            long got = actual.getOrDefault(ticker, 0L);
            String status;
            String note;
            if (Math.abs(want) < 1e-9 && got == 0L) {
                status = "OK";
                note = "flat on both sides";
                matched++;
            } else if (got == 0L) {
                status = "MISMATCH";
                note = "paper expects position, broker is flat";
                mismatched++;
            } else if (Math.signum(want) != Math.signum(got)) {
                status = "MISMATCH";
                note = "side mismatch";
                mismatched++;
            } else {
                double ratio = Math.abs(Math.abs(got) - Math.abs(want)) / Math.max(1.0, Math.abs(want));
                if (ratio <= 0.35) {
                    status = "OK";
                    note = "same side, size within tolerance" + orderNote(activeOrders.get(ticker), true);
                    matched++;
                } else {
                    status = "MISMATCH";
                    note = "same side, but size deviates >35%" + orderNote(activeOrders.get(ticker), false);
                    mismatched++;
                }
            }
            items.add(new BrokerReconcileItem(ticker, round(want), got, status, note));
            activeOrders.remove(ticker);
        }

        for (Map.Entry<String, Long> e : actual.entrySet()) {
            if (expected.containsKey(e.getKey())) {
                continue;
            }
            items.add(new BrokerReconcileItem(e.getKey(), 0.0, e.getValue(), "EXTRA", "broker has position not present in paper"));
            mismatched++;
        }
        for (Map.Entry<String, List<BrokerOrderSnapshot>> e : activeOrders.entrySet()) {
            String note = "broker has active orders not backed by open paper pair: " + summarizeOrders(e.getValue());
            items.add(new BrokerReconcileItem(e.getKey(), 0.0, 0L, "ORDER_ONLY", note));
            mismatched++;
        }

        String summary = snapshot.available()
                ? String.format(Locale.ROOT, "paper pairs=%d, broker positions=%d, active orders=%d, ok=%d, mismatches=%d",
                opens.size(), snapshot.positions().size(), snapshot.activeOrders().size(), matched, mismatched)
                : snapshot.summary();

        return new BrokerReconcileReport(
                LocalDateTime.now(),
                opens.size(),
                snapshot.positions().size(),
                snapshot.activeOrders().size(),
                matched,
                mismatched,
                items,
                summary
        );
    }

    private Map<String, Double> expectedPaperQty(List<PaperTradeEntry> opens) {
        Map<String, Double> out = new HashMap<>();
        for (PaperTradeEntry e : opens) {
            double qtyY = e.qtyY() == null ? 0.0 : e.qtyY() * e.remainingFracOrOne();
            double qtyX = e.qtyX() == null ? 0.0 : e.qtyX() * e.remainingFracOrOne();
            if (e.signal() == TradingSignal.LONG_SPREAD) {
                out.merge(e.tickerY().toUpperCase(Locale.ROOT), qtyY, Double::sum);
                out.merge(e.tickerX().toUpperCase(Locale.ROOT), -qtyX, Double::sum);
            } else if (e.signal() == TradingSignal.SHORT_SPREAD) {
                out.merge(e.tickerY().toUpperCase(Locale.ROOT), -qtyY, Double::sum);
                out.merge(e.tickerX().toUpperCase(Locale.ROOT), qtyX, Double::sum);
            }
        }
        return out;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String orderNote(List<BrokerOrderSnapshot> orders, boolean okPosition) {
        if (orders == null || orders.isEmpty()) {
            return okPosition ? "" : "; no active orders";
        }
        return "; active orders: " + summarizeOrders(orders);
    }

    private static String summarizeOrders(List<BrokerOrderSnapshot> orders) {
        return orders.stream()
                .map(o -> o.side() + " " + o.type() + " req=" + o.requestedLots() + " exec=" + o.executedLots())
                .reduce((a, b) -> a + " | " + b)
                .orElse("none");
    }
}
