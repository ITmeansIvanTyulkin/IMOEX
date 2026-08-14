package com.moex.cointegration.controller;

import com.moex.trinity.shared.TrinityCore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/core")
public class CoreStatusController {

    private final TrinityCore core;

    public CoreStatusController(TrinityCore core) {
        this.core = core;
    }

    @GetMapping
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("present", core.present());
        m.put("edition", core.edition());
        m.put("message", core.message());
        m.put("tradingKernel", core.present());
        return m;
    }
}
