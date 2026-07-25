package com.moex.cointegration.universe;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Секторальная карта IMOEX-тикеров для сужения пар: только same-business / same-sector.
 * Тикеры вне карты в режиме {@code same-sector-only} в пары не берутся.
 */
public final class SectorCatalog {

    public enum Sector {
        BANKS,
        OIL_GAS,
        METALS_MINING,
        UTILITIES,
        RETAIL,
        TELECOM,
        TRANSPORT,
        TECH_IT,
        CHEM_FERT,
        REAL_ESTATE,
        INDUSTRIALS
    }

    private static final Map<String, Sector> BY_TICKER = new HashMap<>();

    static {
        // Банки / финансы
        put(Sector.BANKS, "SBER", "VTBR", "TCSG", "T", "CBOM", "BSPB", "SVCB", "SFIN", "RENI", "DOMRF", "MOEX");
        // Нефть / газ
        put(Sector.OIL_GAS, "GAZP", "LKOH", "ROSN", "NVTK", "SNGS", "TATN", "SIBN", "BANE", "RNFT", "TRNFP", "SNGSP");
        // Металлы / добыча
        put(Sector.METALS_MINING,
                "NLMK", "MAGN", "CHMF", "GMKN", "ALRS", "PLZL", "RUAL", "MTLR", "VSMO", "SELG",
                "UGLD", "TRMK", "RASP", "POLY", "POGR");
        // Энергетика / сети
        put(Sector.UTILITIES, "FEES", "HYDR", "IRAO", "MSNG", "OGKB", "UPRO", "ENPG", "IRGZ", "MSRS", "RSTI", "SAGO");
        // Ритейл / потреб
        put(Sector.RETAIL, "MGNT", "FIVE", "X5", "LENT", "MVID", "LNTA", "FIXP", "DSKY");
        // Телеком
        put(Sector.TELECOM, "MTSS", "RTKM");
        // Транспорт / логистика
        put(Sector.TRANSPORT, "AFLT", "FLOT", "NMTP", "GLTR", "UWGN", "FLOT", "LEAS", "KMAZ");
        // IT / tech / media
        put(Sector.TECH_IT, "YDEX", "YNDX", "VKCO", "OZON", "POSI", "ASTR", "HEAD", "HHRU", "MAIL", "QIWI", "AFKS");
        // Химия / удобрения / агро
        put(Sector.CHEM_FERT, "PHOR", "AKRN", "NKNC", "SGZH", "AGRO", "GCHE", "RAGR");
        // Недвижимость / девелопмент
        put(Sector.REAL_ESTATE, "PIKK", "LSRG", "SMLT", "MSTT");
        // Промышленность прочее
        put(Sector.INDUSTRIALS, "IRKT", "SVAV", "CNRU", "NMTP");
    }

    private SectorCatalog() {
    }

    private static void put(Sector sector, String... tickers) {
        for (String t : tickers) {
            BY_TICKER.put(t.toUpperCase(Locale.ROOT), sector);
        }
    }

    public static Optional<Sector> sectorOf(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_TICKER.get(ticker.toUpperCase(Locale.ROOT)));
    }

    /** true, если оба тикера в одном секторе и сектор известен. */
    public static boolean sameSector(String tickerY, String tickerX) {
        Optional<Sector> a = sectorOf(tickerY);
        Optional<Sector> b = sectorOf(tickerX);
        return a.isPresent() && b.isPresent() && a.get() == b.get();
    }

    public static Set<String> knownTickers() {
        return Collections.unmodifiableSet(BY_TICKER.keySet());
    }

    public static int size() {
        return BY_TICKER.size();
    }
}
