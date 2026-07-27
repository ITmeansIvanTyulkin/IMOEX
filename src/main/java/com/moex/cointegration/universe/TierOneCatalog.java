package com.moex.cointegration.universe;

import java.util.Locale;
import java.util.Set;

/**
 * Первый эшелон MOEX (TQBR): самые ликвидные голубые фишки для INTRADAY-книги.
 * Список статический — обновлять при существенных изменениях индекса / ликвидности.
 */
public final class TierOneCatalog {

    private static final Set<String> TIER_ONE = Set.of(
            "SBER", "LKOH", "GAZP", "GMKN", "NVTK", "ROSN", "TATN", "MGNT",
            "PLZL", "ALRS", "MOEX", "VTBR", "SNGS", "MTSS", "YDEX", "T",
            "CHMF", "NLMK", "MAGN", "PHOR", "IRAO", "FEES", "HYDR", "X5",
            "AFKS", "RUAL", "OZON", "VKCO", "AFLT", "PIKK"
    );

    private TierOneCatalog() {
    }

    public static boolean isTierOne(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return false;
        }
        return TIER_ONE.contains(ticker.toUpperCase(Locale.ROOT));
    }

    public static boolean pairTierOne(String tickerY, String tickerX) {
        return isTierOne(tickerY) && isTierOne(tickerX);
    }

    public static Set<String> tickers() {
        return TIER_ONE;
    }

    public static int size() {
        return TIER_ONE.size();
    }
}
