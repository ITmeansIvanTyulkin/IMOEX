package com.moex.cointegration.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Allow TRINITY marketing cabinet (static landing) to read paper/regime over HTTP.
 * GET-only; no credentials. Trading UI remains same-origin /view.
 */
@Configuration
public class LandingCorsConfig implements WebMvcConfigurer {

    private static final String[] LANDING_ORIGINS = {
            "http://127.0.0.1:5173",
            "http://localhost:5173",
            "https://trinity.trading",
            "https://www.trinity.trading"
    };

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/paper/**")
                .allowedOrigins(LANDING_ORIGINS)
                .allowedMethods("GET")
                .maxAge(3600);
        registry.addMapping("/api/analysis/regime")
                .allowedOrigins(LANDING_ORIGINS)
                .allowedMethods("GET")
                .maxAge(3600);
    }
}
