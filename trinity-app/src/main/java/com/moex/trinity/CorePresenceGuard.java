package com.moex.trinity;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * trinity-app is the operator binary. Without IMOEX-core on the classpath it must not
 * pretend to trade. Public clones should run {@code trinity-shell} instead.
 */
@Component
public class CorePresenceGuard {

    private static final Logger log = LoggerFactory.getLogger(CorePresenceGuard.class);

    private static final String[] KERNEL_CLASSES = {
            "com.moex.trinity.trend.LevelsProfileBrPlaybook",
            "com.moex.trinity.trend.ExclusiveSide",
            "com.moex.trinity.trend.FairPaperSimulator",
            "com.moex.trinity.trend.PositionalVolumeH1Playbook",
            "com.moex.trinity.trend.PositionalSide",
            "com.moex.cointegration.quant.EngleGrangerTest",
            "com.moex.cointegration.config.CapitalAllocator",
            "com.moex.trinity.calendararb.CalendarArbPlaybook",
            "com.moex.cointegration.service.TrendDeskService",
            "com.moex.cointegration.service.TrendFairPaperLiveService"
    };

    private final Environment environment;

    public CorePresenceGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void requireTradingKernel() {
        if (isTestLikeProfile()) {
            log.debug("Core presence guard skipped (test/profile bypass).");
            return;
        }
        for (String name : KERNEL_CLASSES) {
            try {
                Class.forName(name);
            } catch (ClassNotFoundException ex) {
                throw new IllegalStateException("""
                        Торговое ядро не на classpath (%s).
                        Публичный клон IMOEX — это Кости (оценка продукта), не робот.
                        Оператор: соседняя папка ../IMOEX-core и mvn -pl trinity-app -am spring-boot:run
                        Оценка: mvn -pl trinity-shell -am spring-boot:run
                        """.formatted(name).stripIndent().trim());
            }
        }
        log.info("TRINITY core present (playbooks + pairs + calendar-arb).");
    }

    private boolean isTestLikeProfile() {
        for (String p : environment.getActiveProfiles()) {
            if ("test".equalsIgnoreCase(p) || "ci".equalsIgnoreCase(p)) {
                return true;
            }
        }
        return "true".equalsIgnoreCase(environment.getProperty("imoex.run.skip-guard", "false"));
    }
}
