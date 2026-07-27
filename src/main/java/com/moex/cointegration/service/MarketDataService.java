package com.moex.cointegration.service;

import com.moex.cointegration.client.MoexIssClient;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.Candle;
import com.moex.cointegration.model.PricePoint;
import com.moex.cointegration.model.PriceSeries;
import com.moex.cointegration.storage.MarketDataStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Загрузка рыночных данных с MOEX и подготовка ценовых рядов для анализа.
 */
@Service
public class MarketDataService {

    private static final int MIN_CANDLES = 100;

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    private final MoexIssClient moexClient;
    private final MarketDataStorage storage;
    private final ImoexProperties properties;

    public MarketDataService(MoexIssClient moexClient, MarketDataStorage storage, ImoexProperties properties) {
        this.moexClient = moexClient;
        this.storage = storage;
        this.properties = properties;
    }

    /**
     * Скачивает дневные свечи IMOEX за {@code historyYears} лет и сохраняет локально.
     * Тикеры с менее чем 100 свечами пропускаются.
     *
     * @return список успешно загруженных тикеров
     */
    public List<String> refreshMarketData() throws IOException {
        LocalDate till = LocalDate.now();
        LocalDate from = till.minusYears(properties.historyYears());

        List<String> tickers = moexClient.fetchImoexTickers();
        log.info("Fetched {} IMOEX tickers", tickers.size());

        List<String> loaded = new ArrayList<>();
        for (String ticker : tickers) {
            try {
                List<Candle> candles = moexClient.fetchDailyCandles(ticker, from, till);
                if (candles.size() < MIN_CANDLES) {
                    log.warn("Skipping {}: only {} candles", ticker, candles.size());
                    continue;
                }
                candles.sort(Comparator.comparing(Candle::date));
                storage.saveCandles(ticker, candles);
                loaded.add(ticker);
                log.info("Saved {} candles for {}", candles.size(), ticker);
            } catch (Exception ex) {
                log.warn("Failed to load {}: {}", ticker, ex.getMessage());
            }
        }
        return loaded;
    }

    /**
     * Скачивает 1H свечи для списка тикеров (INTRADAY mode) → data/candles-1h/.
     */
    public List<String> refreshHourlyCandles(List<String> tickers, int lookbackDays) throws IOException {
        LocalDate till = LocalDate.now();
        LocalDate from = till.minusDays(Math.max(30, lookbackDays));
        return refreshHourlyCandles(tickers, from, till);
    }

    /**
     * Скачивает 1H свечи за произвольный период (для исторического replay).
     */
    public List<String> refreshHourlyCandles(List<String> tickers, LocalDate from, LocalDate till)
            throws IOException {
        int interval = 60;
        List<String> loaded = new ArrayList<>();
        for (String ticker : tickers) {
            try {
                List<Candle> candles = moexClient.fetchShareCandles(ticker, from, till, interval);
                if (candles.size() < 50) {
                    log.warn("Skipping hourly {}: only {} bars", ticker, candles.size());
                    continue;
                }
                candles.sort(Comparator.comparing(Candle::begin));
                storage.saveHourlyCandles(ticker, candles);
                loaded.add(ticker);
                log.info("Saved {} hourly bars for {} ({} — {})", candles.size(), ticker, from, till);
            } catch (Exception ex) {
                log.warn("Failed hourly {}: {}", ticker, ex.getMessage());
            }
        }
        log.info("Hourly candles saved for {} tickers ({} — {})", loaded.size(), from, till);
        return loaded;
    }

    /**
     * Загружает локальные свечи каждого тикера отдельно (без глобального пересечения дат).
     * Исключённые из индекса бумаги (POLY, QIWI и т.д.) больше не «ломают» общий календарь.
     */
    public Map<String, PriceSeries> loadAlignedPriceSeries() throws IOException {
        List<String> tickers = storage.listStoredTickers();
        Map<String, PriceSeries> seriesByTicker = new HashMap<>();

        for (String ticker : tickers) {
            List<Candle> candles = storage.loadCandles(ticker);
            if (candles.size() < MIN_CANDLES) {
                continue;
            }
            List<PricePoint> points = candles.stream()
                    .map(c -> new PricePoint(c.begin(), c.close()))
                    .toList();
            seriesByTicker.put(ticker, new PriceSeries(ticker, points));
        }

        log.info("Loaded {} tickers with at least {} candles each", seriesByTicker.size(), MIN_CANDLES);
        return seriesByTicker;
    }

    /**
     * Загружает локальные 1H свечи для INTRADAY-книги.
     */
    public Map<String, PriceSeries> loadAlignedHourlyPriceSeries() throws IOException {
        List<String> tickers = storage.listStoredHourlyTickers();
        Map<String, PriceSeries> seriesByTicker = new HashMap<>();
        int minBars = 50;
        for (String ticker : tickers) {
            List<Candle> candles = storage.loadHourlyCandles(ticker);
            if (candles.size() < minBars) {
                continue;
            }
            candles.sort(Comparator.comparing(Candle::begin));
            List<PricePoint> points = candles.stream()
                    .map(c -> new PricePoint(c.begin(), c.close()))
                    .toList();
            seriesByTicker.put(ticker, new PriceSeries(ticker, points));
        }
        log.info("Loaded {} hourly tickers with at least {} bars each", seriesByTicker.size(), minBars);
        return seriesByTicker;
    }
}
