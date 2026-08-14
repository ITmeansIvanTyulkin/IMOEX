package com.moex.trinity;

import com.moex.trinity.shared.TrinityCore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TrinityCoreConfiguration {

    @Bean
    TrinityCore trinityCore() {
        return TrinityCore.operator();
    }
}
