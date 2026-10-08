package com.moex.cointegration.controller;

import com.moex.trinity.dualclass.DualClassDeskService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/dual-class")
@ConditionalOnBean(DualClassDeskService.class)
public class DualClassController {

    private final DualClassDeskService desk;

    public DualClassController(DualClassDeskService desk) {
        this.desk = desk;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return desk.view();
    }

    @GetMapping("/desk")
    public Map<String, Object> desk() {
        return desk.desk();
    }

    @GetMapping("/settings")
    public Map<String, Object> settings() {
        return desk.view();
    }

    @PostMapping("/settings/auto-execution")
    public Map<String, Object> toggleAuto(@RequestBody(required = false) ToggleBody body) {
        boolean next = body != null && body.enabled() != null
                ? body.enabled()
                : !desk.autoExecution();
        return desk.setAutoExecution(next);
    }

    public record ToggleBody(Boolean enabled) {
    }
}
