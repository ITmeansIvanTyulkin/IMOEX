package com.moex.cointegration.config;

import com.moex.trinity.marketdata.TapeTickBus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TapeTickBusConfiguration {

    @Bean
    @ConditionalOnMissingBean(TapeTickBus.class)
    TapeTickBus tapeTickBus() {
        return new TapeTickBus();
    }
}
