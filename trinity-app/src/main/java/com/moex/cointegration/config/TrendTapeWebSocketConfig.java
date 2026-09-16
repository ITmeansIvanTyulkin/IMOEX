package com.moex.cointegration.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@ConditionalOnProperty(prefix = "imoex.strategies.trend", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TrendTapeWebSocketConfig implements WebSocketConfigurer {

    private final TrendTapeWebSocketHandler handler;

    public TrendTapeWebSocketConfig(TrendTapeWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/trend/ws/tape")
                .setAllowedOriginPatterns("*");
    }
}
