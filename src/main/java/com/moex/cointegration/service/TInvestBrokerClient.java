package com.moex.cointegration.service;

import com.moex.cointegration.model.BrokerExecutionReport;
import com.moex.cointegration.model.BrokerExecutionStatus;
import com.moex.cointegration.model.BrokerMode;
import com.moex.cointegration.model.BrokerOrderIntent;
import com.moex.cointegration.model.BrokerOrderSnapshot;
import com.moex.cointegration.model.BrokerOrderSide;
import com.moex.cointegration.model.BrokerOrderType;
import com.moex.cointegration.model.BrokerAccountSnapshot;
import com.moex.cointegration.model.BrokerPositionSnapshot;
import com.moex.cointegration.model.BrokerSandboxAccountResult;
import com.moex.cointegration.model.BrokerSandboxPayInResult;
import com.moex.cointegration.model.BrokerStatus;
import com.moex.cointegration.model.PairExecutionPlan;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import ru.tinkoff.piapi.contract.v1.MoneyValue;
import ru.tinkoff.piapi.contract.v1.OrderDirection;
import ru.tinkoff.piapi.contract.v1.OrderExecutionReportStatus;
import ru.tinkoff.piapi.contract.v1.OrderState;
import ru.tinkoff.piapi.contract.v1.OrderType;
import ru.tinkoff.piapi.contract.v1.PriceType;
import ru.tinkoff.piapi.contract.v1.Quotation;
import ru.tinkoff.piapi.contract.v1.SecurityTradingStatus;
import ru.tinkoff.piapi.contract.v1.Share;
import ru.tinkoff.piapi.core.InvestApi;
import ru.tinkoff.piapi.core.models.SecurityPosition;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Реальный T-Invest клиент. Без токена/счёта остаётся в preview-only режиме.
 */
@Service
@Primary
public class TInvestBrokerClient implements BrokerClient {

    private static final Logger log = LoggerFactory.getLogger(TInvestBrokerClient.class);

    private final BrokerSettingsService brokerSettingsService;
    private final NoopBrokerClient fallback;
    private final Map<String, Share> sharesByTicker = new ConcurrentHashMap<>();

    private volatile InvestApi api;
    private volatile String apiToken;
    private volatile Boolean apiSandbox;

    public TInvestBrokerClient(BrokerSettingsService brokerSettingsService) {
        this.brokerSettingsService = brokerSettingsService;
        this.fallback = new NoopBrokerClient(brokerSettingsService);
    }

