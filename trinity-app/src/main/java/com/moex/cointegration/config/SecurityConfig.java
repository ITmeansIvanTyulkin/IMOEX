package com.moex.cointegration.config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Ops/auth: при {@code imoex.auth.enabled=true} POST /api/** требует HTTP Basic
 * и/или Bearer JWT (Supabase), если {@code imoex.auth.supabase.enabled=true}.
 * GET API, HTML view и actuator health остаются открытыми.
 *
 * <p>401 без {@code WWW-Authenticate: Basic} — иначе Chrome показывает native Basic-диалог
 * и пользователь вводит email кабинета туда вместо формы на /view.
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
        boolean supabaseJwt = properties.auth().supabase().jwtConfigured();
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(EndpointRequest.to("health", "info")).permitAll()
                        .requestMatchers("/", "/view", "/view/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/**").permitAll()
                        /* same-origin cabinet login proxy (before authenticated POST /api/**) */
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/**").permitAll()
                        /* soft commercial beacons — не должны триггерить login prompt */
                        .requestMatchers(HttpMethod.POST, "/api/upsell/events").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/**").authenticated()
                        .anyRequest().permitAll()
                )
                .httpBasic(basic -> basic.authenticationEntryPoint(apiAuthEntryPoint(supabaseJwt)))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(apiAuthEntryPoint(supabaseJwt)));

        if (supabaseJwt) {
            http.oauth2ResourceServer(oauth2 -> oauth2
                    .jwt(Customizer.withDefaults())
                    .authenticationEntryPoint(apiAuthEntryPoint(true)));
        }
        return http.build();
    }

    /**
     * Accept Basic/Bearer, but never advertise Basic via WWW-Authenticate (SPA /view login).
     */
    private static AuthenticationEntryPoint apiAuthEntryPoint(boolean supabaseJwt) {
        return (request, response, authException) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            if (supabaseJwt) {
                /* Hint for clients only — browsers do not treat Bearer as a native login prompt. */
                response.setHeader("WWW-Authenticate", "Bearer");
            }
            response.getWriter().write(
                    "{\"error\":\"unauthorized\",\"message\":\"Use Supabase Bearer (cabinet email/password on /view) or HTTP Basic operator.\"}"
            );
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "imoex.auth.supabase", name = "enabled", havingValue = "true")
    JwtDecoder supabaseJwtDecoder(ImoexProperties properties) {
        var sb = properties.auth().supabase();
        if (!sb.jwtConfigured()) {
            throw new IllegalStateException(
                    "imoex.auth.supabase.enabled=true, but url is empty. "
                            + "Set imoex.auth.supabase.url (https://PROJECT.supabase.co)."
            );
        }

        List<JwtDecoder> decoders = new ArrayList<>();
        /* Primary: ES256 signing keys (current Supabase Auth).
         * withJwkSetUri() defaults to RS256 only — must allow ES256 explicitly. */
        decoders.add(
                NimbusJwtDecoder.withJwkSetUri(sb.jwksUri())
                        .jwsAlgorithm(SignatureAlgorithm.ES256)
                        .build()
        );

        String secret = sb.jwtSecret();
        if (secret != null && !secret.isBlank()) {
            /* Optional legacy HS256 JWT secret. */
            SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            decoders.add(NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build());
        }

        return token -> {
            JwtException last = null;
            for (JwtDecoder decoder : decoders) {
                try {
                    return decoder.decode(token);
                } catch (JwtException ex) {
                    last = ex;
                }
            }
            String detail = last != null && last.getMessage() != null ? last.getMessage() : "unknown";
            throw new BadJwtException("Unable to decode Supabase JWT: " + detail, last);
        };
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
