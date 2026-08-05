package com.moex.cointegration.scheduler;

import com.moex.cointegration.config.ScheduleProperties;
import com.moex.cointegration.service.CointegrationAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Планировщик еженедельного пересчёта коинтегрированных пар (опционально, через конфиг).
 */
@Component
public class WeeklyAnalysisScheduler {

    private static final Logger log = LoggerFactory.getLogger(WeeklyAnalysisScheduler.class);

    private final CointegrationAnalysisService analysisService;
    private final ScheduleProperties scheduleProperties;

    public WeeklyAnalysisScheduler(
            CointegrationAnalysisService analysisService,
            ScheduleProperties scheduleProperties
    ) {
        this.analysisService = analysisService;
        this.scheduleProperties = scheduleProperties;
    }

    /**
     * Cron-задача: при {@code analysis.schedule.enabled=true} обновляет данные с MOEX
     * и запускает полный анализ коинтеграции.
     */
    @Scheduled(cron = "${analysis.schedule.cron:0 0 6 * * SUN}")
    public void runWeeklyAnalysis() {
        if (!scheduleProperties.enabled()) {
            return;
        }
        try {
            log.info("Starting scheduled weekly cointegration analysis");
            analysisService.runFullAnalysis(true);
            log.info("Scheduled weekly analysis finished");
        } catch (Exception ex) {
            log.error("Scheduled analysis failed", ex);
        }
    }
}
