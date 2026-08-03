package com.moex.cointegration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moex.cointegration.config.BrokerProperties;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.BrokerSettingsUpdateRequest;
import com.moex.cointegration.model.BrokerSettingsView;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class BrokerSettingsService {

    private static final Logger log = LoggerFactory.getLogger(BrokerSettingsService.class);

    private final BrokerProperties baseProperties;
    private final Path settingsFile;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private volatile BrokerProperties overrides;

    public BrokerSettingsService(BrokerProperties baseProperties, ImoexProperties imoexProperties) {
        this.baseProperties = baseProperties;
        this.settingsFile = Path.of(imoexProperties.dataDir(), "broker-ui-settings.json");
    }

    @PostConstruct
    void load() {
        if (!Files.exists(settingsFile)) {
            overrides = baseProperties;
            return;
        }
        try {
            overrides = objectMapper.readValue(settingsFile.toFile(), BrokerProperties.class);
        } catch (Exception ex) {
            overrides = baseProperties;
            log.warn("Could not load broker UI settings {}: {}", settingsFile, ex.getMessage());
        }
    }

    public synchronized BrokerProperties effective() {
        return overrides == null ? baseProperties : overrides;
    }

    public synchronized BrokerSettingsView view() {
        BrokerProperties p = effective();
        String token = p.token();
        return new BrokerSettingsView(
                p.enabledFlag(),
                p.provider(),
                p.mode(),
                p.sandboxFlag(),
                token != null && !token.isBlank(),
                maskToken(token),
                p.accountId(),
                p.autoExecuteAfterAnalysisFlag(),
                p.preferLimitOrdersFlag(),
                p.allowMarketFallbackFlag(),
                p.emergencyMarketExitEnabledFlag(),
                p.passivePriceOffsetBps(),
                p.secondLegTimeoutSeconds(),
                p.maxLegDriftBps(),
                p.killSwitchEnabled()
        );
    }

    public synchronized BrokerSettingsView save(BrokerSettingsUpdateRequest request) {
        BrokerProperties current = effective();
        BrokerProperties next = new BrokerProperties(
                boolOr(current.enabled(), request.enabled()),
                blankTo(current.provider(), request.provider()),
                blankTo(current.mode(), request.mode()),
                boolOr(current.sandbox(), request.sandbox()),
                request.token() == null || request.token().isBlank() ? current.token() : request.token().trim(),
                blankTo(current.accountId(), request.accountId()),
                boolOr(current.autoExecuteAfterAnalysis(), request.autoExecuteAfterAnalysis()),
                boolOr(current.preferLimitOrders(), request.preferLimitOrders()),
                boolOr(current.allowMarketFallback(), request.allowMarketFallback()),
                boolOr(current.emergencyMarketExitEnabled(), request.emergencyMarketExitEnabled()),
                doubleOr(current.passivePriceOffsetBps(), request.passivePriceOffsetBps()),
                intOr(current.secondLegTimeoutSeconds(), request.secondLegTimeoutSeconds()),
                doubleOr(current.maxLegDriftBps(), request.maxLegDriftBps()),
                boolOr(current.killSwitch(), request.killSwitch()),
                current.trustAllSsl()
        );
        overrides = next;
        persist(next);
        return view();
    }

    public synchronized BrokerSettingsView updateAccountId(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId is blank");
        }
        BrokerProperties current = effective();
        BrokerProperties next = new BrokerProperties(
                current.enabled(),
                current.provider(),
                current.mode(),
                current.sandbox(),
                current.token(),
                accountId.trim(),
                current.autoExecuteAfterAnalysis(),
                current.preferLimitOrders(),
                current.allowMarketFallback(),
                current.emergencyMarketExitEnabled(),
                current.passivePriceOffsetBps(),
                current.secondLegTimeoutSeconds(),
                current.maxLegDriftBps(),
                current.killSwitch(),
                current.trustAllSsl()
        );
        overrides = next;
        persist(next);
        return view();
    }

    private void persist(BrokerProperties properties) {
        try {
            Files.createDirectories(settingsFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(settingsFile.toFile(), properties);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not save broker UI settings: " + ex.getMessage(), ex);
        }
    }

    private static String blankTo(String fallback, String value) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Boolean boolOr(Boolean fallback, Boolean value) {
        return value == null ? fallback : value;
    }

    private static Double doubleOr(Double fallback, Double value) {
        return value == null ? fallback : value;
    }

    private static Integer intOr(Integer fallback, Integer value) {
        return value == null ? fallback : value;
    }

    private static String maskToken(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        if (token.length() <= 8) {
            return "********";
        }
        return token.substring(0, 4) + "…" + token.substring(token.length() - 4);
    }
}
