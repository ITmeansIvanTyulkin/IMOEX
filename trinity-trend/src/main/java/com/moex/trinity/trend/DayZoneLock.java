package com.moex.trinity.trend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checklist: once TOP/BOT volume ranges are established for the session day,
 * they stay fixed until the next calendar day (MSK). Each side locks independently
 * on first valid shelf; never moves afterward the same day.
 * Optional file path persists across restarts.
 */
public final class DayZoneLock {

    private static final Pattern NUM = Pattern.compile(
            "\"(day|trendHigh|trendLow|topLow|topHigh|bottomLow|bottomHigh|topSource|bottomSource)\"\\s*:\\s*\"?([^\",}]+)\"?");

    public record Snapshot(
            LocalDate day,
            double trendHigh,
            double trendLow,
            MergedVolumeRange top,
            String topSource,
            MergedVolumeRange bottom,
            String bottomSource
    ) {
        public boolean hasTop() {
            return top != null && top.low() < top.high();
        }

        public boolean hasBottom() {
            return bottom != null && bottom.low() < bottom.high();
        }
    }

    private final AtomicReference<Snapshot> ref = new AtomicReference<>();
    private final Path persistFile;

    public DayZoneLock() {
        this(null);
    }

    public DayZoneLock(Path persistFile) {
        this.persistFile = persistFile;
        load();
    }

    public Snapshot get() {
        return ref.get();
    }

    public void clear() {
        ref.set(null);
        save();
    }

    /**
     * If live extreme breaks through a locked shelf by {@code breakPoints}, clear that side
     * so a fresh volume range can lock at the new HI/LO (PRIOR or same-day early lock).
     */
    public Snapshot clearBrokenShelves(double liveHigh, double liveLow, double breakPoints, double pointSize) {
        Snapshot cur = ref.get();
        if (cur == null) {
            return null;
        }
        double thr = Math.max(1, breakPoints) * (pointSize > 0 ? pointSize : 0.01);
        MergedVolumeRange top = cur.top();
        String ts = cur.topSource();
        double th = cur.trendHigh();
        MergedVolumeRange bottom = cur.bottom();
        String bs = cur.bottomSource();
        double tl = cur.trendLow();
        boolean changed = false;
        // Any locked TOP broken by new high → re-lock allowed (not only PRIOR)
        if (top != null && Double.isFinite(liveHigh) && liveHigh > top.high() + thr) {
            top = null;
            ts = null;
            th = liveHigh;
            changed = true;
        }
        if (bottom != null && Double.isFinite(liveLow) && liveLow < bottom.low() - thr) {
            bottom = null;
            bs = null;
            tl = liveLow;
            changed = true;
        }
        if (!changed) {
            return cur;
        }
        Snapshot next = new Snapshot(cur.day(), th, tl, top, ts, bottom, bs);
        ref.set(next);
        save();
        return next;
    }

    /** Force-clear both shelves (kick / new structure). Keeps day stamp. */
    public Snapshot forceClearShelves(double trendHigh, double trendLow) {
        Snapshot cur = ref.get();
        LocalDate day = cur != null ? cur.day() : null;
        Snapshot next = new Snapshot(day, trendHigh, trendLow, null, null, null, null);
        ref.set(next);
        save();
        return next;
    }

    /**
     * Roll to {@code day} if needed; lock each missing side from candidates (first wins).
     */
    public Snapshot absorb(
            LocalDate day,
            double trendHigh,
            double trendLow,
            MergedVolumeRange topCand,
            String topSrc,
            MergedVolumeRange bottomCand,
            String bottomSrc
    ) {
        Snapshot cur = ref.get();
        if (cur == null || cur.day() == null || !cur.day().equals(day)) {
            cur = new Snapshot(day, trendHigh, trendLow, null, null, null, null);
        }
        MergedVolumeRange top = cur.top();
        String ts = cur.topSource();
        double th = cur.trendHigh();
        if (top == null && topCand != null && topCand.low() < topCand.high()) {
            top = stripBands(topCand);
            ts = topSrc;
            if (Double.isFinite(trendHigh)) {
                th = trendHigh;
            }
        }
        MergedVolumeRange bottom = cur.bottom();
        String bs = cur.bottomSource();
        double tl = cur.trendLow();
        if (bottom == null && bottomCand != null && bottomCand.low() < bottomCand.high()) {
            bottom = stripBands(bottomCand);
            bs = bottomSrc;
            if (Double.isFinite(trendLow)) {
                tl = trendLow;
            }
        }
        Snapshot next = new Snapshot(day, th, tl, top, ts, bottom, bs);
        Snapshot prev = ref.get();
        ref.set(next);
        if (prev == null || !sameLock(prev, next)) {
            save();
        }
        return next;
    }

