package com.moex.trinity.marketdata;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fan-out of live tape prints and DOM snapshots to operator sockets. Does not place orders.
 */
public final class TapeTickBus {

    public interface Listener {
        void onTapePrint(TradePrint print);

        default void onBook(DomBook book) {
            /* optional */
        }
    }

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void publish(TradePrint print) {
        if (print == null || !(print.price() > 0)) {
            return;
        }
        for (Listener listener : listeners) {
            try {
                listener.onTapePrint(print);
            } catch (Exception ignored) {
                // one slow socket must not stall the gRPC stream
            }
        }
    }

    public void publishBook(DomBook book) {
        if (book == null || book.instrumentId() == null || book.instrumentId().isBlank()) {
            return;
        }
        for (Listener listener : listeners) {
            try {
                listener.onBook(book);
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    public int listenerCount() {
        return listeners.size();
    }
}
