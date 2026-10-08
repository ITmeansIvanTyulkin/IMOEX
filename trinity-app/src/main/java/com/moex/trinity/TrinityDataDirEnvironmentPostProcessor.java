package com.moex.trinity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves {@code imoex.data-dir=user} (and blank) to the durable per-machine
 * Application Support path after config files / profiles are loaded.
 * Relative {@code data} (dev via application-dev.yml) and absolute overrides stay as-is.
 */
@Order(Ordered.LOWEST_PRECEDENCE)
public class TrinityDataDirEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String configured = environment.getProperty("imoex.data-dir");
        if (!TrinityUserDataPaths.isUserToken(configured)) {
            return;
        }
        Path data = TrinityUserDataPaths.defaultDataDir().toAbsolutePath().normalize();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("imoex.data-dir", data.toString());
        String charts = environment.getProperty("imoex.charts-dir");
        if (charts == null || charts.isBlank() || charts.startsWith("data/")
                || "data/charts".equals(charts)
                || TrinityUserDataPaths.isUserToken(charts)) {
            map.put("imoex.charts-dir", data.resolve("charts").toString());
        }
        // Override the "user" token after profile-specific sources (e.g. application-dev.yml).
        environment.getPropertySources().addFirst(new MapPropertySource("trinityDurableDataDir", map));
    }
}
