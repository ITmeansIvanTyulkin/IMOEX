package com.moex.cointegration.service;

import com.moex.cointegration.model.PricePoint;
import com.moex.cointegration.model.PriceSeries;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты предобработки ценовых рядов перед эконометрическим анализом.
 */
class PreprocessingServiceTest {

    /**
     * Проверяет заполнение пропусков методом LOCF (last observation carried forward):
     * NaN и нулевые/отрицательные цены заменяются последним валидным значением.
     */
    @Test
    void locfFillsMissingValues() {
        PreprocessingService service = new PreprocessingService();
        PriceSeries input = new PriceSeries("TEST", List.of(
                new PricePoint(LocalDate.of(2024, 1, 1), 100.0),
                new PricePoint(LocalDate.of(2024, 1, 2), Double.NaN),
                new PricePoint(LocalDate.of(2024, 1, 3), 0.0),
                new PricePoint(LocalDate.of(2024, 1, 4), 105.0)
        ));

        PriceSeries output = service.preprocess(java.util.Map.of("TEST", input)).get("TEST");
        assertEquals(100.0, output.points().get(1).close());
        assertEquals(100.0, output.points().get(2).close());
        assertEquals(105.0, output.points().get(3).close());
    }

    @Test
    void alignPairKeepsDistinctHourlyTimestamps() {
        PreprocessingService service = new PreprocessingService();
        List<PricePoint> yPts = new java.util.ArrayList<>();
        List<PricePoint> xPts = new java.util.ArrayList<>();
        LocalDateTime t = LocalDateTime.of(2024, 1, 2, 10, 0);
        for (int i = 0; i < 120; i++) {
            yPts.add(new PricePoint(t, 100 + i * 0.01));
            xPts.add(new PricePoint(t, 50 + i * 0.005));
            t = t.plusHours(1);
            if (t.getHour() >= 18) {
                t = t.toLocalDate().plusDays(1).atTime(10, 0);
            }
        }
        var aligned = service.alignPair(new PriceSeries("Y", yPts), new PriceSeries("X", xPts));
        assertTrue(aligned.isPresent());
        LocalDateTime[] begins = aligned.get().begins();
        assertTrue(begins.length >= 100);
        long distinctDays = java.util.Arrays.stream(begins).map(LocalDateTime::toLocalDate).distinct().count();
        assertTrue(begins.length > distinctDays);
    }
}
