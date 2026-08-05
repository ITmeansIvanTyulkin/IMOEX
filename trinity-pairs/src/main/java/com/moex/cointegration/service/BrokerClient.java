package com.moex.cointegration.service;

import com.moex.cointegration.model.BrokerExecutionReport;
import com.moex.cointegration.model.BrokerStatus;
import com.moex.cointegration.model.BrokerAccountSnapshot;
import com.moex.cointegration.model.BrokerSandboxAccountResult;
import com.moex.cointegration.model.BrokerSandboxPayInResult;
import com.moex.cointegration.model.PairExecutionPlan;

/**
 * Абстракция брокерского исполнения. Реальный T-Invest клиент подключим после получения токена.
 */
public interface BrokerClient {

    BrokerStatus status();

    BrokerAccountSnapshot snapshot();

    BrokerExecutionReport preview(PairExecutionPlan plan);

    BrokerExecutionReport execute(PairExecutionPlan plan);

    BrokerExecutionReport flattenAll();

    /**
     * Подтянуть существующий sandbox account или создать новый и сохранить accountId.
     */
    BrokerSandboxAccountResult ensureSandboxAccount();

    /**
     * Пополнить sandbox-счёт в рублях (SandboxPayIn).
     */
    BrokerSandboxPayInResult payInSandbox(double amountRub);
}
