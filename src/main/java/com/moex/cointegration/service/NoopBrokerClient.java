package com.moex.cointegration.service;

import com.moex.cointegration.model.BrokerExecutionReport;
import com.moex.cointegration.model.BrokerExecutionStatus;
import com.moex.cointegration.model.BrokerMode;
import com.moex.cointegration.model.BrokerAccountSnapshot;
import com.moex.cointegration.model.BrokerStatus;
import com.moex.cointegration.model.PairExecutionPlan;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Безопасная заглушка: контур живой, но реальные ордера без токена не отправляются.
 */
public class NoopBrokerClient implements BrokerClient {

    private final BrokerSettingsService brokerSettingsService;

    public NoopBrokerClient(BrokerSettingsService brokerSettingsService) {
        this.brokerSettingsService = brokerSettingsService;
    }

    @Override
    public BrokerStatus status() {
        var properties = brokerSettingsService.effective();
        boolean tokenPresent = properties.token() != null && !properties.token().isBlank();
        boolean accountPresent = properties.accountId() != null && !properties.accountId().isBlank();
        String summary;
        if (!properties.enabledFlag()) {
            summary = "broker disabled";
        } else if (properties.killSwitchEnabled()) {
            summary = "kill-switch enabled";
        } else if (!tokenPresent) {
            summary = "token missing: preview only";
        } else {
            summary = "stub client active: real T-Invest adapter not connected yet";
        }
        return new BrokerStatus(
                properties.enabledFlag(),
                properties.provider(),
                BrokerMode.from(properties.mode()),
                properties.sandboxFlag(),
                tokenPresent,
                accountPresent,
                properties.killSwitchEnabled(),
                properties.autoExecuteAfterAnalysisFlag(),
                summary
        );
    }

    @Override
    public BrokerAccountSnapshot snapshot() {
        var properties = brokerSettingsService.effective();
        return new BrokerAccountSnapshot(
                properties.provider(),
                LocalDateTime.now(),
                false,
                List.of(),
                List.of(),
                "Broker snapshot unavailable: stub/no token"
        );
    }

    @Override
    public BrokerExecutionReport preview(PairExecutionPlan plan) {
        var properties = brokerSettingsService.effective();
        return new BrokerExecutionReport(
                plan.pairKey(),
                BrokerExecutionStatus.PREVIEW,
                properties.provider(),
                plan.mode(),
                LocalDateTime.now(),
                "Preview only: pair execution plan prepared",
                List.of("Real broker adapter is not connected yet."),
                List.of(),
                plan
        );
    }

    @Override
    public BrokerExecutionReport execute(PairExecutionPlan plan) {
        var properties = brokerSettingsService.effective();
        BrokerStatus status = status();
        BrokerExecutionStatus execStatus;
        String summary;
        List<String> messages;
        if (!status.enabled()) {
            execStatus = BrokerExecutionStatus.SKIPPED;
            summary = "Broker execution skipped: disabled";
            messages = List.of("Enable imoex.broker.enabled to arm execution.");
        } else if (status.killSwitch()) {
            execStatus = BrokerExecutionStatus.SKIPPED;
            summary = "Broker execution skipped: kill-switch";
            messages = List.of("Disable imoex.broker.kill-switch to allow execution.");
        } else if (!status.tokenPresent()) {
            execStatus = BrokerExecutionStatus.SKIPPED;
            summary = "Broker execution skipped: token missing";
            messages = List.of("Token not configured yet, so plan remains preview-only.");
        } else {
            execStatus = BrokerExecutionStatus.FAILED;
            summary = "Broker token present, but T-Invest adapter is not implemented yet";
            messages = List.of("Execution layer is wired, but real API calls are intentionally stubbed.");
        }
        return new BrokerExecutionReport(
                plan.pairKey(),
                execStatus,
                properties.provider(),
                plan.mode(),
                LocalDateTime.now(),
                summary,
                messages,
                List.of(),
                plan
        );
    }

    @Override
    public BrokerExecutionReport flattenAll() {
        var properties = brokerSettingsService.effective();
        return new BrokerExecutionReport(
                "FLATTEN_ALL",
                BrokerExecutionStatus.SKIPPED,
                properties.provider(),
                BrokerMode.from(properties.mode()),
                LocalDateTime.now(),
                "Flatten skipped: broker is not armed",
                List.of("No token/account or broker disabled, so no broker-side flatten was attempted."),
                List.of(),
                null
        );
    }
}
