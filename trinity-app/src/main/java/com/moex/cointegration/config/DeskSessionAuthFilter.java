package com.moex.cointegration.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Turns the HttpOnly {@code trinity.desk} cookie into a Spring Authentication so
 * browser document navigation to {@code /view/**} works without an Authorization header.
 */
public class DeskSessionAuthFilter extends OncePerRequestFilter {

    private final DeskSessionStore sessions;

    public DeskSessionAuthFilter(DeskSessionStore sessions) {
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        var current = SecurityContextHolder.getContext().getAuthentication();
        boolean empty = current == null
                || !current.isAuthenticated()
                || current instanceof AnonymousAuthenticationToken;
        if (empty) {
            String id = sessions.idOf(request);
            if (sessions.valid(id)) {
                var auth = new UsernamePasswordAuthenticationToken(
                        sessions.email(id),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))
                );
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        filterChain.doFilter(request, response);
    }
}
