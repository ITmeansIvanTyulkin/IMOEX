package com.moex.cointegration.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Результат подтягивания / создания sandbox accountId.
 */
public record BrokerSandboxAccountResult(
        LocalDateTime checkedAt,
        boolean ok,
        boolean created,
        String accountId,
        List<String> availableAccountIds,
        String summary
) {
}
