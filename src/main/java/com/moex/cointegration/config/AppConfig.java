package com.moex.cointegration.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Базовая конфигурация Spring-бинов приложения.
 */
@Configuration
public class AppConfig {

    /**
     * HTTP-клиент для запросов к MOEX ISS API с таймаутами подключения и чтения.
     *
     * @param builder фабрика RestTemplate от Spring Boot
     * @return настроенный RestTemplate
     */
    @Bean
    RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(15))
                .setReadTimeout(Duration.ofSeconds(60))
                .build();
    }
}
