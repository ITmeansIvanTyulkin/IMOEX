package com.moex.cointegration.service;

import com.moex.cointegration.model.AdfResult;
import com.moex.cointegration.model.AlignedPairData;
import com.moex.cointegration.model.PricePoint;
import com.moex.cointegration.model.PriceSeries;
import com.moex.cointegration.quant.AdfTest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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

    private static final int MIN_PAIR_BARS = 100;

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
     * Выравнивает два ряда по пересечению меток времени и возвращает log-цены для коинтеграции.
     * Если общих баров меньше {@link #MIN_PAIR_BARS}, пара пропускается.
     */
    public Optional<AlignedPairData> alignPair(PriceSeries seriesY, PriceSeries seriesX) {
        Map<LocalDateTime, Double> yByTs = toTsMap(seriesY);
        Map<LocalDateTime, Double> xByTs = toTsMap(seriesX);

        Set<LocalDateTime> common = new HashSet<>(yByTs.keySet());
        common.retainAll(xByTs.keySet());

        List<LocalDateTime> sorted = common.stream().sorted().toList();
        if (sorted.size() < MIN_PAIR_BARS) {
            return Optional.empty();
        }

        double[] logY = new double[sorted.size()];
        double[] logX = new double[sorted.size()];
        LocalDateTime[] begins = new LocalDateTime[sorted.size()];

        for (int i = 0; i < sorted.size(); i++) {
            LocalDateTime ts = sorted.get(i);
            begins[i] = ts;
            logY[i] = Math.log(yByTs.get(ts));
            logX[i] = Math.log(xByTs.get(ts));
        }

        return Optional.of(new AlignedPairData(logY, logX, begins));
    }

    private Map<LocalDateTime, Double> toTsMap(PriceSeries series) {
        Map<LocalDateTime, Double> byTs = new HashMap<>();
        for (PricePoint point : series.points()) {
            byTs.put(point.begin(), point.close());
        }
        return byTs;
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
            filled.add(new PricePoint(point.begin(), value));
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
}
