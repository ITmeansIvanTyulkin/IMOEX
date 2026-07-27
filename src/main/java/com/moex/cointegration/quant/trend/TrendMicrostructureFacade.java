package com.moex.cointegration.quant.trend;

import com.moex.cointegration.config.MicrostructureProperties;
import com.moex.cointegration.model.Candle;
import com.moex.cointegration.quant.trend.ValueAreaBreakoutDetector.BreakoutDirection;

import java.util.List;

/**
 * Фасад трендовой microstructure (roadmap strategy #2). Пока не подключён к пайплайну pairs.
 */
public final class TrendMicrostructureFacade {

    private TrendMicrostructureFacade() {
    }

    public record TrendMicroSnapshot(
            BreakoutDirection breakout,
            double deltaMomentum,
            boolean absorption,
            String summary
    ) {
        public static TrendMicroSnapshot empty() {
            return new TrendMicroSnapshot(BreakoutDirection.NONE, 0, false, "trend module off");
        }
    }

    public static TrendMicroSnapshot snapshot(List<Candle> candles, MicrostructureProperties.TrendMicrostructureProperties cfg) {
        if (!cfg.enabledFlag() || candles == null || candles.size() < 10) {
            return TrendMicroSnapshot.empty();
        }
        int lookback = cfg.footprintLookbackBars();
        double delta = OrderFlowAnalyzer.volumeWeightedDeltaMomentum(candles, lookback);
        BreakoutDirection br = ValueAreaBreakoutDetector.detect(
                candles,
                lookback,
                cfg.valueAreaBreakoutMinBars(),
                cfg.breakoutDeltaMin()
        );
        boolean abs = TrendAbsorptionDetector.absorptionAtLevel(
                candles,
                lookback,
                cfg.absorptionVolumeMult(),
                cfg.absorptionRangeMaxBps()
        );
        String summary = String.format(
                "breakout=%s delta=%.2f absorption=%s",
                br, delta, abs
        );
        return new TrendMicroSnapshot(br, delta, abs, summary);
    }
}
