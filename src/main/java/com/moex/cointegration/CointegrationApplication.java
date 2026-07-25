package com.moex.cointegration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Точка входа Spring Boot-приложения для поиска коинтегрированных пар акций IMOEX.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class CointegrationApplication {

    /**
     * Запускает встроенный сервер и контекст Spring.
     *
     * @param args аргументы командной строки
     */
    public static void main(String[] args) {
        SpringApplication.run(CointegrationApplication.class, args);
    }
}
