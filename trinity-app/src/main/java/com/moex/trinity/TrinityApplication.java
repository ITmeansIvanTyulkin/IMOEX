package com.moex.trinity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * TRINITY operator application — wires pairs / trend / calendar-arb modules.
 */
@SpringBootApplication(scanBasePackages = {
        "com.moex.trinity",
        "com.moex.cointegration"
})
@ConfigurationPropertiesScan(basePackages = {
        "com.moex.trinity",
        "com.moex.cointegration"
})
@EnableScheduling
public class TrinityApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrinityApplication.class, args);
    }
}
