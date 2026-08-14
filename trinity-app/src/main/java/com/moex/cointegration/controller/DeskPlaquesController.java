package com.moex.cointegration.controller;

import com.moex.cointegration.service.DeskPlaquesService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Global floating plaques for the operator dashboard.
 */
@RestController
@RequestMapping("/api/desk")
public class DeskPlaquesController {

    private final DeskPlaquesService plaques;

    public DeskPlaquesController(DeskPlaquesService plaques) {
        this.plaques = plaques;
    }

    /**
     * @param winds {@code active} / {@code 1} — current instrument;
     *              {@code all} — catalog; or CSV of SECIDs. Empty — robots only.
     */
    @GetMapping("/plaques")
    public Map<String, Object> plaques(
            @RequestParam(value = "winds", required = false) String winds
    ) {
        return plaques.plaques(winds);
    }
}
