package com.moex.cointegration.controller;

import com.moex.cointegration.service.CalendarArbDeskService;
import com.moex.cointegration.service.CalendarArbFairPaperLiveService;
import com.moex.cointegration.service.CalendarArbPaperJournalService;
import com.moex.cointegration.service.CalendarArbSettingsService;
import com.moex.trinity.calendararb.CalendarArbResearchService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/calendar-arb")
@ConditionalOnProperty(prefix = "imoex.strategies.calendar-arb", name = "enabled", havingValue = "true")
public class CalendarArbController {

    private final CalendarArbResearchService research;
    private final CalendarArbDeskService desk;
    private final CalendarArbSettingsService settings;
    private final CalendarArbPaperJournalService journal;
    private final CalendarArbFairPaperLiveService fairPaper;

    public CalendarArbController(
            CalendarArbResearchService research,
            CalendarArbDeskService desk,
            CalendarArbSettingsService settings,
            CalendarArbPaperJournalService journal,
            CalendarArbFairPaperLiveService fairPaper
    ) {
        this.research = research;
        this.desk = desk;
        this.settings = settings;
        this.journal = journal;
        this.fairPaper = fairPaper;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", true);
        m.put("message", research.statusMessage());
        m.put("dataSource", research.market().providerId());
        m.putAll(settings.view());
        m.put("fairPaper", fairPaper.snapshot());
        m.put("statement", journal.statement());
        m.put("families", desk.familyCatalog());
        return m;
    }

    @GetMapping("/desk")
    public Map<String, Object> desk(
            @RequestParam(name = "family", required = false) String family,
            @RequestParam(name = "structure", required = false) String structure
    ) {
        return desk.desk(family, structure);
    }

    @GetMapping("/settings")
    public Map<String, Object> settings() {
        return settings.view();
    }

    @PostMapping("/settings")
    public Map<String, Object> saveSettings(@RequestBody(required = false) CalendarArbSettingsService.UpdateRequest body) {
        return settings.save(body);
    }

    @PostMapping("/settings/auto-execution")
    public Map<String, Object> toggleAuto(@RequestBody(required = false) ToggleBody body) {
        boolean next = body != null && body.enabled() != null
                ? body.enabled()
                : !settings.autoExecution();
        return settings.setAutoExecution(next);
    }

    public record ToggleBody(Boolean enabled) {
    }
}
