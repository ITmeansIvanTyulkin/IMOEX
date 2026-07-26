package com.moex.cointegration.service;

import com.moex.cointegration.client.MoexIssClient;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.config.RegimeProperties;
import com.moex.cointegration.model.Candle;
import com.moex.cointegration.model.MarketRegimeSnapshot;
import com.moex.cointegration.quant.AdxCalculator;
import com.moex.cointegration.storage.MarketDataStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ADX по индексу IMOEX → боковик / нейтраль / тренд.
 */
@Service
public class MarketRegimeService {

    private static final Logger log = LoggerFactory.getLogger(MarketRegimeService.class);

    private final RegimeProperties regimeProperties;
    private final ImoexProperties imoexProperties;
    private final MoexIssClient issClient;
    private final MarketDataStorage storage;
    private final AtomicReference<MarketRegimeSnapshot> last = new AtomicReference<>(MarketRegimeSnapshot.unknown());

    public MarketRegimeService(
            RegimeProperties regimeProperties,
            ImoexProperties imoexProperties,
            MoexIssClient issClient,
            MarketDataStorage storage
    ) {
        this.regimeProperties = regimeProperties;
        this.imoexProperties = imoexProperties;
        this.issClient = issClient;
        this.storage = storage;
    }

    public MarketRegimeSnapshot current() {
        return last.get();
    }

    /** Обновить ADX (из кэша индекса или с ISS). */
    public MarketRegimeSnapshot refresh() {
        if (!regimeProperties.enabledFlag()) {
            MarketRegimeSnapshot off = new MarketRegimeSnapshot(Double.NaN, "OFF", false, false,
                    "Режимный фильтр выключен");
            last.set(off);
            return off;
        }
        try {
            List<Candle> candles = loadIndexCandles();
            if (candles.size() < regimeProperties.adxPeriod() * 2 + 5) {
                MarketRegimeSnapshot u = MarketRegimeSnapshot.unknown();
                last.set(u);
                return u;
            }
            double[] high = candles.stream().mapToDouble(Candle::high).toArray();
            double[] low = candles.stream().mapToDouble(Candle::low).toArray();
            double[] close = candles.stream().mapToDouble(Candle::close).toArray();
            double adx = AdxCalculator.lastAdx(high, low, close, regimeProperties.adxPeriod());
            MarketRegimeSnapshot snap = MarketRegimeSnapshot.of(
                    adx, regimeProperties.adxReduce(), regimeProperties.adxBlock());
            last.set(snap);
            log.info("Market regime: {} ({})", snap.label(), snap.detail());
            return snap;
        } catch (Exception ex) {
            log.warn("Regime refresh failed: {}", ex.getMessage());
            MarketRegimeSnapshot u = MarketRegimeSnapshot.unknown();
            last.set(u);
            return u;
        }
    }

    private List<Candle> loadIndexCandles() throws Exception {
        String secid = imoexProperties.index();
        String cacheKey = "_INDEX_" + secid;
        List<Candle> cached = storage.loadCandles(cacheKey);
        LocalDate till = LocalDate.now();
        LocalDate from = till.minusYears(Math.max(1, imoexProperties.historyYears()));
        if (cached.size() >= 80) {
            LocalDate last = cached.get(cached.size() - 1).date();
            if (!last.isBefore(till.minusDays(5))) {
                return cached;
            }
        }
        List<Candle> fresh = issClient.fetchIndexCandles(
                secid, regimeProperties.indexBoard(), from, till, 24);
        if (fresh.size() >= 50) {
            storage.saveCandles(cacheKey, fresh);
            return fresh;
        }
        return cached.isEmpty() ? fresh : cached;
    }
}
