package com.moex.cointegration.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Результат пополнения sandbox-счёта (SandboxPayIn).
 */
public record BrokerSandboxPayInResult(
        LocalDateTime checkedAt,
        boolean ok,
        String accountId,
        BigDecimal amountRub,
        String balanceRub,
        String summary
) {
}