    @Override
    public BrokerStatus status() {
        var properties = brokerSettingsService.effective();
        boolean tokenPresent = properties.token() != null && !properties.token().isBlank();
        boolean accountPresent = properties.accountId() != null && !properties.accountId().isBlank();
        String summary;
        if (!properties.enabledFlag()) {
            summary = "брокер выключен";
        } else if (properties.killSwitchEnabled()) {
            summary = "аварийный стоп включён";
        } else if (!tokenPresent) {
            summary = "нет токена: только preview";
        } else if (!accountPresent) {
            summary = "нет ID счёта: только preview";
        } else {
            summary = properties.sandboxFlag()
                    ? "T-Invest песочница готова"
                    : "T-Invest live готов";
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
        BrokerStatus brokerStatus = status();
        if (!brokerStatus.enabled() || !brokerStatus.tokenPresent() || !brokerStatus.accountConfigured()) {
            return new BrokerAccountSnapshot(
                    properties.provider(),
                    LocalDateTime.now(),
                    false,
                    List.of(),
                    List.of(),
                    "Broker snapshot unavailable: broker not armed"
            );
        }
        try {
            List<BrokerPositionSnapshot> positions = api().getOperationsService().getPositionsSync(properties.accountId())
                    .getSecurities().stream()
                    .map(this::toPositionSnapshot)
                    .toList();
            List<BrokerOrderSnapshot> activeOrders = api().getOrdersService().getOrdersSync(properties.accountId()).stream()
                    .map(this::toOrderSnapshot)
                    .toList();
            return new BrokerAccountSnapshot(
                    properties.provider(),
                    LocalDateTime.now(),
                    true,
                    positions,
                    activeOrders,
                    String.format(Locale.ROOT, "Broker snapshot loaded: positions=%d, activeOrders=%d",
                            positions.size(), activeOrders.size())
            );
        } catch (Exception ex) {
            return new BrokerAccountSnapshot(
                    properties.provider(),
                    LocalDateTime.now(),
                    false,
                    List.of(),
                    List.of(),
                    "Broker snapshot failed: " + ex.getMessage()
            );
        }
    }

    @Override
    public BrokerExecutionReport preview(PairExecutionPlan plan) {
        return fallback.preview(plan);
    }

    @Override
    public BrokerExecutionReport execute(PairExecutionPlan plan) {
        var properties = brokerSettingsService.effective();
        BrokerStatus brokerStatus = status();
        if (!brokerStatus.enabled() || brokerStatus.killSwitch()
                || !brokerStatus.tokenPresent() || !brokerStatus.accountConfigured()) {
            return fallback.execute(plan);
        }

        try {
            Share shareY = shareByTicker(plan.legY().ticker());
            Share shareX = shareByTicker(plan.legX().ticker());

            List<String> statusBlocks = new ArrayList<>();
            if (!assertTradable(shareY, statusBlocks) || !assertTradable(shareX, statusBlocks)) {
                return failed(plan, "Инструмент недоступен для торговли", statusBlocks);
            }

            long lotsY = lots(plan.legY(), shareY);
            long lotsX = lots(plan.legX(), shareX);
            if (lotsY <= 0 || lotsX <= 0) {
                return failed(plan, "Zero lots after size conversion", List.of(
                        "Check capital, lot size and latest price references."
                ));
            }

            String orderIdY = submitLeg(plan.legY(), shareY, lotsY);
            String orderIdX;
            try {
                orderIdX = submitLeg(plan.legX(), shareX, lotsX);
            } catch (Exception secondLegError) {
                cancel(orderIdY);
                throw secondLegError;
            }

            SafetyResult safety = awaitPairCompletion(
                    new WorkingLeg(orderIdY, plan.legY(), shareY, lotsY, plan.legY().limitPrice()),
                    new WorkingLeg(orderIdX, plan.legX(), shareX, lotsX, plan.legX().limitPrice()),
                    plan
            );
            if (!safety.ok()) {
                return failed(plan, safety.summary(), safety.messages());
            }

            return new BrokerExecutionReport(
                    plan.pairKey(),
                    BrokerExecutionStatus.SUBMITTED,
                    properties.provider(),
                    plan.mode(),
                    LocalDateTime.now(),
                    String.format(Locale.ROOT, "Pair armed safely: Y=%s X=%s", orderIdY, orderIdX),
                    safety.messages(),
                    List.of(orderIdY, orderIdX),
                    plan
            );
        } catch (Exception ex) {
            log.warn("T-Invest execute failed for {}: {}", plan.pairKey(), ex.getMessage(), ex);
            return failed(plan, "T-Invest execution failed", List.of(ex.getMessage()));
        }
    }

    @Override
    public BrokerExecutionReport flattenAll() {
        var properties = brokerSettingsService.effective();
        BrokerStatus brokerStatus = status();
        if (!brokerStatus.enabled() || brokerStatus.killSwitch()
                || !brokerStatus.tokenPresent() || !brokerStatus.accountConfigured()) {
            return fallback.flattenAll();
        }
        try {
            List<String> messages = new ArrayList<>();
            List<String> orderIds = new ArrayList<>();
            List<OrderState> activeOrders = api().getOrdersService().getOrdersSync(properties.accountId());
            for (OrderState order : activeOrders) {
                cancel(order.getOrderId());
                orderIds.add(order.getOrderId());
            }
            messages.add("Cancelled active orders: " + activeOrders.size());

            List<SecurityPosition> positions = api().getOperationsService().getPositionsSync(properties.accountId())
                    .getSecurities();
            int flattened = 0;
            for (SecurityPosition position : positions) {
                long balance = position.getBalance();
                if (balance == 0L) {
                    continue;
                }
                String closeOrderId = flattenPosition(position);
                if (closeOrderId != null) {
                    orderIds.add(closeOrderId);
                    flattened++;
                }
            }
            messages.add("Submitted emergency close orders: " + flattened);
            return new BrokerExecutionReport(
                    "FLATTEN_ALL",
                    BrokerExecutionStatus.SUBMITTED,
                    properties.provider(),
                    BrokerMode.from(properties.mode()),
                    LocalDateTime.now(),
                    "Broker flatten-all submitted",
                    messages,
                    orderIds,
                    null
            );
        } catch (Exception ex) {
            log.warn("T-Invest flatten-all failed: {}", ex.getMessage(), ex);
            return new BrokerExecutionReport(
                    "FLATTEN_ALL",
                    BrokerExecutionStatus.FAILED,
                    properties.provider(),
                    BrokerMode.from(properties.mode()),
                    LocalDateTime.now(),
                    "Broker flatten-all failed",
                    List.of(ex.getMessage()),
                    List.of(),
                    null
            );
        }
    }

    @Override
    public BrokerSandboxAccountResult ensureSandboxAccount() {
        var properties = brokerSettingsService.effective();
        String token = properties.token();
        if (token == null || token.isBlank()) {
            return new BrokerSandboxAccountResult(
                    LocalDateTime.now(),
                    false,
                    false,
                    null,
                    List.of(),
                    "Сначала сохраните токен песочницы"
            );
        }
        if (!properties.sandboxFlag()) {
            return new BrokerSandboxAccountResult(
                    LocalDateTime.now(),
                    false,
                    false,
                    properties.accountId(),
                    List.of(),
                    "Включите «Песочница»: создание/подтягивание счёта доступно только там"
            );
        }
        try {
            resetApi();
            List<String> available = api().getSandboxService().getAccountsSync().stream()
                    .map(account -> account.getId())
                    .filter(id -> id != null && !id.isBlank())
                    .toList();
            String current = properties.accountId();
            if (current != null && !current.isBlank() && available.contains(current)) {
                return new BrokerSandboxAccountResult(
                        LocalDateTime.now(),
                        true,
                        false,
                        current,
                        available,
                        "Счёт песочницы уже настроен: " + current
                );
            }
            boolean created = false;
            String accountId;
            if (!available.isEmpty()) {
                accountId = available.get(0);
            } else {
                accountId = api().getSandboxService().openAccountSync();
                if (accountId == null || accountId.isBlank()) {
                    throw new IllegalStateException("T-Invest вернул пустой accountId при OpenSandboxAccount");
                }
                created = true;
                available = List.of(accountId);
            }
            brokerSettingsService.updateAccountId(accountId);
            return new BrokerSandboxAccountResult(
                    LocalDateTime.now(),
                    true,
                    created,
                    accountId,
                    available,
                    created
                            ? "Создан новый счёт песочницы: " + accountId
                            : "Подтянут существующий счёт песочницы: " + accountId
            );
        } catch (Exception ex) {
            log.warn("T-Invest sandbox account ensure failed: {}", ex.getMessage(), ex);
            return new BrokerSandboxAccountResult(
                    LocalDateTime.now(),
                    false,
                    false,
                    properties.accountId(),
                    List.of(),
                    "Не удалось получить счёт песочницы: " + rootMessage(ex)
            );
        }
    }

    @Override
    public BrokerSandboxPayInResult payInSandbox(double amountRub) {
        var properties = brokerSettingsService.effective();
        String token = properties.token();
        String accountId = properties.accountId();
        if (token == null || token.isBlank()) {
            return new BrokerSandboxPayInResult(
                    LocalDateTime.now(), false, accountId, BigDecimal.valueOf(amountRub), null,
                    "Сначала сохраните токен песочницы"
            );
        }
        if (!properties.sandboxFlag()) {
            return new BrokerSandboxPayInResult(
                    LocalDateTime.now(), false, accountId, BigDecimal.valueOf(amountRub), null,
                    "Пополнение доступно только в песочнице"
            );
        }
        if (accountId == null || accountId.isBlank()) {
            return new BrokerSandboxPayInResult(
                    LocalDateTime.now(), false, null, BigDecimal.valueOf(amountRub), null,
                    "Сначала подтяните / создайте счёт песочницы"
            );
        }
        if (amountRub <= 0) {
            return new BrokerSandboxPayInResult(
                    LocalDateTime.now(), false, accountId, BigDecimal.valueOf(amountRub), null,
                    "Сумма пополнения должна быть > 0"
            );
        }
        try {
            resetApi();
            long units = Math.round(amountRub);
            MoneyValue paid = api().getSandboxService().payInSync(
                    accountId,
                    MoneyValue.newBuilder().setUnits(units).setNano(0).setCurrency("RUB").build()
            );
            String balance = moneyToString(paid);
            return new BrokerSandboxPayInResult(
                    LocalDateTime.now(),
                    true,
                    accountId,
                    BigDecimal.valueOf(units),
                    balance,
                    "Песочница пополнена на " + units + " RUB. Баланс: " + balance
            );
        } catch (Exception ex) {
            log.warn("T-Invest sandbox pay-in failed: {}", ex.getMessage(), ex);
            return new BrokerSandboxPayInResult(
                    LocalDateTime.now(),
                    false,
                    accountId,
                    BigDecimal.valueOf(amountRub),
                    null,
                    "Не удалось пополнить песочницу: " + rootMessage(ex)
            );
        }
    }

    @PreDestroy
    void destroy() {
        resetApi();
    }

    private synchronized void resetApi() {
        if (api != null) {
            try {
                api.destroy(3);
            } catch (Exception ignored) {
                // best effort
            }
        }
        api = null;
        apiToken = null;
        apiSandbox = null;
        sharesByTicker.clear();
    }

    private synchronized InvestApi api() {
        var properties = brokerSettingsService.effective();
        if (api == null || !safeEquals(apiToken, properties.token()) || !safeEquals(apiSandbox, properties.sandbox())) {
            if (api != null) {
                try {
                    api.destroy(3);
                } catch (Exception ignored) {
                    // best effort
                }
            }
            sharesByTicker.clear();
            apiToken = properties.token();
            apiSandbox = properties.sandbox();
            api = properties.sandboxFlag()
                    ? InvestApi.createSandbox(properties.token())
                    : InvestApi.create(properties.token());
        }
        return api;
    }

    private Share shareByTicker(String ticker) {
        return sharesByTicker.computeIfAbsent(ticker.toUpperCase(Locale.ROOT), key ->
                api().getInstrumentsService().getTradableSharesSync().stream()
                        .filter(s -> key.equalsIgnoreCase(s.getTicker()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Ticker not found in T-Invest tradable shares: " + key))
        );
    }

    private long lots(BrokerOrderIntent leg, Share share) {
        if (leg.quantity() == null || leg.quantity() <= 0) {
            return 0;
        }
        long lot = Math.max(1L, share.getLot());
        return Math.max(1L, (long) Math.ceil(leg.quantity() / lot));
    }

    private String submitLeg(BrokerOrderIntent leg, Share share, long lots) {
        var properties = brokerSettingsService.effective();
        String accountId = properties.accountId();
        String orderId = UUID.randomUUID().toString();
        OrderDirection direction = leg.side() == BrokerOrderSide.BUY
                ? OrderDirection.ORDER_DIRECTION_BUY
                : OrderDirection.ORDER_DIRECTION_SELL;
        OrderType orderType = leg.orderType() == BrokerOrderType.MARKET
                ? OrderType.ORDER_TYPE_MARKET
                : OrderType.ORDER_TYPE_LIMIT;
        Quotation price = null;
        if (leg.limitPrice() != null) {
            BigDecimal aligned = alignToMinIncrement(BigDecimal.valueOf(leg.limitPrice()), share.getMinPriceIncrement());
            price = quotation(aligned);
        }

        log.info("T-Invest submit {} {} lots {} as {} @{} [{}]",
                leg.side(), leg.ticker(), lots, orderType,
                price == null ? null : decimal(price), properties.sandboxFlag() ? "sandbox" : "live");

        return api().getOrdersService().postOrderSync(
                share.getFigi(),
                lots,
                price,
                direction,
                accountId,
                orderType,
                orderId
        ).getOrderId();
    }

    private String replaceOrder(String orderId, long lots, double desiredPrice) {
        var properties = brokerSettingsService.effective();
        // desiredPrice already aligned in desiredPassiveLimit
        Quotation price = quotation(BigDecimal.valueOf(desiredPrice));
        return api().getOrdersService().replaceOrderSync(
                orderId,
                lots,
                price,
                properties.accountId(),
                UUID.randomUUID().toString(),
                PriceType.PRICE_TYPE_CURRENCY
        ).getOrderId();
    }

    private SafetyResult awaitPairCompletion(WorkingLeg legY, WorkingLeg legX, PairExecutionPlan plan) {
        var properties = brokerSettingsService.effective();
        long deadline = System.currentTimeMillis() + properties.secondLegTimeoutSeconds() * 1000L;
        List<String> messages = new ArrayList<>();
        while (System.currentTimeMillis() < deadline) {
            OrderState y = orderState(legY.orderId());
            OrderState x = orderState(legX.orderId());
            if (y == null || x == null) {
                messages.add("Could not read one of order states from broker.");
                break;
            }

            boolean yRejected = rejected(y);
            boolean xRejected = rejected(x);
            if (yRejected || xRejected) {
                cancelOpen(legY.orderId(), y);
                cancelOpen(legX.orderId(), x);
                messages.add("At least one leg was rejected/cancelled by broker.");
                messages.add(stateLine("Y", y));
                messages.add(stateLine("X", x));
                return new SafetyResult(false, "Pair execution aborted: leg rejected/cancelled", messages);
            }

            boolean yDone = filled(y);
            boolean xDone = filled(x);
            if (yDone && xDone) {
                messages.add("Both legs reached FILL status.");
                messages.add(stateLine("Y", y));
                messages.add(stateLine("X", x));
                messages.add(properties.preferLimitOrdersFlag()
                        ? "Both legs used limit orders."
                        : "Market orders allowed by configuration.");
                return new SafetyResult(true, "Both legs filled", messages);
            }

            if (!yDone && !rejected(y) && legY.intent().orderType() == BrokerOrderType.LIMIT) {
                RepriceResult repriced = maybeReprice(legY, plan);
                if (repriced.abort()) {
                    cancelOpen(legY.orderId(), y);
                    cancelOpen(legX.orderId(), x);
                    return new SafetyResult(false, repriced.summary(), repriced.messages());
                }
                if (repriced.workingLeg() != null) {
                    legY = repriced.workingLeg();
                }
            }
            if (!xDone && !rejected(x) && legX.intent().orderType() == BrokerOrderType.LIMIT) {
                RepriceResult repriced = maybeReprice(legX, plan);
                if (repriced.abort()) {
                    cancelOpen(legY.orderId(), y);
                    cancelOpen(legX.orderId(), x);
                    return new SafetyResult(false, repriced.summary(), repriced.messages());
                }
                if (repriced.workingLeg() != null) {
                    legX = repriced.workingLeg();
                }
            }

            sleepQuietly(1500);
        }

        OrderState y = orderState(legY.orderId());
        OrderState x = orderState(legX.orderId());
        cancelOpen(legY.orderId(), y);
        cancelOpen(legX.orderId(), x);

        List<String> timeoutMessages = new ArrayList<>();
        timeoutMessages.add("Timeout while waiting pair completion; open остатки отменены.");
        if (y != null) {
            timeoutMessages.add(stateLine("Y", y));
        }
        if (x != null) {
            timeoutMessages.add(stateLine("X", x));
        }
        if ((y != null && partiallyFilledOrFilled(y)) || (x != null && partiallyFilledOrFilled(x))) {
            if (properties.emergencyMarketExitEnabledFlag()) {
                BrokerExecutionReport emergency = flattenAll();
                timeoutMessages.add("Asymmetric fill risk detected; emergency flatten triggered.");
                timeoutMessages.add(emergency.summary());
                timeoutMessages.addAll(emergency.messages());
            } else {
                timeoutMessages.add("Asymmetric fill risk: one leg has executions. Manual reconcile/flatten required.");
            }
        }
        return new SafetyResult(false, "Pair execution timed out before full 2-leg fill", timeoutMessages);
    }

    private OrderState orderState(String orderId) {
        var properties = brokerSettingsService.effective();
        return api().getOrdersService().getOrderStateSync(properties.accountId(), orderId);
    }

    private RepriceResult maybeReprice(WorkingLeg leg, PairExecutionPlan plan) {
        Double desired = desiredPassiveLimit(leg.intent().side(), leg.share());
        if (desired == null || desired <= 0) {
            return RepriceResult.noop();
        }
        double drift = driftBps(leg.intent().referencePrice(), desired);
        if (drift > plan.maxLegDriftBps()) {
            return RepriceResult.abort(
                    "Price drift exceeded safety band",
                    List.of(String.format(Locale.ROOT,
                            "%s desired %.2f drift %.1f bps from ref %.2f exceeds max %.1f",
                            leg.intent().ticker(), desired, drift,
                            leg.intent().referencePrice(), plan.maxLegDriftBps()))
            );
        }
        if (leg.currentLimitPrice() != null && driftBps(leg.currentLimitPrice(), desired) < 1.0) {
            return RepriceResult.noop();
        }

        String nextOrderId = replaceOrder(leg.orderId(), leg.lots(), desired);
        return RepriceResult.updated(new WorkingLeg(
                nextOrderId == null || nextOrderId.isBlank() ? leg.orderId() : nextOrderId,
                leg.intent(),
                leg.share(),
                leg.lots(),
                desired
        ));
    }

    private void cancelOpen(String orderId, OrderState state) {
        if (orderId == null || orderId.isBlank() || state == null) {
            return;
        }
        if (filled(state) || rejected(state)) {
            return;
        }
        cancel(orderId);
    }

    private void cancel(String orderId) {
        var properties = brokerSettingsService.effective();
        if (orderId == null || orderId.isBlank()) {
            return;
        }
        try {
            api().getOrdersService().cancelOrder(properties.accountId(), orderId);
        } catch (Exception ex) {
            log.warn("Failed to cancel first leg {} after second-leg error: {}", orderId, ex.getMessage());
        }
    }

    private Double desiredPassiveLimit(BrokerOrderSide side, Share share) {
        var properties = brokerSettingsService.effective();
        BigDecimal last = lastPrice(share);
        if (last == null) {
            return null;
        }
        double bps = properties.passivePriceOffsetBps() / 10_000.0;
        BigDecimal raw = last.multiply(BigDecimal.valueOf(side == BrokerOrderSide.BUY ? (1.0 - bps) : (1.0 + bps)));
        BigDecimal aligned = alignToMinIncrement(raw, share.getMinPriceIncrement());
        return aligned.doubleValue();
    }

    private BrokerExecutionReport failed(PairExecutionPlan plan, String summary, List<String> messages) {
        var properties = brokerSettingsService.effective();
        return new BrokerExecutionReport(
                plan.pairKey(),
                BrokerExecutionStatus.FAILED,
                properties.provider(),
                plan.mode(),
                LocalDateTime.now(),
                summary,
                messages,
                List.of(),
                plan
        );
    }

    private static boolean filled(OrderState state) {
        return state.getExecutionReportStatus() == OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_FILL;
    }

    private static boolean rejected(OrderState state) {
        return state.getExecutionReportStatus() == OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_REJECTED
                || state.getExecutionReportStatus() == OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_CANCELLED;
    }

    private static boolean partiallyFilledOrFilled(OrderState state) {
        return state.getExecutionReportStatus() == OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_PARTIALLYFILL
                || filled(state);
    }

    private static String stateLine(String leg, OrderState state) {
        return String.format(Locale.ROOT, "%s status=%s requested=%d executed=%d",
                leg,
                state.getExecutionReportStatus().name(),
                state.getLotsRequested(),
                state.getLotsExecuted());
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private BigDecimal lastPrice(Share share) {
        try {
            Quotation q = api().getMarketDataService().getLastPricesSync(List.of(share.getFigi())).get(0).getPrice();
            return decimal(q);
        } catch (Exception ex) {
            return null;
        }
    }

    private static double driftBps(Double reference, Double candidate) {
        if (reference == null || candidate == null || reference <= 0) {
            return 0.0;
        }
        return Math.abs(candidate - reference) / reference * 10_000.0;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * Округление цены к minPriceIncrement инструмента (как в официальном Example.java).
     */
    static BigDecimal alignToMinIncrement(BigDecimal price, Quotation minIncrement) {
        if (price == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal step = decimal(minIncrement);
        if (step == null || step.compareTo(BigDecimal.ZERO) <= 0) {
            return price.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal steps = price.divide(step, 0, RoundingMode.HALF_UP);
        return steps.multiply(step).stripTrailingZeros();
    }

    private boolean assertTradable(Share share, List<String> messages) {
        try {
            SecurityTradingStatus status = api().getMarketDataService()
                    .getTradingStatusSync(share.getFigi())
                    .getTradingStatus();
            if (isTradable(status)) {
                return true;
            }
            messages.add(String.format(Locale.ROOT, "%s tradingStatus=%s", share.getTicker(), status.name()));
            return false;
        } catch (Exception ex) {
            messages.add(String.format(Locale.ROOT, "%s tradingStatus check failed: %s",
                    share.getTicker(), rootMessage(ex)));
            return false;
        }
    }

    static boolean isTradable(SecurityTradingStatus status) {
        return status == SecurityTradingStatus.SECURITY_TRADING_STATUS_NORMAL_TRADING
                || status == SecurityTradingStatus.SECURITY_TRADING_STATUS_DEALER_NORMAL_TRADING
                || status == SecurityTradingStatus.SECURITY_TRADING_STATUS_SESSION_OPEN;
    }

    private static String moneyToString(MoneyValue value) {
        if (value == null) {
            return "—";
        }
        BigDecimal amount = BigDecimal.valueOf(value.getUnits()).add(BigDecimal.valueOf(value.getNano(), 9));
        return amount.stripTrailingZeros().toPlainString() + " " + value.getCurrency();
    }

    private static Quotation quotation(BigDecimal value) {
        BigDecimal normalized = value == null ? BigDecimal.ZERO : value;
        BigDecimal nanos = normalized.remainder(BigDecimal.ONE).movePointRight(9);
        return Quotation.newBuilder()
                .setUnits(normalized.longValue())
                .setNano(nanos.intValue())
                .build();
    }

    private static BigDecimal decimal(Quotation value) {
        if (value == null) {
            return null;
        }
        return BigDecimal.valueOf(value.getUnits()).add(BigDecimal.valueOf(value.getNano(), 9));
    }

    private BrokerPositionSnapshot toPositionSnapshot(SecurityPosition position) {
        String figi = position.getFigi();
        String ticker = tickerByFigi(figi);
        return new BrokerPositionSnapshot(ticker, figi, position.getBalance());
    }

    private BrokerOrderSnapshot toOrderSnapshot(OrderState state) {
        return new BrokerOrderSnapshot(
                state.getOrderId(),
                tickerByFigi(state.getFigi()),
                state.getExecutionReportStatus().name(),
                state.getLotsRequested(),
                state.getLotsExecuted(),
                state.getDirection().name(),
                state.getOrderType().name()
        );
    }

    private String tickerByFigi(String figi) {
        if (figi == null || figi.isBlank()) {
            return "?";
        }
        return sharesByTicker.values().stream()
                .filter(s -> figi.equals(s.getFigi()))
                .map(Share::getTicker)
                .findFirst()
                .orElseGet(() -> {
                    try {
                        return api().getInstrumentsService().getInstrumentByFigiSync(figi).getTicker();
                    } catch (Exception ex) {
                        return figi;
                    }
                });
    }

    private String flattenPosition(SecurityPosition position) {
        var properties = brokerSettingsService.effective();
        String figi = position.getFigi();
        long balance = position.getBalance();
        if (balance == 0L) {
            return null;
        }
        Share share = shareByTicker(tickerByFigi(figi));
        long lot = Math.max(1L, share.getLot());
        long lots = Math.max(1L, Math.abs(balance) / lot);
        OrderDirection direction = balance > 0
                ? OrderDirection.ORDER_DIRECTION_SELL
                : OrderDirection.ORDER_DIRECTION_BUY;
        return api().getOrdersService().postOrderSync(
                figi,
                lots,
                null,
                direction,
                properties.accountId(),
                OrderType.ORDER_TYPE_MARKET,
                UUID.randomUUID().toString()
        ).getOrderId();
    }

    private static boolean safeEquals(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String rootMessage(Throwable ex) {
        Throwable cur = ex;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String message = cur.getMessage();
        return message == null || message.isBlank() ? cur.getClass().getSimpleName() : message;
    }

    private record SafetyResult(boolean ok, String summary, List<String> messages) {
    }

    private record WorkingLeg(
            String orderId,
            BrokerOrderIntent intent,
            Share share,
            long lots,
            Double currentLimitPrice
    ) {
    }

    private record RepriceResult(
            boolean abort,
            String summary,
            List<String> messages,
            WorkingLeg workingLeg
    ) {
        static RepriceResult noop() {
            return new RepriceResult(false, null, List.of(), null);
        }

        static RepriceResult updated(WorkingLeg workingLeg) {
            return new RepriceResult(false, null, List.of(), workingLeg);
        }

        static RepriceResult abort(String summary, List<String> messages) {
            return new RepriceResult(true, summary, messages, null);
        }
    }
}
