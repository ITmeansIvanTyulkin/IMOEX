package com.moex.trinity.trend;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OneSetupPerZoneTest {

    @Test
    void locksFirstArmedSetupAndIgnoresFlip() {
        TrendPlaybookSettings settings = TrendPlaybookSettings.brDefaults();
        TrendPlaybook sticky = new StickyArmedPlaybook();
        TrendRobotEngine engine = new TrendRobotEngine(sticky, settings);
        TrendAccountContext acct = TrendAccountContext.of(100_000, 15_000, 16_000, 1.0);

        List<TrendBar> bars = baseBars();
        Optional<TrendRobotPlan> first = engine.evaluate(new TrendBarSeries("BR", "M5", bars), acct);
        assertTrue(first.isPresent());
        assertTrue(first.get().actionable());
        assertEquals(TrendRobotState.ARMED_BOUNCE, first.get().state());
        assertTrue(first.get().buy());

        // Next bar: playbook would flip to SELL — engine must hold BUY lock
        bars = new ArrayList<>(bars);
        bars.add(new TrendBar(LocalDateTime.of(2024, 12, 1, 14, 0), 84.62, 84.68, 84.58, 84.62, 1000));
        ((StickyArmedPlaybook) sticky).flipToSell = true;
        Optional<TrendRobotPlan> held = engine.evaluate(new TrendBarSeries("BR", "M5", bars), acct);
        assertTrue(held.isPresent());
        assertEquals(TrendRobotState.WORKING_ORDERS, held.get().state());
        assertTrue(held.get().buy());
        assertTrue(held.get().rationale().contains("ONE_SETUP"));
    }

    private static List<TrendBar> baseBars() {
        List<TrendBar> bars = new ArrayList<>();
        LocalDateTime t = LocalDateTime.of(2024, 12, 1, 10, 0);
        for (int i = 0; i < 40; i++) {
            double c = 84.5 + i * 0.02;
            bars.add(new TrendBar(t.plusMinutes(i * 5L), c, c + 0.05, c - 0.05, c, 800));
        }
        return bars;
    }

    /** Stub that returns actionable BUY, then SELL when flipToSell. */
    static class StickyArmedPlaybook implements TrendPlaybook {
        boolean flipToSell;

        @Override
        public String id() {
            return "stub";
        }

        @Override
        public String displayName() {
            return "stub";
        }

        @Override
        public String whenApplicable() {
            return "test";
        }

        @Override
        public Optional<TrendRobotPlan> evaluate(TrendBarSeries series, TrendAccountContext account) {
            boolean buy = !flipToSell;
            MergedVolumeRange range = new MergedVolumeRange(84.50, 84.70, 100, List.of(), true, null);
            LimitGridPlan grid = LimitGridBuilder.build(range, buy, 6, LimitGridStyle.MODERATE);
            return Optional.of(new TrendRobotPlan(
                    id(), series.instrument(), series.timeframe(), LocalDateTime.now(),
                    buy ? TrendRobotState.ARMED_BOUNCE : TrendRobotState.ARMED_BOUNCE,
                    TrendTradeMode.BOUNCE, buy, range, grid,
                    buy ? 84.30 : 84.90, buy ? 84.90 : 84.50, buy ? 85.10 : 84.30,
                    1.0 / 3.0, buy ? "BUY setup" : "SELL setup", List.of()
            ));
        }
    }
}
