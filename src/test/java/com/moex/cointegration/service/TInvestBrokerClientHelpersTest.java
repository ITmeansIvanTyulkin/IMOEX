package com.moex.cointegration.service;

import org.junit.jupiter.api.Test;
import ru.tinkoff.piapi.contract.v1.Quotation;
import ru.tinkoff.piapi.contract.v1.SecurityTradingStatus;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TInvestBrokerClientHelpersTest {

    @Test
    void alignsPriceToMinIncrement() {
        Quotation step = Quotation.newBuilder().setUnits(0).setNano(10_000_000).build(); // 0.01
        BigDecimal aligned = TInvestBrokerClient.alignToMinIncrement(new BigDecimal("123.456"), step);
        assertEquals(0, aligned.compareTo(new BigDecimal("123.46")));
    }

    @Test
    void alignsOddIncrement() {
        Quotation step = Quotation.newBuilder().setUnits(0).setNano(50_000_000).build(); // 0.05
        BigDecimal aligned = TInvestBrokerClient.alignToMinIncrement(new BigDecimal("10.12"), step);
        assertEquals(0, aligned.compareTo(new BigDecimal("10.10")));
    }

    @Test
    void tradableStatuses() {
        assertTrue(TInvestBrokerClient.isTradable(SecurityTradingStatus.SECURITY_TRADING_STATUS_NORMAL_TRADING));
        assertTrue(TInvestBrokerClient.isTradable(SecurityTradingStatus.SECURITY_TRADING_STATUS_SESSION_OPEN));
        assertFalse(TInvestBrokerClient.isTradable(SecurityTradingStatus.SECURITY_TRADING_STATUS_BREAK_IN_TRADING));
        assertFalse(TInvestBrokerClient.isTradable(SecurityTradingStatus.SECURITY_TRADING_STATUS_NOT_AVAILABLE_FOR_TRADING));
    }
}
