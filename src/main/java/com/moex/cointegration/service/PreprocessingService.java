package com.moex.cointegration.service;

import com.moex.cointegration.model.AdfResult;
import com.moex.cointegration.model.AlignedPairData;
import com.moex.cointegration.model.PricePoint;
import com.moex.cointegration.model.PriceSeries;
import com.moex.cointegration.quant.AdfTest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Предобработка ценовых рядов: LOCF, логарифмирование, диагностика стационарности.
 */
@Service
public class PreprocessingService {

    private static final int MIN_PAIR_DAYS = 100;

    /**
     * Применяет LOCF ко всем тикерам в наборе рядов.
     */
    public Map<String, PriceSeries> preprocess(Map<String, PriceSeries> input) {
        Map<String, PriceSeries> output = new HashMap<>();
        for (Map.Entry<String, PriceSeries> entry : input.entrySet()) {
            output.put(entry.getKey(), applyLocf(entry.getValue()));
        }
        return output;
    }

    /**
     * Запускает ADF-тест на уровнях цен (с трендом) для каждого тикера.
     * Нестационарность уровней цен ожидаема и используется только для логирования.
     */
    public Map<String, AdfResult> checkPriceStationarity(Map<String, PriceSeries> seriesMap) {
        Map<String, AdfResult> results = new HashMap<>();
        for (Map.Entry<String, PriceSeries> entry : seriesMap.entrySet()) {
            double[] prices = entry.getValue().points().stream().mapToDouble(PricePoint::close).toArray();
            AdfResult adf = AdfTest.test(prices, 1, true);
            results.put(entry.getKey(), adf);
        }
        return results;
    }

    /**
     * Выравнивает два ряда по пересечению торговых дат и возвращает log-цены для коинтеграции.
     * Если общих дней меньше {@link #MIN_PAIR_DAYS}, пара пропускается.
     */
    public Optional<AlignedPairData> alignPair(PriceSeries seriesY, PriceSeries seriesX) {
        Map<LocalDate, Double> yByDate = toDateMap(seriesY);
        Map<LocalDate, Double> xByDate = toDateMap(seriesX);

        Set<LocalDate> commonDates = new HashSet<>(yByDate.keySet());
        commonDates.retainAll(xByDate.keySet());

        List<LocalDate> sortedDates = commonDates.stream().sorted().toList();
        if (sortedDates.size() < MIN_PAIR_DAYS) {
            return Optional.empty();
        }

        double[] logY = new double[sortedDates.size()];
        double[] logX = new double[sortedDates.size()];
        LocalDate[] dates = new LocalDate[sortedDates.size()];

        for (int i = 0; i < sortedDates.size(); i++) {
            LocalDate date = sortedDates.get(i);
            dates[i] = date;
            logY[i] = Math.log(yByDate.get(date));
            logX[i] = Math.log(xByDate.get(date));
        }

        return Optional.of(new AlignedPairData(logY, logX, dates));
    }

    private Map<LocalDate, Double> toDateMap(PriceSeries series) {
        Map<LocalDate, Double> byDate = new HashMap<>();
        for (PricePoint point : series.points()) {
            byDate.put(point.date(), point.close());
        }
        return byDate;
    }

    /**
     * Заполняет пропуски методом LOCF: NaN и неположительные цены заменяются последним валидным значением.
     */
    public PriceSeries applyLocf(PriceSeries series) {
        List<PricePoint> points = new ArrayList<>(series.points());
        if (points.isEmpty()) {
            return series;
        }

        double lastValid = points.get(0).close();
        List<PricePoint> filled = new ArrayList<>(points.size());

        for (PricePoint point : points) {
            double value = point.close();
            if (Double.isNaN(value) || value <= 0) {
                value = lastValid;
            } else {
                lastValid = value;
            }
            filled.add(new PricePoint(point.date(), value));
        }

        return new PriceSeries(series.ticker(), filled);
    }

    /**
     * Возвращает натуральный логарифм цен закрытия — вход для коинтеграционного анализа.
     */
    public double[] logPrices(PriceSeries series) {
        return series.points().stream()
                .mapToDouble(p -> Math.log(p.close()))
                .toArray();
    }

    /**
     * Извлекает массив дат, синхронизированный с ценовым рядом (для графиков спреда).
     */
    public LocalDateArray dates(PriceSeries series) {
        return new LocalDateArray(series.points().stream().map(PricePoint::date).toArray(LocalDate[]::new));
    }

    /** Обёртка над массивом дат для передачи в аналитику спреда. */
    public record LocalDateArray(LocalDate[] values) {
    }
}
