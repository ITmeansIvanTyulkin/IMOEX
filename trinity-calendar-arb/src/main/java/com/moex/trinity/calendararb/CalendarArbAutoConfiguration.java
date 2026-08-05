package com.moex.trinity.calendararb;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnProperty(prefix = "imoex.strategies.calendar-arb", name = "enabled", havingValue = "true")
public class CalendarArbAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(CalendarArbResearchService.class)
    CalendarArbResearchService calendarArbResearchService() {
        return new CalendarArbResearchService();
    }
}
