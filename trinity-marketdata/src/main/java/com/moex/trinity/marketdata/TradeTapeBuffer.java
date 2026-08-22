package com.moex.trinity.marketdata;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Bounded tape buffer shared by live stream and historical clocked feeds.
 */
public final class TradeTapeBuffer {

    private final int capacity;
    private final ArrayList<TradePrint> buf = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public TradeTapeBuffer(int capacity) {
        this.capacity = Math.max(1_000, capacity);
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            buf.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void add(TradePrint print) {
        if (print == null) {
            return;
        }
        lock.writeLock().lock();
        try {
            buf.add(print);
            int overflow = buf.size() - capacity;
            if (overflow > 0) {
                buf.subList(0, overflow).clear();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void addAll(List<TradePrint> prints) {
        if (prints == null || prints.isEmpty()) {
            return;
        }
        lock.writeLock().lock();
        try {
            buf.addAll(prints);
            int overflow = buf.size() - capacity;
            if (overflow > 0) {
                buf.subList(0, overflow).clear();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return buf.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<TradePrint> snapshot() {
        lock.readLock().lock();
        try {
            return List.copyOf(buf);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Prints in {@code [from, to)} for one instrument — no full-buffer copy.
     */
    public List<TradePrint> snapshotWindow(String instrumentId, Instant from, Instant to) {
        lock.readLock().lock();
        try {
            if (buf.isEmpty()) {
                return List.of();
            }
            String u = instrumentId == null ? "" : instrumentId.trim().toUpperCase();
            boolean brFamily = "BR".equals(u);
            List<TradePrint> out = new ArrayList<>();
            for (TradePrint p : buf) {
                if (p == null || p.time() == null) {
                    continue;
                }
                if (from != null && p.time().isBefore(from)) {
                    continue;
                }
                if (to != null && !p.time().isBefore(to)) {
                    continue;
                }
                if (!u.isEmpty()) {
                    String id = p.instrumentId() == null ? "" : p.instrumentId().toUpperCase();
                    if (!id.equals(u) && !(brFamily && id.startsWith("BR"))) {
                        continue;
                    }
                }
                out.add(p);
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Prints with {@code time <= asOf} (inclusive), for look-ahead-safe replay. */
    public List<TradePrint> snapshotUntil(Instant asOf) {
        if (asOf == null) {
            return snapshot();
        }
        lock.readLock().lock();
        try {
            List<TradePrint> out = new ArrayList<>();
            for (TradePrint p : buf) {
                if (p.time() != null && !p.time().isAfter(asOf)) {
                    out.add(p);
                }
            }
            return List.copyOf(out);
        } finally {
            lock.readLock().unlock();
        }
    }
}
