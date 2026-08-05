package com.moex.cointegration.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moex.cointegration.config.ImoexProperties;

/**
 * Public auth mode for operator UI (no secrets except anon key, which is designed public).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthModeController {

    private final ImoexProperties properties;

    public AuthModeController(ImoexProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/mode")
    public Map<String, Object> mode() {
        var auth = properties.auth();
        var sb = auth.supabase();
        Map<String, Object> supabase = new LinkedHashMap<>();
        supabase.put("enabled", sb.enabled());
        supabase.put("configured", sb.jwtConfigured());
        supabase.put("url", sb.url() == null ? "" : sb.url());
        /* anon key is public by design (RLS); jwt-secret never exposed */
        supabase.put("anonKey", sb.anonKey() == null ? "" : sb.anonKey());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("basicEnabled", auth.enabled());
        out.put("supabase", supabase);
        out.put(
                "note",
                "Same email/password as TRINITY cabinet when Supabase is enabled. "
                        + "Boot unlock (imoex.run.unlock) is separate from user login."
        );
        return out;
    }
}
