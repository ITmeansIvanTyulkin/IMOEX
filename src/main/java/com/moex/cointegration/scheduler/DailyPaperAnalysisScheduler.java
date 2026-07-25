package com.moex.cointegration.scheduler;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.service.CointegrationAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ежедневный автопрогон: новые свечи → сигналы → paper open/hold/close с псевдо-PnL.
 * Включается {@code imoex.paper.auto-run-daily=true} (по умолчанию).
 */
@Component
public class DailyPaperAnalysisScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyPaperAnalysisScheduler.class);

    private final CointegrationAnalysisService analysisService;
    private final ImoexProperties properties;

    public DailyPaperAnalysisScheduler(
            CointegrationAnalysisService analysisService,
            ImoexProperties properties
    ) {
        this.analysisService = analysisService;
        this.properties = properties;
    }

    @Scheduled(cron = "${imoex.paper.daily-cron:0 5 19 * * MON-FRI}")
    public void runDailyPaperCycle() {
        if (!properties.paper().enabled() || !properties.paper().autoRunDailyEnabled()) {
            return;
        }
        try {
            log.info("Daily paper cycle: refresh candles + analysis + paper sync");
            analysisService.runFullAnalysis(true);
            log.info("Daily paper cycle finished");
        } catch (Exception ex) {
            log.error("Daily paper cycle failed", ex);
        }
    }
}
