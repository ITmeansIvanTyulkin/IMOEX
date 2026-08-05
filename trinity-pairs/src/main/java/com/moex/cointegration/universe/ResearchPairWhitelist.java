package com.moex.cointegration.universe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Research whitelist пар. Не для live — только campaign / paper research.
 */
public final class ResearchPairWhitelist {

    public record Pair(String tickerY, String tickerX) {
        public Pair {
            Objects.requireNonNull(tickerY, "tickerY");
            Objects.requireNonNull(tickerX, "tickerX");
            tickerY = tickerY.toUpperCase(Locale.ROOT);
            tickerX = tickerX.toUpperCase(Locale.ROOT);
        }

        public boolean matches(String y, String x) {
            if (y == null || x == null) {
                return false;
            }
            String a = y.toUpperCase(Locale.ROOT);
            String b = x.toUpperCase(Locale.ROOT);
            return (tickerY.equals(a) && tickerX.equals(b))
                    || (tickerY.equals(b) && tickerX.equals(a));
        }

        @Override
        public String toString() {
            return tickerY + "/" + tickerX;
        }
    }

    private static final List<String> METALS_TIER1 =
            List.of("CHMF", "NLMK", "MAGN", "GMKN", "ALRS", "PLZL", "RUAL");
    private static final List<String> OIL_TIER1 =
            List.of("LKOH", "ROSN", "NVTK", "GAZP", "SNGS", "TATN");

    /** Liquid same-sector металлы tier-1 (~21 пара). Нефть — roadmap (фьючерсы/опционы). */
    public static final List<Pair> METALS_ONLY = List.copyOf(combos(METALS_TIER1));

    /**
     * Liquid same-sector metals + oil (~36). Research/legacy; DAILY paper — {@link #METALS_ONLY}.
     */
    public static final List<Pair> METALS_OIL = buildSectorCombos(METALS_TIER1, OIL_TIER1);

    /** DAILY whitelist: только металлы (без нефти). */
    public static final List<Pair> DAILY_METALS = METALS_ONLY;

    /** @deprecated use {@link #DAILY_METALS} */
    public static final List<Pair> DAILY_METALS_OIL = METALS_OIL;

    /** INTRADAY research (legacy full set). */
    public static final List<Pair> INTRADAY_METALS_OIL = METALS_OIL;

    /** DAILY: исходные soft-winners (узкий набор). */
    public static final List<Pair> DAILY_WINNERS = List.of(
            new Pair("CHMF", "MAGN"),
            new Pair("NVTK", "RNFT"),
            new Pair("RENI", "SVCB")
    );

    /** Узкий core INTRADAY (legacy). */
    public static final List<Pair> INTRADAY_LIQUID = List.of(
            new Pair("CHMF", "MAGN"),
            new Pair("CHMF", "NLMK"),
            new Pair("NVTK", "SNGS")
    );

    private ResearchPairWhitelist() {
    }

    private static List<Pair> buildSectorCombos(List<String> metals, List<String> oil) {
        List<Pair> out = new ArrayList<>();
        out.addAll(combos(metals));
        out.addAll(combos(oil));
        return List.copyOf(out);
    }

    private static List<Pair> combos(List<String> tickers) {
        List<Pair> out = new ArrayList<>();
        for (int i = 0; i < tickers.size(); i++) {
            for (int j = i + 1; j < tickers.size(); j++) {
                out.add(new Pair(tickers.get(i), tickers.get(j)));
            }
        }
        return out;
    }

    public static List<String> tickersOf(List<Pair> pairs) {
        return pairs.stream()
                .flatMap(p -> java.util.stream.Stream.of(p.tickerY(), p.tickerX()))
                .distinct()
                .sorted()
                .toList();
    }
}
