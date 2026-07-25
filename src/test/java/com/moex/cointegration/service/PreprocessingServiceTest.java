package com.moex.cointegration.service;

import com.moex.cointegration.model.PricePoint;
import com.moex.cointegration.model.PriceSeries;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
