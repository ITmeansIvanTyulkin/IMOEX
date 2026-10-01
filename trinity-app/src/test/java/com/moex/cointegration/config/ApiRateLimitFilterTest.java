package com.moex.cointegration.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiRateLimitFilterTest {

    @Test
    void loginWindowCapsBurst() {
        ApiRateLimitFilter filter = new ApiRateLimitFilter(new DeskSessionStore());
        String key = "test-ip|login";
        for (int i = 0; i < ApiRateLimitFilter.LOGIN_MAX; i++) {
            assertTrue(filter.allow(key, ApiRateLimitFilter.LOGIN_MAX));
        }
        assertFalse(filter.allow(key, ApiRateLimitFilter.LOGIN_MAX));
    }

    @Test
    void deskPollNotCappedWhenBearerPresent() throws Exception {
        ApiRateLimitFilter filter = new ApiRateLimitFilter(new DeskSessionStore());
        FilterChain chain = (request, response) -> ((HttpServletResponse) response).setStatus(200);
        for (int i = 0; i < ApiRateLimitFilter.DESK_MAX + 20; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/trend/desk");
            req.addHeader("Authorization", "Bearer test");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, chain);
            assertEquals(200, res.getStatus(), "authenticated desk poll must not 429");
        }
    }

    @Test
    void deskPollCappedWhenAnonymous() throws Exception {
        ApiRateLimitFilter filter = new ApiRateLimitFilter(new DeskSessionStore());
        FilterChain chain = (request, response) -> ((HttpServletResponse) response).setStatus(200);
        for (int i = 0; i < ApiRateLimitFilter.DESK_MAX; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/trend/desk");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, chain);
            assertEquals(200, res.getStatus());
        }
        MockHttpServletRequest extra = new MockHttpServletRequest("GET", "/api/trend/desk");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(extra, res, chain);
        assertEquals(429, res.getStatus());
    }

    @Test
    void deskPollNotCappedWhenSessionCookieValid() throws Exception {
        DeskSessionStore store = new DeskSessionStore();
        var cookie = store.issueCookie("op@example.com", Duration.ofMinutes(30));
        ApiRateLimitFilter filter = new ApiRateLimitFilter(store);
        FilterChain chain = (request, response) -> ((HttpServletResponse) response).setStatus(200);
        for (int i = 0; i < ApiRateLimitFilter.DESK_MAX + 5; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/trend/desk");
            req.setCookies(new jakarta.servlet.http.Cookie(DeskSessionStore.COOKIE_NAME, cookie.getValue()));
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, chain);
            assertEquals(200, res.getStatus());
        }
    }
}
