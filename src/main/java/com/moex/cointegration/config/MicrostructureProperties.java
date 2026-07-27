package com.moex.cointegration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ATAS-inspired microstructure gates (ISS OHLCV) + foundation for future trend strategy.
 */
@ConfigurationProperties(prefix = "imoex.microstructure")
public record MicrostructureProperties(
        Boolean enabled,
        Boolean intradayEnabled,
        Double minRelativeVolume,
        Double maxSpreadProxyBps,
        Double minLegDeltaAlignment,
        Double maxLegVolumeRatio,
        Integer sessionOpenSkipMinutes,
        Integer sessionCloseSkipMinutes,
        Integer volumeProfileLookback,
        Boolean blockOutsideValueArea,
        Boolean domEnabled,
        Double minDomDepthRub,
        Double maxDomSpreadBps,
        Double minFootprintAlignment,
        Double clusterVolumeMult,
        Boolean blockIcebergSuspect,
        Boolean pocPartialTpEnabled,
        Double pocProximityBps,
        TrendMicrostructureProperties trend
) {
    public MicrostructureProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (intradayEnabled == null) {
            intradayEnabled = true;
        }
        if (minRelativeVolume == null || minRelativeVolume <= 0) {
            minRelativeVolume = 0.60;
        }
        if (maxSpreadProxyBps == null || maxSpreadProxyBps <= 0) {
            maxSpreadProxyBps = 35.0;
        }
        if (minLegDeltaAlignment == null || minLegDeltaAlignment < 0) {
            minLegDeltaAlignment = 0.15;
        }
        if (maxLegVolumeRatio == null || maxLegVolumeRatio < 1) {
            maxLegVolumeRatio = 5.0;
        }
        if (sessionOpenSkipMinutes == null || sessionOpenSkipMinutes < 0) {
            sessionOpenSkipMinutes = 15;
        }
        if (sessionCloseSkipMinutes == null || sessionCloseSkipMinutes < 0) {
            sessionCloseSkipMinutes = 30;
        }
        if (volumeProfileLookback == null || volumeProfileLookback < 5) {
            volumeProfileLookback = 20;
        }
        if (blockOutsideValueArea == null) {
            blockOutsideValueArea = true;
        }
        if (domEnabled == null) {
            domEnabled = true;
        }
        if (minDomDepthRub == null || minDomDepthRub <= 0) {
            minDomDepthRub = 300_000.0;
        }
        if (maxDomSpreadBps == null || maxDomSpreadBps <= 0) {
            maxDomSpreadBps = 25.0;
        }
        if (minFootprintAlignment == null || minFootprintAlignment < 0) {
            minFootprintAlignment = 0.20;
        }
        if (clusterVolumeMult == null || clusterVolumeMult < 1) {
            clusterVolumeMult = 2.0;
        }
        if (blockIcebergSuspect == null) {
            blockIcebergSuspect = true;
        }
        if (pocPartialTpEnabled == null) {
            pocPartialTpEnabled = true;
        }
        if (pocProximityBps == null || pocProximityBps <= 0) {
            pocProximityBps = 15.0;
        }
        if (trend == null) {
            trend = TrendMicrostructureProperties.defaults();
        }
    }

    public static MicrostructureProperties defaults() {
        return new MicrostructureProperties(
                true, true, 0.60, 35.0, 0.15, 5.0,
                15, 30, 20, true,
                true, 300_000.0, 25.0, 0.20, 2.0, true, true, 15.0,
                TrendMicrostructureProperties.defaults()
        );
    }

    /**
     * Research: базовый RVOL/spread/session-edge, без жёсткого ATAS (DOM/VA/iceberg/footprint).
     * Live-гейт остаётся {@link #defaults()}.
     */
    public static MicrostructureProperties researchDefaults() {
        return new MicrostructureProperties(
                true, true, 0.40, 45.0, 0.0, 8.0,
                10, 20, 20, false,
                false, 300_000.0, 25.0, 0.0, 2.0, false, true, 15.0,
                TrendMicrostructureProperties.defaults()
        );
    }

    /**
     * Research mid: только session-edge + мягкий RVOL/spread, без ATAS-прокси.
     * Между researchDefaults (мало сделок) и micro=off (шум).
     */
    public static MicrostructureProperties sessionEdgeOnly() {
        return new MicrostructureProperties(
                true, true, 0.25, 60.0, 0.0, 12.0,
                5, 15, 20, false,
                false, 300_000.0, 25.0, 0.0, 2.0, false, false, 15.0,
                TrendMicrostructureProperties.defaults()
        );
    }

    public boolean domEnabledFlag() {
        return Boolean.TRUE.equals(domEnabled);
    }

    public boolean blockIcebergSuspectEnabled() {
        return Boolean.TRUE.equals(blockIcebergSuspect);
    }

    public boolean pocPartialTpEnabledFlag() {
        return Boolean.TRUE.equals(pocPartialTpEnabled);
    }

    public boolean enabledFlag() {
        return Boolean.TRUE.equals(enabled);
    }

    public boolean intradayEnabledFlag() {
        return Boolean.TRUE.equals(intradayEnabled);
    }

    public boolean blockOutsideValueAreaEnabled() {
        return Boolean.TRUE.equals(blockOutsideValueArea);
    }

    /**
     * Параметры order-flow / value-area для будущей трендовой стратегии (roadmap #2).
     */
    public record TrendMicrostructureProperties(
            Boolean enabled,
            Double deltaMomentumThreshold,
            Integer valueAreaBreakoutMinBars,
            Double absorptionVolumeMult,
            Double absorptionRangeMaxBps,
            Double breakoutDeltaMin,
            Integer footprintLookbackBars
    ) {
        public TrendMicrostructureProperties {
            if (enabled == null) {
                enabled = false;
            }
            if (deltaMomentumThreshold == null || deltaMomentumThreshold <= 0) {
                deltaMomentumThreshold = 0.55;
            }
            if (valueAreaBreakoutMinBars == null || valueAreaBreakoutMinBars < 1) {
                valueAreaBreakoutMinBars = 3;
            }
            if (absorptionVolumeMult == null || absorptionVolumeMult < 1) {
                absorptionVolumeMult = 2.5;
            }
            if (absorptionRangeMaxBps == null || absorptionRangeMaxBps <= 0) {
                absorptionRangeMaxBps = 15.0;
            }
            if (breakoutDeltaMin == null || breakoutDeltaMin <= 0) {
                breakoutDeltaMin = 0.60;
            }
            if (footprintLookbackBars == null || footprintLookbackBars < 5) {
                footprintLookbackBars = 24;
            }
        }

        public static TrendMicrostructureProperties defaults() {
            return new TrendMicrostructureProperties(
                    false, 0.55, 3, 2.5, 15.0, 0.60, 24
            );
        }

        public boolean enabledFlag() {
            return Boolean.TRUE.equals(enabled);
        }
    }
}
