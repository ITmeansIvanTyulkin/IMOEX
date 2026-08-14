package com.moex.trinity.shell;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import com.moex.trinity.shared.TrinityCore;

/**
 * Public evaluation shell. Trading kernel is not on this classpath.
 */
@SpringBootApplication
public class TrinityBonesApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrinityBonesApplication.class, args);
    }

    @Bean
    TrinityCore trinityCore() {
        return TrinityCore.bones();
    }
}
