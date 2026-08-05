package com.moex.cointegration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Локальный «ключ запуска». Без него приложение не стартует (секреты не в git).
 */
@ConfigurationProperties(prefix = "imoex.run")
public record RunGuardProperties(String unlock) {
    public RunGuardProperties {
        if (unlock == null) {
            unlock = "";
        }
    }
}
