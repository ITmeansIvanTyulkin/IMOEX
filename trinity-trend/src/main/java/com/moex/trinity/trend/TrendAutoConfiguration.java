package com.moex.trinity.trend;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.List;

@AutoConfiguration
@ConditionalOnProperty(prefix = "imoex.strategies.trend", name = "enabled", havingValue = "true")
public class TrendAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(TrendPlaybook.class)
    TrendPlaybook placeholderTrendPlaybook() {
        return new PlaceholderTrendPlaybook();
    }

    @Bean
    @ConditionalOnMissingBean(TrendRegimeSelector.class)
    TrendRegimeSelector trendRegimeSelector() {
        return new DefaultTrendRegimeSelector();
    }

    @Bean
    @ConditionalOnMissingBean(TrendResearchService.class)
    TrendResearchService trendResearchService(List<TrendPlaybook> playbooks, TrendRegimeSelector selector) {
        return new TrendResearchService(playbooks, selector);
    }
}
