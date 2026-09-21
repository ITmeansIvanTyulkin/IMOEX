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
    private final TrendTapeHandshakeInterceptor handshake;

    public TrendTapeWebSocketConfig(
            TrendTapeWebSocketHandler handler,
            TrendTapeHandshakeInterceptor handshake
    ) {
        this.handler = handler;
        this.handshake = handshake;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/trend/ws/tape")
                .addInterceptors(handshake)
                .setAllowedOriginPatterns(
                        "http://127.0.0.1:*",
                        "http://localhost:*",
                        "https://127.0.0.1:*",
                        "https://localhost:*"
                );
    }
}
