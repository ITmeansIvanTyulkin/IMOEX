package com.moex.cointegration.config;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Ops/auth: при {@code imoex.auth.enabled=true} POST /api/** требует HTTP Basic
 * и/или Bearer JWT (Supabase), если {@code imoex.auth.supabase.enabled=true}.
 * GET API, HTML view и actuator health остаются открытыми.
 */
@Configuration
public class SecurityConfig {

    @Bean
    @Order(1)
    @ConditionalOnProperty(prefix = "imoex.auth", name = "enabled", havingValue = "false", matchIfMissing = true)
    SecurityFilterChain openSecurity(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(1)
    @ConditionalOnProperty(prefix = "imoex.auth", name = "enabled", havingValue = "true")
    SecurityFilterChain securedSecurity(HttpSecurity http, ImoexProperties properties) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(EndpointRequest.to("health", "info")).permitAll()
                        .requestMatchers("/", "/view", "/view/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/**").authenticated()
                        .anyRequest().permitAll()
                )
                .httpBasic(Customizer.withDefaults());

        if (properties.auth().supabase().jwtConfigured()) {
            http.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        }
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "imoex.auth.supabase", name = "enabled", havingValue = "true")
    JwtDecoder supabaseJwtDecoder(ImoexProperties properties) {
        String secret = properties.auth().supabase().jwtSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "imoex.auth.supabase.enabled=true, but jwt-secret is empty. "
                            + "Set imoex.auth.supabase.jwt-secret (Supabase Project Settings → API → JWT Secret)."
            );
        }
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "imoex.auth", name = "enabled", havingValue = "true")
    UserDetailsService userDetailsService(ImoexProperties properties, PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(
                User.builder()
                        .username(properties.auth().username())
                        .password(encoder.encode(properties.auth().password()))
                        .roles("OPERATOR")
                        .build()
        );
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
