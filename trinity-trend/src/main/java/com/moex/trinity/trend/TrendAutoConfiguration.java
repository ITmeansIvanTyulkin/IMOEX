package com.moex.trinity.trend;

import com.moex.trinity.marketdata.MarketDataFeed;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.List;

@AutoConfiguration
@ConditionalOnProperty(prefix = "imoex.strategies.trend", name = "enabled", havingValue = "true")
public class TrendAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(TrendPlaybookSettings.class)
    TrendPlaybookSettings trendPlaybookSettings(
            @Value("${imoex.strategies.trend.playbook:levels-profile-br-m5}") String playbookId,
            @Value("${imoex.strategies.trend.grid:MODERATE}") String grid,
            @Value("${imoex.strategies.trend.max-risk-pct-equity:1.0}") double maxRiskPct,
            @Value("${imoex.strategies.trend.br.zone-min-points:15}") double zoneMin,
            @Value("${imoex.strategies.trend.br.zone-max-points:20}") double zoneMax,
            @Value("${imoex.strategies.trend.br.stop-points:15}") double stopPts,
            @Value("${imoex.strategies.trend.br.tp1-points:25}") double tp1Pts,
            @Value("${imoex.strategies.trend.br.rub-per-point:7.0}") double rubPerPoint,
            @Value("${imoex.strategies.trend.one-setup-per-zone:true}") boolean oneSetupPerZone,
            @Value("${imoex.strategies.trend.unlock-distance-points:40}") double unlockDistancePoints,
            @Value("${imoex.strategies.trend.allow-zone-pad:false}") boolean allowZonePad,
            @Value("${imoex.strategies.trend.min-touch-count:3}") int minTouchCount,
            @Value("${imoex.strategies.trend.min-hvn-bands:2}") int minHvnBands,
            @Value("${imoex.strategies.trend.session-bias-bars:36}") int sessionBiasBars,
            @Value("${imoex.strategies.trend.session-bias-min-points:40}") double sessionBiasMinPoints,
            @Value("${imoex.strategies.trend.require-bounce-confirm:true}") boolean requireBounceConfirm,
            @Value("${imoex.strategies.trend.min-reward-risk:1.5}") double minRewardRisk,
            @Value("${imoex.strategies.trend.max-setups-per-day:2}") int maxSetupsPerDay,
            @Value("${imoex.strategies.trend.cooldown-bars-after-sl:12}") int cooldownBarsAfterSl,
            @Value("${imoex.strategies.trend.retest-arm-max-distance-points:10}") double retestArmMaxDistancePoints,
            @Value("${imoex.strategies.trend.trade-session-open:09:00}") String tradeSessionOpen,
            @Value("${imoex.strategies.trend.trade-session-close:23:50}") String tradeSessionClose,
            @Value("${imoex.strategies.trend.no-trade-after-open-minutes:40}") int noTradeAfterOpenMinutes,
            @Value("${imoex.strategies.trend.no-trade-before-close-minutes:40}") int noTradeBeforeCloseMinutes,
            @Value("${imoex.strategies.trend.htf-min-move-points:50}") double htfMinMovePoints,
            @Value("${imoex.strategies.trend.htf-slope-bars:24}") int htfSlopeBars,
            @Value("${imoex.strategies.trend.htf-require-agreement:true}") boolean htfRequireAgreement,
            @Value("${imoex.strategies.trend.counter-trend-size-fraction:0.6}") double counterTrendSizeFraction,
            @Value("${imoex.strategies.trend.counter-trend-min-reward-risk:2.0}") double counterTrendMinRewardRisk,
            @Value("${imoex.strategies.trend.counter-trend-bounce-only:true}") boolean counterTrendBounceOnly,
            @Value("${imoex.strategies.trend.counter-trend-max-distance-points:5}") double counterTrendMaxDistancePoints,
            @Value("${imoex.strategies.trend.counter-trend-require-confirm:true}") boolean counterTrendRequireConfirm,
            @Value("${imoex.strategies.trend.event-calendar-enabled:true}") boolean eventCalendarEnabled,
            @Value("${imoex.strategies.trend.event-calendar-file:data/trend-event-calendar.json}") String eventCalendarFile,
            @Value("${imoex.strategies.trend.event-block-minutes-before:45}") int eventBlockMinutesBefore,
            @Value("${imoex.strategies.trend.event-block-minutes-after:30}") int eventBlockMinutesAfter,
            @Value("${imoex.strategies.trend.a-setup-bounce-only:true}") boolean aSetupBounceOnly,
            @Value("${imoex.strategies.trend.initial-size-fraction:0.4}") double initialSizeFraction,
            @Value("${imoex.strategies.trend.prefer-marketdata-zones:true}") boolean preferMarketDataZones
    ) {
        LimitGridStyle style;
        try {
            style = LimitGridStyle.valueOf(grid.trim().toUpperCase());
        } catch (Exception ex) {
            style = LimitGridStyle.MODERATE;
        }
        TrendInstrumentSpec br = TrendInstrumentSpec.br(zoneMin, zoneMax, stopPts, tp1Pts, rubPerPoint);
        TrendPlaybookSettings defaults = TrendPlaybookSettings.brDefaults();
        return new TrendPlaybookSettings(
                playbookId == null || playbookId.isBlank() ? LevelsProfileBrPlaybook.ID : playbookId.trim(),
                style,
                maxRiskPct,
                br,
                defaults.tp1Fraction(),
                defaults.touchLookback(),
                defaults.candlesPerTouch(),
                defaults.confirmBarsAfterBreak(),
                defaults.levelLookbackBars(),
                oneSetupPerZone,
                unlockDistancePoints,
                allowZonePad,
                minTouchCount,
                minHvnBands,
                sessionBiasBars,
                sessionBiasMinPoints,
                requireBounceConfirm,
                minRewardRisk,
                maxSetupsPerDay,
                cooldownBarsAfterSl,
                retestArmMaxDistancePoints,
                tradeSessionOpen,
                tradeSessionClose,
                noTradeAfterOpenMinutes,
                noTradeBeforeCloseMinutes,
                htfMinMovePoints,
                htfSlopeBars,
                htfRequireAgreement,
                counterTrendSizeFraction,
                counterTrendMinRewardRisk,
                counterTrendBounceOnly,
                counterTrendMaxDistancePoints,
                counterTrendRequireConfirm,
                eventCalendarEnabled,
                eventCalendarFile,
                eventBlockMinutesBefore,
                eventBlockMinutesAfter,
                aSetupBounceOnly,
                initialSizeFraction,
                preferMarketDataZones
        );
    }

    @Bean
    @ConditionalOnMissingBean(TrendPlaybook.class)
    TrendPlaybook levelsProfileBrPlaybook(
            TrendPlaybookSettings settings,
            ObjectProvider<MarketDataFeed> marketDataFeed
    ) {
        return new LevelsProfileBrPlaybook(settings, marketDataFeed.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(TrendEventCalendar.class)
    TrendEventCalendar trendEventCalendar(TrendPlaybookSettings settings) {
        return TrendEventCalendar.fromSettings(settings);
    }

    @Bean
    @ConditionalOnMissingBean(TrendRegimeSelector.class)
    TrendRegimeSelector trendRegimeSelector() {
        return new DefaultTrendRegimeSelector();
    }

    @Bean
    @ConditionalOnMissingBean(TrendResearchService.class)
    TrendResearchService trendResearchService(List<TrendPlaybook> playbooks, TrendRegimeSelector selector) {
        return new TrendResearchService(playbooks, selector);
    }

    @Bean
    @ConditionalOnMissingBean(TrendRobotEngine.class)
    TrendRobotEngine trendRobotEngine(
            TrendPlaybook playbook,
            TrendPlaybookSettings settings,
            TrendEventCalendar eventCalendar
    ) {
        return new TrendRobotEngine(playbook, settings, eventCalendar);
    }
}
