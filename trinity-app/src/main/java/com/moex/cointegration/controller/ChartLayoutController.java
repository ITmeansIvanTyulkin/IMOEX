package com.moex.cointegration.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moex.cointegration.service.ChartLayoutService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chart layouts for desk + multi-instrument terminal — keyed by operator login.
 */
@RestController
@RequestMapping("/api/charts")
public class ChartLayoutController {

    private final ChartLayoutService layouts;

    public ChartLayoutController(ChartLayoutService layouts) {
        this.layouts = layouts;
    }

    @GetMapping("/layouts")
    public ObjectNode get(
            @RequestHeader(value = "X-Trinity-User", required = false) String headerUser
    ) {
        return layouts.load(resolveUser(headerUser));
    }

    @PutMapping("/layouts")
    public ResponseEntity<?> put(
            @RequestHeader(value = "X-Trinity-User", required = false) String headerUser,
            @RequestBody JsonNode body
    ) {
        if (body == null || body.isNull()) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "body required"));
        }
        return ResponseEntity.ok(layouts.save(resolveUser(headerUser), body));
    }

    private static String resolveUser(String headerUser) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && auth.getName() != null
                && !"anonymousUser".equalsIgnoreCase(auth.getName())) {
            return auth.getName();
        }
        if (headerUser != null && !headerUser.isBlank()) {
            return headerUser.trim();
        }
        return "anonymous";
    }
}
