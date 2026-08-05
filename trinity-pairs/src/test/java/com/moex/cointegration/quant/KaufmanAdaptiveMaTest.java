package com.moex.cointegration.quant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KaufmanAdaptiveMaTest {

    @Test
    void kamaTracksUptrendAfterWarmup() {
        double[] values = new double[40];
        for (int i = 0; i < values.length; i++) {
            values[i] = i * 1.0;
        }

        double[] kama = KaufmanAdaptiveMa.computeDefault(values);

        for (int i = 0; i <= 10; i++) {
            if (i < 10) {
                assertTrue(Double.isNaN(kama[i]));
            }
        }
        assertFalse(Double.isNaN(kama[kama.length - 1]));
        assertTrue(kama[kama.length - 1] > kama[20]);
        assertEquals(values[10], kama[10], 1e-9);
    }
}