    private static MergedVolumeRange stripBands(MergedVolumeRange r) {
        return new MergedVolumeRange(r.low(), r.high(), r.totalVolume(), List.of(), r.validForEntry(), r.invalidReason());
    }

    private static boolean sameLock(Snapshot a, Snapshot b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return eq(a.day(), b.day())
                && eqD(a.trendHigh(), b.trendHigh())
                && eqD(a.trendLow(), b.trendLow())
                && eqRange(a.top(), b.top())
                && eqRange(a.bottom(), b.bottom());
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private static boolean eqD(double a, double b) {
        if (!Double.isFinite(a) && !Double.isFinite(b)) {
            return true;
        }
        return Double.isFinite(a) && Double.isFinite(b) && Math.abs(a - b) < 1e-9;
    }

    private static boolean eqRange(MergedVolumeRange a, MergedVolumeRange b) {
        if (a == null || b == null) {
            return a == b;
        }
        return eqD(a.low(), b.low()) && eqD(a.high(), b.high());
    }

    private void load() {
        if (persistFile == null || !Files.isRegularFile(persistFile)) {
            return;
        }
        try {
            String raw = Files.readString(persistFile, StandardCharsets.UTF_8);
            Snapshot s = parse(raw);
            if (s != null && s.day() != null) {
                ref.set(s);
            }
        } catch (Exception ignored) {
            // keep empty lock
        }
    }

    private void save() {
        if (persistFile == null) {
            return;
        }
        Snapshot s = ref.get();
        try {
            Files.createDirectories(persistFile.getParent() == null
                    ? Path.of(".")
                    : persistFile.getParent());
            if (s == null) {
                Files.writeString(persistFile, "{}\n", StandardCharsets.UTF_8);
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"day\": \"").append(s.day()).append("\",\n");
            sb.append("  \"trendHigh\": ").append(fmt(s.trendHigh())).append(",\n");
            sb.append("  \"trendLow\": ").append(fmt(s.trendLow())).append(",\n");
            if (s.top() != null) {
                sb.append("  \"topLow\": ").append(fmt(s.top().low())).append(",\n");
                sb.append("  \"topHigh\": ").append(fmt(s.top().high())).append(",\n");
                sb.append("  \"topSource\": \"").append(esc(s.topSource())).append("\",\n");
            }
            if (s.bottom() != null) {
                sb.append("  \"bottomLow\": ").append(fmt(s.bottom().low())).append(",\n");
                sb.append("  \"bottomHigh\": ").append(fmt(s.bottom().high())).append(",\n");
                sb.append("  \"bottomSource\": \"").append(esc(s.bottomSource())).append("\"\n");
            } else {
                sb.append("  \"bottomSource\": null\n");
            }
            sb.append("}\n");
            Files.writeString(persistFile, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static Snapshot parse(String raw) {
        if (raw == null || raw.isBlank() || raw.trim().equals("{}")) {
            return null;
        }
        String day = null;
        double th = Double.NaN, tl = Double.NaN, tLo = Double.NaN, tHi = Double.NaN, bLo = Double.NaN, bHi = Double.NaN;
        String ts = null, bs = null;
        Matcher m = NUM.matcher(raw);
        while (m.find()) {
            String k = m.group(1);
            String v = m.group(2).trim();
            switch (k) {
                case "day" -> day = v;
                case "trendHigh" -> th = parseD(v);
                case "trendLow" -> tl = parseD(v);
                case "topLow" -> tLo = parseD(v);
                case "topHigh" -> tHi = parseD(v);
                case "bottomLow" -> bLo = parseD(v);
                case "bottomHigh" -> bHi = parseD(v);
                case "topSource" -> ts = "null".equals(v) ? null : v;
                case "bottomSource" -> bs = "null".equals(v) ? null : v;
                default -> {
                }
            }
        }
        if (day == null) {
            return null;
        }
        MergedVolumeRange top = (Double.isFinite(tLo) && Double.isFinite(tHi) && tLo < tHi)
                ? new MergedVolumeRange(tLo, tHi, 0, List.of(), true, null) : null;
        MergedVolumeRange bottom = (Double.isFinite(bLo) && Double.isFinite(bHi) && bLo < bHi)
                ? new MergedVolumeRange(bLo, bHi, 0, List.of(), true, null) : null;
        return new Snapshot(LocalDate.parse(day), th, tl, top, ts, bottom, bs);
    }

    private static double parseD(String v) {
        try {
            return Double.parseDouble(v);
        } catch (Exception ex) {
            return Double.NaN;
        }
    }

    private static String fmt(double v) {
        if (!Double.isFinite(v)) {
            return "null";
        }
        return String.format(Locale.ROOT, "%.6f", v);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\"", "'");
    }
}
