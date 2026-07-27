package com.moex.cointegration.scheduler;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.service.CointegrationAnalysisService;
import com.moex.cointegration.service.PaperAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Часовой автопрогон INTRADAY: refresh 1H → tech → paper (без FA).
 * По умолчанию пн–пт в :05 каждого часа 10–18 (после закрытия часового бара).
 */
@Component
public class IntradayPaperAnalysisScheduler {

    private static final Logger log = LoggerFactory.getLogger(IntradayPaperAnalysisScheduler.class);

    private final CointegrationAnalysisService analysisService;
    private final PaperAlertService alertService;
    private final ImoexProperties properties;

    public IntradayPaperAnalysisScheduler(
            CointegrationAnalysisService analysisService,
            PaperAlertService alertService,
            ImoexProperties properties
    ) {
        this.analysisService = analysisService;
        this.alertService = alertService;
        this.properties = properties;
    }

    @Scheduled(cron = "${imoex.paper.intraday-cron:0 5 10-18 * * MON-FRI}")
    public void runIntradayPaperCycle() {
        if (!properties.paper().enabled() || !properties.paper().autoRunIntradayEnabled()) {
            return;
        }
        alertService.markIntradayRunStarted();
        try {
            log.info("Intraday paper cycle: refresh 1H + INTRADAY analysis + paper sync");
            analysisService.runIntradayOnly(true);
            alertService.markIntradayRunFinished(true, null);
            log.info("Intraday paper cycle finished");
        } catch (Exception ex) {
            alertService.markIntradayRunFinished(false, ex.getMessage());
            log.error("Intraday paper cycle failed", ex);
        }
    }
}
