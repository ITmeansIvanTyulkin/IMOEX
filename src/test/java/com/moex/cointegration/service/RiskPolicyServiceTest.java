package com.moex.cointegration.service;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.TradingSignal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskPolicyServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void smallerSizeWhenCloserToStop() {
        RiskPolicyService svc = newService(true);
        double far = svc.sizeMultiplier(TradingSignal.SHORT_SPREAD, false, 2.0, 0.04);
        double near = svc.sizeMultiplier(TradingSignal.SHORT_SPREAD, false, 3.2, 0.04);
        assertTrue(near < far);
    }

    @Test
    void smallerSizeWhenSpreadMoreVolatile() {
        RiskPolicyService svc = newService(true);
        // same Z=2 → sigma = |spread|/2; larger |spread| → larger σ → smaller size
        double calm = svc.sizeMultiplier(TradingSignal.LONG_SPREAD, false, -2.0, 0.02);
        double wild = svc.sizeMultiplier(TradingSignal.LONG_SPREAD, false, -2.0, 0.08);
        assertTrue(wild < calm);
    }

    @Test
    void flatSizeWhenDynamicDisabled() {
        RiskPolicyService svc = newService(false);
        assertEquals(1.0, svc.sizeMultiplier(TradingSignal.LONG_SPREAD, false, -2.5, 0.1), 1e-9);
        assertEquals(0.5, svc.sizeMultiplier(TradingSignal.LONG_SPREAD, true, -2.5, 0.1), 1e-9);
    }

    private RiskPolicyService newService(boolean dynamic) {
        ImoexProperties props = new ImoexProperties(
                "https://iss.moex.com/iss", "TQBR", "IMOEX", 5, 0.0005,
                ImoexProperties.CointegrationProperties.of(0.05, 2.0, 0.0, 10),
                new ImoexProperties.NewsProperties(false, 10, 10, 1),
                tempDir.toString(),
                tempDir.resolve("charts").toString(),
                new ImoexProperties.RiskProperties(3.5, 40, 0.5, 5, 1.0, 0.0, 90.0, 1.0, 0.08,
                        dynamic, 0.02, 0.25, 1.5, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null),
                ImoexProperties.WalkForwardProperties.defaults(),
                ImoexProperties.PaperProperties.defaults(),
                new ImoexProperties.UniverseProperties(false, 60, 0, 0, 1.0, false, false, false, 0.0, 100.0),
                ImoexProperties.PortfolioProperties.defaults(),
                ImoexProperties.AuthProperties.defaults()
        );
        return new RiskPolicyService(props);
    }
}
