package com.moex.trinity.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolves T-Invest token / sandbox from env, system props, or {@code data/broker-ui-settings.json}.
 */
public final class TInvestCredentials {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String token;
    private final boolean sandbox;

    public TInvestCredentials(String token, boolean sandbox) {
        this.token = token == null ? "" : token.trim();
        this.sandbox = sandbox;
    }

    public String token() {
        return token;
    }

    public boolean sandbox() {
        return sandbox;
    }

    public boolean present() {
        return !token.isBlank();
    }

    public static TInvestCredentials resolve() {
        String token = firstNonBlank(
                System.getenv("T_INVEST_TOKEN"),
                System.getProperty("imoex.broker.token"),
                System.getProperty("imoex.marketdata.token")
        );
        Boolean sandboxEnv = parseBool(firstNonBlank(
                System.getenv("T_INVEST_SANDBOX"),
                System.getProperty("imoex.broker.sandbox"),
                System.getProperty("imoex.marketdata.sandbox")
        ));
        Path settings = Path.of("data", "broker-ui-settings.json");
        if ((!tokenPresent(token) || sandboxEnv == null) && Files.isRegularFile(settings)) {
            try {
                JsonNode root = MAPPER.readTree(settings.toFile());
                if (!tokenPresent(token)) {
                    token = root.path("token").asText("");
                }
                if (sandboxEnv == null && root.has("sandbox")) {
                    sandboxEnv = root.path("sandbox").asBoolean(true);
                }
            } catch (Exception ignored) {
                // keep env/props
            }
        }
        boolean sandbox = sandboxEnv == null || sandboxEnv;
        return new TInvestCredentials(token, sandbox);
    }

    public static Optional<String> figiOverride(String instrumentId) {
        String map = firstNonBlank(
                System.getenv("IMOEX_MARKETDATA_INSTRUMENTS"),
                System.getProperty("imoex.marketdata.instruments")
        );
        if (map == null || instrumentId == null) {
            return Optional.empty();
        }
        for (String part : map.split(",")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq > 0 && p.substring(0, eq).trim().equalsIgnoreCase(instrumentId.trim())) {
                return Optional.of(p.substring(eq + 1).trim());
            }
        }
        return Optional.empty();
    }

    private static boolean tokenPresent(String token) {
        return token != null && !token.isBlank();
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static Boolean parseBool(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return Boolean.parseBoolean(s.trim());
    }
}
