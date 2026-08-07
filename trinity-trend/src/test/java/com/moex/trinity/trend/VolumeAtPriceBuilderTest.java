package com.moex.trinity.trend;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VolumeAtPriceBuilderTest {

    @Test
    void mergesNaturalWidthWithoutPad() {
        TrendInstrumentSpec br = TrendInstrumentSpec.brDefaults();
        VolumeAtPriceBuilder vap = new VolumeAtPriceBuilder(br, false, 1);
        List<TrendBar> window = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            window.add(bar(86.40, 86.55, 86.48, 5000 + i * 100));
        }
        MergedVolumeRange range = vap.mergeFromBars(window);
        assertTrue(range.validForEntry(), range.invalidReason());
        double pts = range.widthPoints(br.pointSize());
        assertTrue(pts >= 15 && pts <= 20, "width pts=" + pts);
    }

    @Test
    void rejectsNarrowWithoutPad() {
        VolumeAtPriceBuilder vap = new VolumeAtPriceBuilder(TrendInstrumentSpec.brDefaults(), false, 1);
        List<TrendBar> window = List.of(bar(86.40, 86.45, 86.42, 3000));
        MergedVolumeRange range = vap.mergeFromBars(window);
        assertFalse(range.validForEntry());
        assertTrue(range.invalidReason() != null && range.invalidReason().contains("no pad"));
    }

    @Test
    void rejectsTooWideSparsePeaksViaMergeBands() {
        VolumeAtPriceBuilder vap = new VolumeAtPriceBuilder(TrendInstrumentSpec.brDefaults(), false, 1);
        List<MarketProfileBand> bands = List.of(
                new MarketProfileBand(86.00, 86.10, 500),
                new MarketProfileBand(86.10, 86.20, 500),
                new MarketProfileBand(86.20, 86.30, 500),
                new MarketProfileBand(86.30, 86.40, 500),
                new MarketProfileBand(86.40, 86.50, 500)
        );
        MergedVolumeRange range = vap.mergeBands(bands);
        assertFalse(range.validForEntry(), "expected invalid wide range, got " + range);
        assertTrue(range.invalidReason() != null && range.invalidReason().contains("wide"));
    }

    @Test
    void countsTouchClusters() {
        assertEquals(3, VolumeAtPriceBuilder.countTouchClusters(List.of(1, 2, 10, 11, 20), 3));
    }

    @Test
    void tapeShelfPadsToZoneMin() {
        VolumeAtPriceBuilder vap = new VolumeAtPriceBuilder(TrendInstrumentSpec.brDefaults(), false, 2);
        List<double[]> prints = new ArrayList<>();
        // three visits to 86.50 with shelf volume
        for (int visit = 0; visit < 3; visit++) {
            for (int i = 0; i < 80; i++) {
                prints.add(new double[]{86.50 + (i % 5) * 0.01, 10});
            }
            for (int i = 0; i < 50; i++) {
                prints.add(new double[]{86.80, 5}); // leave level
            }
        }
        MergedVolumeRange range = vap.buildAroundLevelFromPrints(prints, 86.50, 3);
        assertTrue(range.validForEntry(), range.invalidReason());
        double pts = range.widthPoints(0.01);
        assertTrue(pts >= 14.9 && pts <= 20.1, "width pts=" + pts);
    }

    @Test
    void rejectsThinShelfBelowMinVolume() {
        VolumeAtPriceBuilder vap = new VolumeAtPriceBuilder(
                TrendInstrumentSpec.brDefaults(), false, 1, 5000);
        List<TrendBar> window = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            window.add(bar(86.40, 86.55, 86.48, 100)); // total shelf vol << 5000
        }
        MergedVolumeRange range = vap.mergeFromBars(window);
        assertFalse(range.validForEntry(), "expected thin reject, got " + range);
        assertTrue(range.invalidReason() != null && range.invalidReason().contains("thin"));
    }

    private static TrendBar bar(double low, double high, double close, double vol) {
        return new TrendBar(LocalDateTime.now(), close, high, low, close, vol);
    }
}
