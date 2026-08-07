package com.moex.cointegration.web;

import com.moex.cointegration.config.CapitalProperties;
import com.moex.cointegration.upsell.UpsellAccess;
import com.moex.cointegration.upsell.UpsellService;
import com.moex.cointegration.model.AnalysisReport;
import com.moex.cointegration.model.FinalTradeDecision;
import com.moex.cointegration.model.FinalTradeRecommendation;
import com.moex.cointegration.model.MarketRegimeSnapshot;
import com.moex.cointegration.model.NewsTriggerHit;
import com.moex.cointegration.model.PairAnalysisResult;
import com.moex.cointegration.model.PaperJournal;
import com.moex.cointegration.model.PaperTradeEntry;
import com.moex.cointegration.model.RssHeadline;
import com.moex.cointegration.model.TradingRecommendation;
import com.moex.cointegration.model.TradingSignal;
import com.moex.cointegration.model.WalkForwardReport;
import com.moex.cointegration.service.RssHeadlineService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Формирует HTML-страницы с таблицами для просмотра в браузере.
 */
@Component
public class AnalysisHtmlRenderer {

    private final UpsellService upsellService;
    private final CapitalProperties capitalProperties;
    private final boolean strategyPairsEnabled;
    private final boolean strategyTrendEnabled;
    private final boolean strategyCalendarArbEnabled;

    public AnalysisHtmlRenderer(
            UpsellService upsellService,
            CapitalProperties capitalProperties,
            @Value("${imoex.strategies.pairs.enabled:true}") boolean strategyPairsEnabled,
            @Value("${imoex.strategies.trend.enabled:false}") boolean strategyTrendEnabled,
            @Value("${imoex.strategies.calendar-arb.enabled:false}") boolean strategyCalendarArbEnabled
    ) {
        this.upsellService = upsellService;
        this.capitalProperties = capitalProperties;
        this.strategyPairsEnabled = strategyPairsEnabled;
        this.strategyTrendEnabled = strategyTrendEnabled;
        this.strategyCalendarArbEnabled = strategyCalendarArbEnabled;
    }

    private static final String PAGE_TEMPLATE = """
            <!DOCTYPE html>
            <html lang="ru">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>{{TITLE}}</title>
              <link rel="preconnect" href="https://fonts.googleapis.com">
              <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
              <link href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;600&family=IBM+Plex+Sans:wght@400;500;600;700&display=swap" rel="stylesheet">
              <link rel="stylesheet" href="/css/operator.css?v=20260807-dom-light-pad">
            </head>
            <body data-upsell="{{UPSELL}}" data-upsell-phase="{{UPSELL_PHASE}}">
              <header class="site-header">
                <div class="brand-row">
                  <div class="trinity-logo" aria-hidden="true">
                    <span class="ring ring-a"></span>
                    <span class="ring ring-b"></span>
                    <span class="ring ring-c"></span>
                  </div>
                  <div class="brand-text">
                    <h1 class="brand">TRINITY</h1>
                    <p class="brand-sub">Multi-Strategy Arbitrage</p>
                  </div>
                </div>
                <p class="tagline">Three Strategies. One Mission.</p>
              </header>
              {{NAV}}
              <div id="auth-session-bar" class="auth-session-bar" hidden></div>
              <main>
                {{OPS}}
                {{BODY}}
                <div id="trinity-toast-stack" class="toast-stack" aria-live="assertive"></div>
                <div id="trinity-upsell-host" class="upsell-host" aria-live="polite"></div>
                <p class="footnote">TRINITY — research / decision-support. Не индивидуальная инвестиционная рекомендация. Paper PnL — research-метрика (qty×цена, не брокерский отчёт).</p>
              </main>
              <div id="trinity-auth-gate" class="trinity-auth-gate" hidden aria-hidden="true">
                <canvas id="trinity-auth-canvas" class="trinity-auth-canvas" aria-hidden="true"></canvas>
                <div class="trinity-auth-veil"></div>
                <div class="trinity-auth-stage">
                  <div class="trinity-auth-modal" id="trinity-auth-modal" role="dialog" aria-modal="true" aria-labelledby="trinity-auth-title">
                    <div class="trinity-auth-brand">
                      <div class="trinity-logo trinity-logo-lg" aria-hidden="true">
                        <span class="ring ring-a"></span>
                        <span class="ring ring-b"></span>
                        <span class="ring ring-c"></span>
                      </div>
                      <p class="trinity-auth-eyebrow">Operator desk</p>
                      <h2 id="trinity-auth-title" class="trinity-auth-title">TRINITY</h2>
                      <p class="trinity-auth-lead">Три стратегии. Один пульт. Войдите аккаунтом кабинета.</p>
                    </div>
                    <form id="trinity-auth-form" class="trinity-auth-form" autocomplete="on">
                      <div class="field">
                        <label for="gate-user">Email</label>
                        <input id="gate-user" name="email" type="email" autocomplete="username" spellcheck="false" placeholder="you@example.com" required>
                      </div>
                      <div class="field">
                        <label for="gate-pass">Пароль</label>
                        <input id="gate-pass" name="password" type="password" autocomplete="current-password" required>
                      </div>
                      <p id="trinity-auth-error" class="trinity-auth-error" hidden></p>
                      <button type="submit" class="btn btn-primary trinity-auth-submit" id="gate-login-btn">Войти в платформу</button>
                    </form>
                    <p class="trinity-auth-foot">Тот же email и пароль, что в кабинете TRINITY.</p>
                  </div>
                  <div class="trinity-welcome" id="trinity-welcome" hidden aria-live="polite">
                    <div class="trinity-logo trinity-logo-xl" aria-hidden="true">
                      <span class="ring ring-a"></span>
                      <span class="ring ring-b"></span>
                      <span class="ring ring-c"></span>
                    </div>
                    <p class="trinity-welcome-kicker">Сессия открыта</p>
                    <h2 class="trinity-welcome-title">Добро пожаловать в TRINITY!</h2>
                    <p class="trinity-welcome-copy">
                      Три стратегии + самообучаемый искусственный интеллект в одной платформе —
                      ваш билет в мир автоматической торговли
                    </p>
                  </div>
                </div>
              </div>
              <script src="/js/operator.js?v=20260805-trend-switch"></script>
            </body>
            </html>
            """;

    private enum OpsMode {
        /** Полный пульт — только на /view/settings. */
        SETTINGS,
        COMPACT,
        NONE
    }

    private String opsPanel() {
        return """
                <section class="ops-panel" id="ops-panel">
                  <h2>Пульт оператора</h2>
                  <p class="ops-lead">
                    Вход один раз здесь (email/пароль кабинета TRINITY). На остальных страницах
                    сессия уже из браузера — поля логина не дублируются.
                  </p>
                  <div class="alert-prefs">
                    <label class="check-label"><input type="checkbox" id="ops-alerts-enabled" checked> Алерты при новой paper-сделке</label>
                    <label class="check-label"><input type="checkbox" id="ops-alerts-sound" checked> Звук</label>
                    <button type="button" class="btn btn-ghost" id="ops-notify-permission">Уведомления macOS / Windows</button>
                  </div>
                  <p class="meta alert-hint">Баннер справа сверху в браузере + системное уведомление (если разрешено).</p>
                  <div class="busy-bar" id="ops-busy"></div>
                  <div class="ops-grid">
                    <div>
                      <div class="auth-row">
                        <div class="field">
                          <label for="ops-user">Email (как в кабинете TRINITY)</label>
                          <input id="ops-user" type="email" autocomplete="username" spellcheck="false" placeholder="you@example.com">
                        </div>
                        <div class="field">
                          <label for="ops-pass">Пароль кабинета</label>
                          <input id="ops-pass" type="password" autocomplete="current-password">
                        </div>
                        <button type="button" class="btn btn-ghost" id="ops-save-creds">Войти</button>
                      </div>
                      <div class="ops-actions">
                        <button type="button" class="btn btn-primary" data-ops-action="run-fast">Анализ + paper</button>
                        <button type="button" class="btn btn-secondary" data-ops-action="run-full">Анализ + скачать свечи</button>
                        <button type="button" class="btn btn-ghost" data-ops-action="news-refresh">Только новости / paper</button>
                        <button type="button" class="btn btn-ghost" data-ops-action="walk-forward">Walk-forward</button>
                        <button type="button" class="btn btn-warn" data-ops-action="data-refresh">Скачать свечи</button>
                      </div>
                    </div>
                    <div>
                      <div class="status-box" id="ops-log" aria-live="polite"></div>
                    </div>
                  </div>
                </section>
                """;
    }

    private String compactOpsPanel() {
        return """
                <section class="ops-compact" id="ops-panel">
                  <div class="busy-bar" id="ops-busy"></div>
                  <p class="ops-compact-lead">
                    Быстрый запуск. Логин и консоль брокера — один раз в
                    <a href="/view/settings">Настройках</a>.
                  </p>
                  <div class="ops-compact-actions">
                    <button type="button" class="btn btn-primary" data-ops-action="run-fast">Анализ + paper</button>
                    <a class="btn btn-ghost" href="/view/settings">Настройки</a>
                  </div>
                </section>
                """;
    }

    /** Дискретная CTA на дашборде: без пульта и без консоли брокера. */
    private String dashboardQuietCta() {
        return """
                <section class="dash-cta" id="dash-cta">
                  <div class="busy-bar" id="ops-busy"></div>
                  <div class="dash-cta-copy">
                    <p class="dash-cta-label">Действие</p>
                    <p class="dash-cta-text">Обновить сигналы и paper-журнал. Брокер и алерты — в Настройках.</p>
                  </div>
                  <div class="dash-cta-actions">
                    <button type="button" class="btn btn-primary" data-ops-action="run-fast">Анализ + paper</button>
                    <a class="btn btn-ghost" href="/view/settings">Настройки</a>
                  </div>
                </section>
                """;
    }

    private String opsHtml(OpsMode mode) {
        return switch (mode) {
            case SETTINGS -> opsPanel();
            case COMPACT -> compactOpsPanel();
            case NONE -> "";
        };
    }

    /**
     * Главная страница: сводка, сигналы входа, топ-пары.
     */
    public String renderDashboard(
            AnalysisReport report,
            List<TradingRecommendation> recommendations,
            com.moex.cointegration.model.MarketRegimeSnapshot regime
    ) {
        List<TradingRecommendation> actionableSignals = recommendations.stream()
                .filter(r -> r.signal() == TradingSignal.LONG_SPREAD || r.signal() == TradingSignal.SHORT_SPREAD)
                .sorted((a, b) -> Double.compare(
                        Math.abs(b.currentZScore()),
                        Math.abs(a.currentZScore())))
                .toList();

        long actionable = actionableSignals.size();

        StringBuilder body = new StringBuilder();
        body.append("<div class=\"dash-shell\">");
        body.append(dashboardWidgetGrid(regime, report, actionable));
        body.append("""
                <aside class="next-steps" id="dash-next-steps">
                  <p class="next-steps-label">Что сделать сейчас</p>
                  <ol>
                    <li>Три карточки стратегий: боковик / тренд / арбитраж.</li>
                    <li>Смотрите «Режим рынка» — TREND блокирует новые pairs-входы.</li>
                    <li>Нажмите <em>Анализ + paper</em> — обновит сигналы и журнал.
                      Trend и брокер — в <a href="/view/settings">Настройках</a>.</li>
                  </ol>
                </aside>
                """);
        body.append(trialBanner());
        body.append(dashboardFullCoreTeasers());
        body.append(dashboardQuietCta());
        body.append(summaryBlock(report, recommendations.size(), actionable));
        body.append("<section class=\"dash-section\"><h2>Сигналы входа (LONG / SHORT)</h2>");
        body.append(dashboardActionableSignalsTable(
                actionableSignals,
                "Нет активных сигналов LONG/SHORT сейчас. См. полный список рекомендаций."
        ));
        body.append("</section>");
        body.append("<section class=\"dash-section\"><h2>Топ-пары по Sharpe</h2>");
        body.append(topPairsTableCompact(report.topPairs()));
        body.append("</section>");
        body.append("</div>");

        return page("TRINITY — дашборд", body.toString(), nav("dashboard"), OpsMode.NONE);
    }

    /**
     * Настройки: полный пульт оператора + консоль брокера (один раз настроить, не жить здесь).
     */
    public String renderSettings() {
        String body = """
                <div class="settings-shell">
                  <header class="settings-intro">
                    <p class="settings-eyebrow">Конфигурация</p>
                    <h2>Настройки оператора</h2>
                    <p class="meta">
                      Пульт и консоль брокера собраны здесь. Дашборд остаётся обзором портфеля и сигналов —
                      без форм токенов и логов.
                    </p>
                  </header>
                  %s
                  %s
                  %s
                  %s
                </div>
                """.formatted(
                trialBanner(),
                opsPanel(),
                trendPlaybookPanel(),
                brokerConsolePanel()
        );
        return page("TRINITY — настройки", body, nav("settings"), OpsMode.NONE);
    }

    private String trendPlaybookPanel() {
        if (!strategyTrendEnabled) {
            return "";
        }
        return """
                <section class="dash-section strategy-doc" id="trend-playbook-settings">
                  <h2>Trend playbook · исполнение</h2>
                  <p class="meta">
                    Робот «Уровни + профиль» (BR M5) — один из playbook’ов: сигнал или авто
                    (sandbox journal / live по флагам). Выбор режима — переключателем ниже.
                  </p>
                  <div class="callout trend-delivery-card">
                    <div class="trend-delivery-row">
                      <div class="trend-delivery-copy">
                        <strong id="trend-delivery-title">Только сигнал</strong>
                        <p class="meta" id="trend-delivery-hint">
                          Тикер + BUY/SELL без заявок. Переключите для автоторговли.
                        </p>
                      </div>
                      <label class="mode-switch" title="Сигнал ↔ Автоторговля">
                        <span class="mode-switch-label" id="trend-mode-left">Сигнал</span>
                        <input type="checkbox" id="trend-auto-execution" role="switch" aria-checked="false">
                        <span class="mode-switch-track" aria-hidden="true"><span class="mode-switch-knob"></span></span>
                        <span class="mode-switch-label" id="trend-mode-right">Авто</span>
                      </label>
                    </div>
                    <p class="meta" id="trend-delivery-status">Загрузка режима…</p>
                  </div>
                </section>
                """;
    }

    private String dashboardWidgetGrid(
            MarketRegimeSnapshot regime,
            AnalysisReport report,
            long actionableSignals
    ) {
        if (regime == null) {
            regime = MarketRegimeSnapshot.unknown();
        }
        String label = regime.label() == null ? "—" : regime.label();
        String shortLabel = label.length() > 8 ? label.substring(0, 7) + "…" : label;
        String color = switch (label) {
            case "SIDEWAYS" -> "var(--ok)";
            case "NEUTRAL" -> "var(--warn)";
            case "TREND" -> "var(--danger)";
            default -> "var(--slate)";
        };
        String swatch = switch (label) {
            case "SIDEWAYS" -> "ok";
            case "NEUTRAL" -> "warn";
            case "TREND" -> "danger";
            default -> "";
        };
        String hint = switch (label) {
            case "SIDEWAYS" -> "входы ок";
            case "NEUTRAL" -> "осторожно";
            case "TREND" -> "блок входов";
            default -> "нет данных";
        };
        String adx = Double.isNaN(regime.adx()) ? "—" : String.format(Locale.ROOT, "%.0f", regime.adx());
        String regimeBack = regimeBackCopy(label);

        var capital = capitalProperties;
        double equity = capital.equityRub() != null ? capital.equityRub() : 100_000.0;
        int dailyPct = (int) Math.round((capital.dailyGrossShare() != null ? capital.dailyGrossShare() : 1.0) * 100);
        int intraPct = (int) Math.round((capital.intradayGrossShare() != null ? capital.intradayGrossShare() : 0.0) * 100);
        String equityLabel = String.format(Locale.ROOT, "%,.0f ₽", equity).replace(',', ' ');
        String leverage = capital.leverageAllowed() ? "доступно" : "выкл <1M";

        boolean pairsOn = strategyPairsEnabled;
        boolean trendOn = strategyTrendEnabled;
        boolean arbOn = strategyCalendarArbEnabled;

        String strategiesFrontHint = strategyActiveHint(label, pairsOn, trendOn, arbOn);
        String strategiesBack = strategyBackCopy(label, pairsOn, trendOn, arbOn);

        int tickers = report != null ? report.tickersAnalyzed() : 0;
        int pairs = report != null ? report.pairsTested() : 0;
        int coint = report != null ? report.cointegratedPairs() : 0;
        int topN = report != null && report.topPairs() != null ? report.topPairs().size() : 0;
        String analysisDate = report != null && report.analysisDate() != null
                ? report.analysisDate().toString()
                : "—";

        String row1 = """
                <section class="widget-grid" aria-label="Сводка дашборда">
                  %s
                  %s
                  %s
                  %s
                </section>
                """.formatted(
                flipCard(
                        "widget-paper",
                        "Paper",
                        """
                        <div class="donut" id="widget-paper-donut" style="--p:0;--c:var(--accent)">
                          <div class="donut-center">
                            <strong id="dash-paper-open">—</strong>
                            <span>open</span>
                          </div>
                        </div>
                        <div class="widget-meta">
                          <div class="widget-stat"><span class="k"><i class="swatch accent"></i>Открыто</span><span class="v" id="widget-paper-open-label">—</span></div>
                          <div class="widget-stat"><span class="k"><i class="swatch ok"></i>PnL ₽</span><span class="v" id="dash-paper-pnl">—</span></div>
                        </div>
                        """,
                        """
                        <p class="widget-back-lead" id="widget-paper-back-lead">Загрузка journal…</p>
                        <div class="widget-back-stats" id="widget-paper-back-stats"></div>
                        <a class="widget-back-link" href="/view/paper">Открыть paper journal →</a>
                        """
                ),
                flipCard(
                        "widget-broker",
                        "Брокер",
                        """
                        <div class="donut" id="widget-broker-donut" style="--p:0;--c:var(--info)">
                          <div class="donut-center">
                            <strong id="widget-broker-center">—</strong>
                            <span>статус</span>
                          </div>
                        </div>
                        <div class="widget-meta">
                          <div class="widget-stat"><span class="k"><i class="swatch info"></i>Сводка</span><span class="v" id="dash-broker-status">—</span></div>
                          <div class="widget-stat"><span class="k"><i class="swatch gold"></i>Контур</span><span class="v" id="widget-broker-mode">—</span></div>
                          <div class="widget-stat"><span class="k"><i class="swatch accent"></i>Лента</span><span class="v" id="widget-broker-tape">—</span></div>
                        </div>
                        """,
                        """
                        <p class="widget-back-lead" id="widget-broker-back-lead">Загрузка статуса…</p>
                        <div class="widget-back-stats" id="widget-broker-back-stats"></div>
                        <a class="widget-back-link" href="/view/settings">Настройки брокера →</a>
                        """
                ),
                flipCard(
                        "widget-final",
                        "Final",
                        """
                        <div class="donut" id="widget-final-donut" style="--p:0;--c:var(--ok)">
                          <div class="donut-center">
                            <strong id="dash-final-actionable">—</strong>
                            <span>вход</span>
                          </div>
                        </div>
                        <div class="widget-meta">
                          <div class="widget-stat"><span class="k"><i class="swatch ok"></i>ENTER/REDUCE</span><span class="v" id="widget-final-enter">—</span></div>
                          <div class="widget-stat"><span class="k"><i class="swatch warn"></i>WATCH</span><span class="v" id="dash-final-watch">—</span></div>
                          <div class="widget-stat"><span class="k"><i class="swatch danger"></i>BLOCK</span><span class="v" id="dash-final-block">—</span></div>
                        </div>
                        """,
                        """
                        <p class="widget-back-lead" id="widget-final-back-lead">Загрузка итога…</p>
                        <div class="widget-back-stats" id="widget-final-back-stats"></div>
                        <a class="widget-back-link" href="/view/final">Итог + новости →</a>
                        """
                ),
                flipCard(
                        "widget-regime",
                        "Режим рынка",
                        """
                        <div class="donut" id="widget-regime-donut" data-target-p="100" style="--p:0;--c:%s">
                          <div class="donut-center">
                            <strong id="widget-regime-center">%s</strong>
                            <span>ADX %s</span>
                          </div>
                        </div>
                        <div class="widget-meta">
                          <div class="widget-stat"><span class="k"><i class="swatch %s" id="widget-regime-swatch"></i>Режим</span><span class="v" id="widget-regime-label">%s</span></div>
                          <div class="widget-stat"><span class="k">Подсказка</span><span class="v" id="widget-regime-hint">%s</span></div>
                        </div>
                        """.formatted(color, escape(shortLabel), escape(adx), swatch, escape(label), escape(hint)),
                        """
                        <p class="widget-back-lead" id="widget-regime-back-lead">%s</p>
                        <div class="widget-back-stats" id="widget-regime-back-stats"></div>
                        <a class="widget-back-link" href="/view/strategy">О стратегии →</a>
                        """.formatted(escape(regimeBack))
                )
        );

        String row2 = """
                <section class="widget-grid widget-grid-secondary" aria-label="Сводка счёта и стратегий">
                  %s
                  %s
                  %s
                  %s
                </section>
                """.formatted(
                flipCard(
                        "widget-capital",
                        "Капитал",
                        """
                        <div class="donut" id="widget-capital-donut" style="--p:%d;--c:var(--gold)">
                          <div class="donut-center">
                            <strong id="widget-capital-center">%s</strong>
                            <span>equity</span>
                          </div>
                        </div>
                        <div class="widget-meta">
                          <div class="widget-stat"><span class="k"><i class="swatch gold"></i>DAILY</span><span class="v">%d%%</span></div>
                          <div class="widget-stat"><span class="k"><i class="swatch info"></i>INTRADAY</span><span class="v">%d%%</span></div>
                          <div class="widget-stat"><span class="k">Плечо</span><span class="v">%s</span></div>
                        </div>
                        """.formatted(dailyPct, escape(equityLabel), dailyPct, intraPct, escape(leverage)),
                        """
                        <p class="widget-back-lead">Операторский профиль капитала для paper / research.</p>
                        <div class="widget-back-stats">
                          <div class="widget-stat"><span class="k">Equity</span><span class="v">%s</span></div>
                          <div class="widget-stat"><span class="k">Книга DAILY</span><span class="v">%d%% капитала</span></div>
                          <div class="widget-stat"><span class="k">INTRADAY</span><span class="v">%d%% · research-only</span></div>
                          <div class="widget-stat"><span class="k">Плечо</span><span class="v">%s</span></div>
                        </div>
                        <a class="widget-back-link" href="/view/settings">Конфиг в Настройках →</a>
                        """.formatted(escape(equityLabel), dailyPct, intraPct, escape(leverage))
                ),
                flipCard(
                        "widget-signals",
                        "Сигналы",
                        """
                        <div class="donut" id="widget-signals-donut" style="--p:0;--c:var(--accent)">
                          <div class="donut-center">
                            <strong id="widget-signals-center">%d</strong>
                            <span>active</span>
                          </div>
                        </div>
                        <div class="widget-meta">
                          <div class="widget-stat"><span class="k"><i class="swatch ok"></i>LONG</span><span class="v" id="widget-signals-long">—</span></div>
                          <div class="widget-stat"><span class="k"><i class="swatch danger"></i>SHORT</span><span class="v" id="widget-signals-short">—</span></div>
                        </div>
                        """.formatted(actionableSignals),
                        """
                        <p class="widget-back-lead" id="widget-signals-back-lead">Технические LONG/SHORT до FA-гейта.</p>
                        <div class="widget-back-stats" id="widget-signals-back-stats"></div>
                        <a class="widget-back-link" href="/view/signals">Все сигналы →</a>
                        """
                ),
                flipCard(
                        "widget-strategies",
                        "Стратегии",
                        """
                        <div class="donut" id="widget-strategies-donut" style="--p:%d;--c:var(--navy)">
                          <div class="donut-center">
                            <strong id="widget-strategies-center">3</strong>
                            <span>модуля</span>
                          </div>
                        </div>
                        <div class="widget-meta">
                          <div class="widget-stat"><span class="k"><i class="swatch accent"></i>Сейчас</span><span class="v" id="widget-strategies-hint">%s</span></div>
                          <div class="widget-stat"><span class="k">Pairs</span><span class="v">%s</span></div>
                        </div>
                        """.formatted(
                                pairsOn ? 100 : 35,
                                escape(strategiesFrontHint),
                                pairsOn ? "live paper" : "off"
                        ),
                        """
                        <p class="widget-back-lead" id="widget-strategies-back-lead">%s</p>
                        <div class="widget-back-stats">
                          <div class="widget-stat"><span class="k">#1 Pairs</span><span class="v">%s</span></div>
                          <div class="widget-stat"><span class="k">#2 Trend</span><span class="v">%s</span></div>
                          <div class="widget-stat"><span class="k">#3 Calendar arb</span><span class="v">%s</span></div>
                        </div>
                        <a class="widget-back-link" href="/view/full-core">Full Core roadmap →</a>
                        """.formatted(
                                escape(strategiesBack),
                                pairsOn ? "активна (paper)" : "выкл",
                                trendOn ? "вкл" : "research / off",
                                arbOn ? "вкл" : "roadmap / off"
                        )
                ),
                flipCard(
                        "widget-universe",
                        "Вселенная",
                        """
                        <div class="donut" id="widget-universe-donut" style="--p:%d;--c:var(--info)">
                          <div class="donut-center">
                            <strong id="widget-universe-center">%d</strong>
                            <span>coint</span>
                          </div>
                        </div>
                        <div class="widget-meta">
                          <div class="widget-stat"><span class="k"><i class="swatch info"></i>Тикеры</span><span class="v">%d</span></div>
                          <div class="widget-stat"><span class="k"><i class="swatch gold"></i>Пары</span><span class="v">%d</span></div>
                          <div class="widget-stat"><span class="k">Топ</span><span class="v">%d</span></div>
                        </div>
                        """.formatted(
                                pairs > 0 ? Math.min(100, (int) Math.round(100.0 * coint / Math.max(1, pairs))) : 0,
                                coint,
                                tickers,
                                pairs,
                                topN
                        ),
                        """
                        <p class="widget-back-lead">Последний прогон анализа: <strong>%s</strong>.</p>
                        <div class="widget-back-stats">
                          <div class="widget-stat"><span class="k">Тикеров</span><span class="v">%d</span></div>
                          <div class="widget-stat"><span class="k">Пар протестировано</span><span class="v">%d</span></div>
                          <div class="widget-stat"><span class="k">Коинтегрированы</span><span class="v">%d</span></div>
                          <div class="widget-stat"><span class="k">В топе UI</span><span class="v">%d</span></div>
                        </div>
                        <a class="widget-back-link" href="/view/recommendations">Рекомендации →</a>
                        """.formatted(escape(analysisDate), tickers, pairs, coint, topN)
                )
        );

        String pillars = dashboardStrategyPillars(label, pairsOn, trendOn, arbOn);
        return row1 + row2 + pillars;
    }

    /**
     * Три столпа TRINITY на дашборде: боковик (pairs), тренд (все playbooks), календарный арбитраж.
     */
    private String dashboardStrategyPillars(
            String regime,
            boolean pairsOn,
            boolean trendOn,
            boolean arbOn
    ) {
        boolean sideways = "SIDEWAYS".equals(regime);
        boolean trending = "TREND".equals(regime);

        String pairsStatus = !pairsOn ? "выкл"
                : trending ? "пауза · ADX"
                : sideways ? "paper live" : "осторожно";
        String pairsSwatch = !pairsOn ? "slate" : trending ? "warn" : "ok";
        String pairsCenter = !pairsOn ? "OFF" : trending ? "HOLD" : "ON";
        int pairsPct = !pairsOn ? 0 : trending ? 35 : 100;

        String trendStatus = !trendOn ? "выкл" : "1 playbook";
        String trendSwatch = trendOn ? "accent" : "slate";
        String trendCenter = trendOn ? "BR" : "—";
        int trendPct = trendOn ? 70 : 20;

        String arbStatus = arbOn ? "early" : "заглушка";
        String arbSwatch = arbOn ? "gold" : "slate";
        String arbCenter = arbOn ? "EA" : "···";
        int arbPct = arbOn ? 40 : 15;

        return """
                <section class="widget-grid widget-grid-pillars" aria-label="Три стратегии TRINITY">
                  %s
                  %s
                  %s
                </section>
                """.formatted(
                flipCard(
                        "pillar-pairs",
                        "① Боковик · Pairs",
                        """
                        <div class="donut" style="--p:%d;--c:var(--ok)">
                          <div class="donut-center">
                            <strong>%s</strong>
                            <span>pairs</span>
                          </div>
                        </div>
                        <div class="widget-meta">
                          <div class="widget-stat"><span class="k"><i class="swatch %s"></i>Статус</span><span class="v">%s</span></div>
                          <div class="widget-stat"><span class="k">Книга</span><span class="v">DAILY paper</span></div>
                          <div class="widget-stat"><span class="k">Gate</span><span class="v">ADX · FA</span></div>
                        </div>
                        """.formatted(pairsPct, escape(pairsCenter), pairsSwatch, escape(pairsStatus)),
                        """
                        <p class="widget-back-lead">Стратегия #1 — mean-reversion на коинтегрированных парах IMOEX.
                          Новые входы в SIDEWAYS; при TREND (ADX) — блок.</p>
                        <div class="widget-back-stats">
                          <div class="widget-stat"><span class="k">Модуль</span><span class="v">trinity-pairs</span></div>
                          <div class="widget-stat"><span class="k">Флаг</span><span class="v">imoex.strategies.pairs</span></div>
                        </div>
                        <a class="widget-back-link" href="/view/strategy">О pairs →</a>
                        """
                ),
                flipCard(
                        "pillar-trend",
                        "② Тренд · Playbooks",
                        """
                        <div class="donut" style="--p:%d;--c:var(--accent)">
                          <div class="donut-center">
                            <strong>%s</strong>
                            <span>trend</span>
                          </div>
                        </div>
                        <div class="widget-meta">
                          <div class="widget-stat"><span class="k"><i class="swatch %s"></i>Статус</span><span class="v">%s</span></div>
                          <div class="widget-stat"><span class="k">Активный</span><span class="v">Уровни+профиль</span></div>
                          <div class="widget-stat"><span class="k">Лента</span><span class="v" id="pillar-trend-tape">…</span></div>
                        </div>
                        """.formatted(trendPct, escape(trendCenter), trendSwatch, escape(trendStatus)),
                        """
                        <p class="widget-back-lead">Стратегия #2 — робот по playbook’ам. Сейчас один:
                          «Уровни + профиль рынка» (BR). Одновременно на инструменте — не больше одного
                          playbook’а; переключение — через селектор режима (см. ниже / настройки).</p>
                        <div class="widget-back-stats" id="pillar-trend-back-stats">
                          <div class="widget-stat"><span class="k">Playbook</span><span class="v">levels-profile-br-m5</span></div>
                          <div class="widget-stat"><span class="k">Режим</span><span class="v">сигнал / авто</span></div>
                          <div class="widget-stat"><span class="k">Данные</span><span class="v">T-Invest tape+DOM</span></div>
                        </div>
                        <a class="widget-back-link" href="/view/trend-signal">Экран сигнала →</a>
                        """
                ),
                flipCard(
                        "pillar-arb",
                        "③ Арбитраж · Calendar",
                        """
                        <div class="donut" style="--p:%d;--c:var(--gold)">
                          <div class="donut-center">
                            <strong>%s</strong>
                            <span>arb</span>
                          </div>
                        </div>
                        <div class="widget-meta">
                          <div class="widget-stat"><span class="k"><i class="swatch %s"></i>Статус</span><span class="v">%s</span></div>
                          <div class="widget-stat"><span class="k">Тип</span><span class="v">futures calendar</span></div>
                          <div class="widget-stat"><span class="k">Доступ</span><span class="v">Full Core</span></div>
                        </div>
                        """.formatted(arbPct, escape(arbCenter), arbSwatch, escape(arbStatus)),
                        """
                        <p class="widget-back-lead">Стратегия #3 — календарный арбитраж фьючерсов.
                          Код и live пока не стартуем: roadmap / early access Full Core после валидации pairs paper.</p>
                        <div class="widget-back-stats">
                          <div class="widget-stat"><span class="k">Модуль</span><span class="v">trinity-calendar-arb</span></div>
                          <div class="widget-stat"><span class="k">Сейчас</span><span class="v">заглушка UI</span></div>
                        </div>
                        <a class="widget-back-link" href="/view/full-core?feature=calendar-arb">Full Core · arbitrage →</a>
                        """
                )
        );
    }

    private String flipCard(String id, String title, String frontInner, String backInner) {
        return """
                <article class="widget-card is-flippable" id="%s" data-flip="1" tabindex="0" role="button" aria-pressed="false" aria-label="%s — нажмите, чтобы перевернуть">
                  <div class="widget-flip">
                    <div class="widget-face widget-front">
                      <div class="widget-title">%s <span class="widget-flip-cue" aria-hidden="true">⇄</span></div>
                      <div class="widget-body">%s</div>
                    </div>
                    <div class="widget-face widget-back">
                      <div class="widget-title">%s · детали <span class="widget-flip-cue" aria-hidden="true">↩</span></div>
                      <div class="widget-back-body">%s</div>
                    </div>
                  </div>
                </article>
                """.formatted(id, escape(title), escape(title), frontInner, escape(title), backInner);
    }

    private static String regimeBackCopy(String label) {
        return switch (label) {
            case "TREND" -> "Сейчас выявлен трендовый рынок: mean-reversion (коинтеграция) на таком рынке неэффективна. "
                    + "Новые входы pairs заблокированы. В фокусе — research TREND и calendar-arbitrage (поиск идей, не live paper).";
            case "NEUTRAL" -> "Переходный режим: pairs ещё допустимы, но размер снижен. "
                    + "Параллельно идёт мониторинг — при усилении тренда активируется контур TREND / ARBITRAGE research.";
            case "SIDEWAYS" -> "Боковик: стратегия коинтеграции (pairs) в приоритете — paper-входы разрешены при прохождении FA. "
                    + "TREND и ARBITRAGE остаются на research-контуре.";
            default -> "Режим рынка не определён (нет ADX). Pairs работают осторожно; TREND/ARBITRAGE — research.";
        };
    }

    private static String strategyActiveHint(String regime, boolean pairsOn, boolean trendOn, boolean arbOn) {
        if ("TREND".equals(regime)) {
            if (trendOn) {
                return "Trend playbook";
            }
            if (arbOn) {
                return "ARB research";
            }
            return "pairs блок · research";
        }
        if (pairsOn) {
            return "Pairs paper";
        }
        if (trendOn) {
            return "Pairs + Trend";
        }
        return "модули off";
    }

    private static String strategyBackCopy(String regime, boolean pairsOn, boolean trendOn, boolean arbOn) {
        StringBuilder sb = new StringBuilder();
        if ("TREND".equals(regime)) {
            sb.append("Тренд: коинтеграция неэффективна для новых входов. ");
            if (trendOn && arbOn) {
                sb.append("Активны research-контуры TREND и ARBITRAGE — идёт анализ и поиск бумаг/срочных.");
            } else if (trendOn) {
                sb.append("Активен research TREND — поиск идей по плейбукам режима.");
            } else if (arbOn) {
                sb.append("Активен research calendar-arbitrage.");
            } else {
                sb.append("TREND/ARBITRAGE пока выключены флагами — на Full Core roadmap; pairs paper на паузе по режиму.");
            }
        } else if ("SIDEWAYS".equals(regime)) {
            sb.append(pairsOn
                    ? "Боковик: активна стратегия #1 Pairs (paper). TREND и ARBITRAGE — research/roadmap."
                    : "Боковик, но pairs выключены конфигом.");
        } else {
            sb.append("Сводка модулей TRINITY под текущий режим рынка.");
        }
        return sb.toString();
    }

    private String brokerConsolePanel() {
        return """
                <section class="dash-section strategy-doc" id="broker-console">
                  <h2>Консоль брокера</h2>
                  <p class="meta">
                    Токены и параметры исполнения без правки
                    <code>application-local.yml</code>. Сейчас — <strong>T-Invest</strong>.
                  </p>
                  <div class="ops-grid">
                    <div class="callout" id="broker-widget">
                      <strong>Статус брокера</strong>
                      <div id="broker-status-line">Статус брокера загружается…</div>
                      <div id="broker-test-line">Подключение ещё не проверялось.</div>
                      <div id="broker-reconcile-line">Сверка ещё не запрашивалась.</div>
                      <div id="broker-journal-line">Журнал брокера загружается…</div>
                      <div class="ops-actions">
                        <button type="button" class="btn btn-primary" data-ops-action="broker-test">Проверить подключение</button>
                        <button type="button" class="btn btn-ghost" data-ops-action="broker-reconcile">Сверить с брокером</button>
                        <button type="button" class="btn btn-warn" data-ops-action="broker-flatten">Закрыть все позиции брокера</button>
                      </div>
                    </div>
                    <div class="callout">
                      <strong>Подключение брокера</strong>
                      <div class="auth-row">
                        <div class="field">
                          <label for="broker-provider">Брокер</label>
                          <select id="broker-provider">
                            <option value="T_INVEST">T-Invest</option>
                            <option value="ALOR">Alor</option>
                            <option value="FINAM">Finam</option>
                            <option value="BKS">BKS</option>
                          </select>
                        </div>
                        <div class="field">
                          <label for="broker-mode">Режим</label>
                          <select id="broker-mode">
                            <option value="AUTO">AUTO — автоматически</option>
                            <option value="MANUAL_CONFIRM">MANUAL — с подтверждением</option>
                            <option value="PAPER">PAPER — только paper</option>
                          </select>
                        </div>
                        <div class="field">
                          <label for="broker-account-id">ID счёта</label>
                          <input id="broker-account-id" type="text" autocomplete="off" spellcheck="false">
                        </div>
                      </div>
                      <p class="meta" id="broker-sandbox-account-line">
                        Для песочницы: токен → «Подтянуть / создать счёт» → «Пополнить песочницу».
                      </p>
                      <div class="auth-row">
                        <div class="field">
                          <label for="broker-token">Токен</label>
                          <input id="broker-token" type="password" autocomplete="off" placeholder="Вставьте новый токен только при обновлении">
                        </div>
                        <div class="field">
                          <label for="broker-sandbox-payin-amount">Пополнение песочницы, ₽</label>
                          <input id="broker-sandbox-payin-amount" type="number" step="1000" min="1000" value="200000">
                        </div>
                        <div class="field">
                          <label for="broker-passive-bps">Смещение лимита, bps</label>
                          <input id="broker-passive-bps" type="number" step="0.1" min="0">
                        </div>
                      </div>
                      <div class="auth-row">
                        <div class="field">
                          <label for="broker-timeout-seconds">Таймаут второй ноги, сек</label>
                          <input id="broker-timeout-seconds" type="number" step="1" min="1">
                        </div>
                      </div>
                      <div class="alert-prefs">
                        <label class="check-label"><input type="checkbox" id="broker-enabled"> Брокер включён</label>
                        <label class="check-label"><input type="checkbox" id="broker-sandbox"> Песочница</label>
                        <label class="check-label"><input type="checkbox" id="broker-auto-execute"> Автоисполнение после анализа</label>
                        <label class="check-label"><input type="checkbox" id="broker-prefer-limit"> Предпочитать лимитные заявки</label>
                        <label class="check-label"><input type="checkbox" id="broker-allow-market"> Разрешить рыночный fallback</label>
                        <label class="check-label"><input type="checkbox" id="broker-emergency-exit"> Аварийный market-exit при асимметрии</label>
                        <label class="check-label"><input type="checkbox" id="broker-kill-switch"> Аварийный стоп (kill-switch)</label>
                      </div>
                      <p class="meta" id="broker-token-hint">Токен пока не сохранён.</p>
                      <div class="ops-actions">
                        <button type="button" class="btn btn-primary" id="broker-save-settings">Сохранить настройки брокера</button>
                        <button type="button" class="btn btn-ghost" id="broker-sandbox-account">Подтянуть / создать счёт песочницы</button>
                        <button type="button" class="btn btn-ghost" id="broker-sandbox-payin">Пополнить песочницу</button>
                      </div>
                    </div>
                  </div>
                </section>
                """;
    }

    private String dashboardActionableSignalsTable(List<TradingRecommendation> rows, String emptyMessage) {
        if (rows == null || rows.isEmpty()) {
            return "<p class=\"empty-msg\">" + escape(emptyMessage) + "</p>";
        }

        StringBuilder table = new StringBuilder();
        table.append("""
                <div class="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Пара Y / X</th>
                      <th>Сигнал</th>
                      <th>Z-score</th>
                      <th>Дата</th>
                      <th>Комментарий</th>
                      <th>График</th>
                    </tr>
                  </thead>
                  <tbody>
                """);

        for (TradingRecommendation r : rows) {
            table.append("<tr>");
            table.append("<td><strong>")
                    .append(escape(r.tickerY()))
                    .append("</strong> / ")
                    .append(escape(r.tickerX()))
                    .append("</td>");
            table.append("<td>").append(signalBadge(r.signal())).append("</td>");
            table.append("<td class=\"num\">").append(formatZ(r.currentZScore())).append("</td>");
            table.append("<td>").append(r.asOfDate()).append("</td>");
            table.append("<td class=\"details\">")
                    .append("<div class=\"summary\">").append(escape(r.summary())).append("</div>")
                    .append("</td>");
            table.append("<td class=\"links\">").append(chartPageLink(r.tickerY(), r.tickerX())).append("</td>");
            table.append("</tr>");
        }

        table.append("</tbody></table></div>");
        return table.toString();
    }

    private String topPairsTableCompact(List<PairAnalysisResult> pairs) {
        if (pairs == null || pairs.isEmpty()) {
            return "<p class=\"empty-msg\">Топ-пар нет.</p>";
        }

        StringBuilder table = new StringBuilder();
        table.append("""
                <div class="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>#</th>
                      <th>Пара</th>
                      <th>Sharpe</th>
                      <th>Half-life</th>
                      <th>Coverage</th>
                      <th>График</th>
                    </tr>
                  </thead>
                  <tbody>
                """);

        int rank = 1;
        for (PairAnalysisResult p : pairs) {
            table.append("<tr>");
            table.append("<td>").append(rank++).append("</td>");
            table.append("<td><strong>").append(escape(p.tickerY())).append("</strong> / ")
                    .append(escape(p.tickerX())).append("</td>");
            table.append("<td class=\"num\">").append(formatNum(p.sharpeRatio())).append("</td>");
            table.append("<td class=\"num\">").append(formatNum(p.halfLifeDays())).append(" д</td>");

            String cov = p.coveragePercent() == null ? "—"
                    : String.format(Locale.ROOT, "%.1f%%", p.coveragePercent());
            table.append("<td class=\"num\" title=\"")
                    .append(escape(p.coverageWarning() == null ? "" : p.coverageWarning()))
                    .append("\">")
                    .append(cov)
                    .append("</td>");

            table.append("<td class=\"links\">").append(chartPageLink(p.tickerY(), p.tickerX())).append("</td>");
            table.append("</tr>");
        }

        table.append("</tbody></table></div>");
        return table.toString();
    }

    /** Страница всех торговых рекомендаций. */
    public String renderAllRecommendations(List<TradingRecommendation> recommendations) {
        StringBuilder body = new StringBuilder();
        body.append("""
                <div class="hint">
                  <strong>Режим рынка.</strong> Стратегия — mean-reversion <em>только в боковике</em>.
                  Если на дашборде режим TREND (высокий ADX индекса), входы блокируются:
                  в кратком описании будет «Не торговать! Выявлен тренд — стратегия только боковик».
                </div>
                """);
        body.append("<p class=\"meta\">Всего рекомендаций: ").append(recommendations.size()).append("</p>");
        body.append(recommendationsTable(recommendations, "Рекомендаций пока нет. Нажмите «Анализ + paper» на дашборде, в полоске сверху или в Настройках."));
        return page("TRINITY — рекомендации", body.toString(), nav("recommendations"));
    }

    /** Страница только actionable-сигналов. */
    public String renderSignals(List<TradingRecommendation> signals) {
        StringBuilder body = new StringBuilder();
        body.append("<p class=\"meta\">Сигналов входа: ").append(signals.size()).append("</p>");
        body.append(recommendationsTable(signals, "Нет пар с |Z| ≥ порога входа."));
        return page("TRINITY — сигналы", body.toString(), nav("signals"));
    }

    /** Страница «анализ не выполнен». */
    public String renderEmpty() {
        String body = """
                <div class="empty">
                  <h2>Анализ ещё не выполнен</h2>
                  <p>Нажмите <strong>«Анализ + paper»</strong> на дашборде, в полоске сверху или в
                    <a href="/view/settings">Настройках</a>.
                  Логин/пароль API — из вашего локального <code>application-local.yml</code>
                  (в репозитории секретов нет). После завершения страница обновится сама.</p>
                  <p class="meta">Если свечей ещё нет — сначала «Скачать свечи» в Настройках, либо «Анализ + скачать свечи».</p>
                </div>
                """;
        return page("TRINITY — нет данных", body, nav("none"));
    }

    /**
     * Описание торговой стратегии простым языком.
     */
    public String renderStrategy() {
        String arbBadge = fullCoreBadge("calendar-arb");
        String trendBadge = fullCoreBadge("trend");
        String researchBadge = ""; // working local replay — no fake lock
        String roadmapBlock = coreRoadmapBlock();
        String body = """
                <article class="strategy-doc">
                  <h2>Описание торговой стратегии</h2>
                  <p class="lead">
                    TRINITY сейчас в live paper ведёт <strong>DAILY</strong> pairs mean-reversion в боковике
                    (фокус — металлы / mining; нефть в equities-парах отложена на фьючерсы/опционы).
                    <strong>INTRADAY</strong> — только research (1H EG/Z/метрики), без paper-торговли.
                    Мы не угадываем направление рынка: ищем временный разрыв связанной пары и ставим на сжатие.
                    Календарный арбитраж %s и опционы — следующие стратегии бренда, пока в дорожной карте.
                  </p>

                  %s

                  <aside class="atas-plaque" id="atas" aria-labelledby="atas-title">
                    <span class="atas-badge">Встроено в TRINITY</span>
                    <h3 id="atas-title">Функционал ATAS внутри TRINITY</h3>
                    <p>
                      Отдельный терминал ATAS не нужен: ключевые идеи order-flow и volume profile
                      встроены в пайплайн как <strong>execution-слой</strong> поверх Z-score.
                      Это не «ещё один индикатор», а проверка: можно ли <em>реально</em> набрать обе ноги
                      пары на 1H без ложного входа на тонком рынке.
                    </p>
                    <ul>
                      <li><strong>Relative volume</strong> — бар не «мёртвый», объём сопоставим с медианой.</li>
                      <li><strong>Spread proxy</strong> — ширина H–L относительно цены (bps): отсев illiquid часов.</li>
                      <li><strong>Delta proxy ног</strong> — направление закрытия бара; согласованность с LONG/SHORT spread.</li>
                      <li><strong>Volume profile (POC / value area)</strong> — цена ноги в зоне справедливого объёма; <strong>partial TP у POC</strong> на INTRADAY.</li>
                      <li><strong>Footprint proxy</strong> — buy/sell imbalance внутри бара (volume-weighted).</li>
                      <li><strong>Volume clusters</strong> — аномальный объём на краю VA → WATCH, риск ложного входа.</li>
                      <li><strong>DOM</strong> — snapshot стакана MOEX ISS: глубина bid/ask, spread bps, imbalance ноги.</li>
                      <li><strong>Iceberg proxy</strong> — скрытая ликвидность: высокий объём при узком диапазоне.</li>
                      <li><strong>Session edges</strong> — блок первых/последних минут сессии (тонкий рынок MOEX).</li>
                      <li><strong>INTRADAY tier-1</strong> — только ~30 ликвиднейших голубых фишек (SBER, LKOH, GAZP…).</li>
                    </ul>
                    <p class="atas-why">
                      <strong>Зачем это добавлено.</strong>
                      Классический pairs-backtest часто красив на бумаге, но ломается в live из‑за проскальзывания
                      и асимметрии ног. TRINITY отсекает сигналы, где Z «есть», а исполнение на MOEX — сомнительное.
                      Для оператора — меньше ложных входов; для продукта — честнее paper и ближе к live.
                      Задел под трендовую стратегию (breakout VA, delta momentum, absorption) уже в коде
                      (<code>quant/trend</code>, <code>imoex.microstructure.trend</code>), включается на roadmap #2 %s.
                    </p>
                  </aside>

                  <aside class="atas-plaque" id="tiger" aria-labelledby="tiger-title">
                    <span class="atas-badge">Встроено в TRINITY</span>
                    <h3 id="tiger-title">Функционал Tiger.trade внутри TRINITY</h3>
                    <p>
                      Отдельный терминал Tiger.trade не нужен: live DOM, лента сделок и depth-профиль
                      входят в продукт как <strong>market-data контур</strong> маркетплейса —
                      рядом с ATAS-слоем, но отдельно от исполнения ордеров у брокера.
                      Это не «ещё один график», а поток рынка: что реально стоит в стакане
                      и как идут сделки в момент сигнала.
                    </p>
                    <ul>
                      <li><strong>Live DOM</strong> — глубина bid/ask с провайдера (не только snapshot ISS).</li>
                      <li><strong>Trades tape</strong> — поток сделок для delta / footprint на desk.</li>
                      <li><strong>Depth / candle profile</strong> — профиль объёма внутри бара для ручного входа.</li>
                      <li><strong>Session liquidity map</strong> — где рынок тонкий, где набор ног реалистичен.</li>
                      <li><strong>Модуль <code>trinity-marketdata</code></strong> — SPI feed (<code>MarketDataFeed</code>,
                        провайдер <code>T_INVEST</code> → MarketDataStream).</li>
                      <li><strong>Флаг <code>imoex.marketdata.*</code></strong> — контур включается отдельно от pairs/paper.</li>
                    </ul>
                    <p class="atas-why">
                      <strong>Зачем это добавлено.</strong>
                      ATAS-слой отвечает на вопрос «можно ли входить по объёму/профилю»;
                      Tiger-слой — «что видит рынок прямо сейчас» (стакан + лента).
                      Вместе это замена внешней связки ATAS + Tiger.trade в одной подписке TRINITY:
                      сигнал → объяснение → ручной ордер у брокера.
                      Сейчас контур в коде как foundation (SPI + stub); live-stream подключается по мере валидации paper/OOS.
                      Roadmap #4 — volume desk поверх этого feed.
                    </p>
                  </aside>

                  <aside class="atas-plaque" id="trend-robot" aria-labelledby="trend-robot-title">
                    <span class="atas-badge">Робот · sandbox</span>
                    <h3 id="trend-robot-title">Playbook #1 — Уровни + профиль (BR M5)</h3>
                    <p>
                      Торговый робот стратегии #2: чек-лист «Уровни + Объемы» + усиления риска.
                      На М5 нефтяного фьючерса строит <strong>market profile</strong> на отбоях,
                      сливает HVN в диапазон <strong>15–20 пунктов</strong>, выбирает bounce или break+retest,
                      ставит сетку из 3 лимиток (2-2-2 / 3-1-1), SL от средней позиции, TP1 → Б/У → runner.
                    </p>
                    <ul>
                      <li><strong>Модуль</strong> <code>trinity-trend</code> · id <code>levels-profile-br-m5</code></li>
                      <li><strong>Профиль обязателен</strong> — VAP-прокси по H–L бара; tick VAP — через marketdata позже</li>
                      <li><strong>Риск</strong> — <code>min(ГО, maxRiskPct equity)</code>, не «весь депозит / ГО»</li>
                      <li><strong>Одна зона / один сетап</strong> — после ARMED не прыгаем на новый уровень,
                        пока цена не уйдёт ≥ <code>unlock-distance-points</code> (default 40) от mid зоны или новый день</li>
                      <li><strong>Исполнение</strong> — сигнал (<code>auto-execution=false</code>)
                        или авто/journal (<code>auto-execution=true</code>); live FORTS — ещё
                        <code>live-execution=true</code> когда single-leg брокер готов</li>
                      <li><strong>API</strong> — <code>GET/POST /api/trend/*</code> (status, signal, evaluate, submit, journal)</li>
                    </ul>
                    <p class="atas-why">
                      <strong>Зачем.</strong>
                      Нефть уходит из equities-пар в фьючерсный trend-контур. Sandbox-first —
                      тот же мозг робота, без обещания live до OOS.
                    </p>
                  </aside>

                  <nav class="strategy-toc" aria-label="Содержание">
                    <strong>Содержание</strong>
                    <ol>
                      <li><a href="#core-roadmap">Roadmap TRINITY / Full Core</a></li>
                      <li><a href="#atas">Функционал ATAS внутри TRINITY</a></li>
                      <li><a href="#tiger">Функционал Tiger.trade внутри TRINITY</a></li>
                      <li><a href="#trend-robot">Playbook #1 — Уровни + профиль (BR M5)</a></li>
                      <li><a href="#idea">Идея простыми словами</a></li>
                      <li><a href="#pipeline">Что за чем происходит</a></li>
                      <li><a href="#universe">Как отбираются акции</a></li>
                      <li><a href="#pairs">Как пары попадают в анализ</a></li>
                      <li><a href="#clusters">Ежемесячный пересмотр кластеров</a></li>
                      <li><a href="#regime">Режим рынка: только боковик</a></li>
                      <li><a href="#signals">Как появляется сигнал</a></li>
                      <li><a href="#news">Новостной фильтр</a></li>
                      <li><a href="#size">Размер позиции и лимиты</a></li>
                      <li><a href="#exits">Как выходим</a></li>
                      <li><a href="#paper">Paper и проверка на истории</a></li>
                      <li><a href="#validation">Валидация: replay и издержки</a></li>
                      <li><a href="#intraday-events">INTRADAY: календарь событий</a></li>
                      <li><a href="#limits">Честные ограничения</a></li>
                    </ol>
                  </nav>

                  <h3 id="idea">1. Идея простыми словами</h3>
                  <p>
                    Берём пару акций, например банк A и банк B. Если исторически их цены связаны,
                    можно собрать <em>спред</em> — разницу с учётом «коэффициента хеджа» β:
                    сколько бумаги X нужно против одной единицы Y.
                  </p>
                  <p>
                    Дальше смотрим на <strong>Z-score</strong>: насколько спред сейчас ушёл от своей нормы.
                    Если Z очень высокий — спред «раздут», ждём сжатия вниз.
                    Если очень низкий — ждём отскока вверх.
                  </p>
                  <ul>
                    <li><strong>LONG спред</strong> (Z слишком низкий): купить Y и одновременно продать X.</li>
                    <li><strong>SHORT спред</strong> (Z слишком высокий): продать Y и купить X.</li>
                  </ul>
                  <div class="callout">
                    Прибыль (или убыток) идёт от <strong>схождения ног</strong>, а не от того,
                    что весь рынок вырос. Поэтому важны обе ноги сразу.
                  </div>

                  <h3 id="pipeline">2. Что за чем происходит в одном прогоне</h3>
                  <div class="flow" aria-hidden="true">
                    <span>MOEX daily</span><i>→</i>
                    <span>Capital → DAILY</span><i>→</i>
                    <span>EG/FDR + cluster</span><i>→</i>
                    <span>FA → paper</span><i>→</i>
                    <span>INTRADAY research</span>
                  </div>
                  <ol class="pipeline">
                    <li><strong>Капитал.</strong> Equity → слоты DAILY (INTRADAY share = 0 при research-only). Без плеча до 1M.</li>
                    <li><strong>DAILY.</strong> Дневные свечи → EG/FDR/Z → monthly cluster gate → фундамент (MOEX+RSS) → paper-journal.json.</li>
                    <li><strong>INTRADAY.</strong> 1H свечи ISS → EG/Z/метрики (и ATAS-gate в коде) → <strong>без paper-открытий</strong>. Cron выключен.</li>
                    <li><strong>Режим.</strong> ADX индекса блокирует <em>новые</em> входы DAILY при TREND.</li>
                  </ol>
                  <div class="callout">
                    Торговый фокус — DAILY metals. INTRADAY остаётся в пайплайне как research-слой
                    (`imoex.paper.intraday-research-only`), не как вторая торговая книга.
                    Источник свечей — только MOEX ISS.
                  </div>
                """.formatted(arbBadge, roadmapBlock, trendBadge);
        // Continue with rest of strategy page — read original and splice carefully.
        // The original method had one big string; we split: first part formatted above,
        // then append the remainder that starts at universe section.
        body = body + strategyDocRemainder(researchBadge);
        return page("TRINITY — описание стратегии", body, nav("strategy"));
    }

    /** Remainder of strategy doc after the pipeline callout. */
    private String strategyDocRemainder(String researchBadge) {
        return """
                  <h3 id="universe">3. Как отбираются акции в анализ</h3>
                  <p>До любых статистических тестов тикер должен пройти простой «рыночный» фильтр:</p>
                  <ul>
                    <li>состав индекса <strong>IMOEX</strong>, режим TQBR;</li>
                    <li>медианный дневной оборот за ~60 дней не ниже порога (по умолчанию ~50 млн ₽);</li>
                    <li>цена закрытия не ниже минимума (по умолчанию 5 ₽);</li>
                    <li>мало дней с нулевым объёмом;</li>
                    <li>привилегированные акции (<code>*P</code>) обычно исключены;</li>
                    <li>тикер должен быть в секторном каталоге, если включён секторный режим.</li>
                    <li><strong>только INTRADAY:</strong> дополнительно whitelist <strong>1-го эшелона</strong>
                      (~30 голубых фишек) — <code>imoex.universe.intraday-tier-one-only</code>.
                      DAILY может оставаться шире при том же ADV-фильтре.</li>
                  </ul>
                  <p>
                    Смысл: не тестировать illiquid «мусор», где спред нельзя нормально набрать и закрыть.
                  </p>

                  <h3 id="pairs">4. Как пары попадают в анализ и проходят фильтры</h3>
                  <ol class="pipeline">
                    <li>
                      <strong>Кандидаты.</strong> Из отфильтрованного списка строим пары.
                      По умолчанию — только один сектор или «родственная» группа
                      (например нефть ↔ электроэнергетика, ритейл ↔ телеком).
                    </li>
                    <li>
                      <strong>Ликвидность пары.</strong> Обе ноги должны иметь достаточный ADV,
                      и обороты не должны различаться в десятки раз (иначе хедж на бумаге, а в жизни — нет).
                    </li>
                    <li>
                      <strong>Общая история.</strong> Нужно достаточно общих торговых дней (порядка 100+).
                    </li>
                    <li>
                      <strong>Коинтеграция Engle–Granger.</strong>
                      Проверяем, что остатки регрессии log-цен стационарны — то есть «связь» не случайная на коротком куске.
                    </li>
                    <li>
                      <strong>FDR (q ≈ 0.20).</strong>
                      Когда пар тысячи, часть «значимых» p-value — ложные. FDR оставляет только те,
                      кто проходит контроль множественных сравнений.
                    </li>
                    <li>
                      <strong>Data coverage.</strong> Для каждой пары считаем долю общих баров
                      (<code>coveragePercent</code> в <code>analysis-report.json</code>).
                      Ниже порога (<code>imoex.risk.min-coverage-percent</code>, по умолчанию 85%) — пара отсекается:
                      слишком много пропусков истории (делистинг, дырявые котировки).
                    </li>
                    <li>
                      <strong>Качество серии.</strong>
                      Считаем спред, Z, half-life, Sharpe симуляции. Слишком медленный возврат к среднему
                      или слабые метрики не дают входной сигнал.
                    </li>
                  </ol>
                  <div class="callout">
                    На дашборде «Топ-пары по Sharpe» — это уже прошедшие статистику и отобранные для обзора.
                    Сырой сигнал LONG/SHORT ещё не равен разрешению торговать: дальше режим рынка, новости и лимиты книги.
                  </div>

                  <h3 id="clusters">4a. Ежемесячный пересмотр кластеров</h3>
                  <p>
                    Раз в месяц (на стыке месяца в replay / при live-прогоне) поверх EG/FDR/quality
                    считается <strong>секторный rolling cash PnL и profit factor</strong> по закрытым paper-сделкам
                    за lookback (<code>imoex.cluster-review.lookback-months</code>, по умолчанию 6).
                  </p>
                  <ul>
                    <li>в слоты — только сектора с <strong>net &gt; 0</strong> и <strong>PF ≥ 1.1</strong> (при ≥ N закрытий);</li>
                    <li>мало истории — сектор допускается временно (cold start), кроме нефти;</li>
                    <li><strong>OIL_GAS</strong> всегда вне DAILY pairs (нефть → roadmap фьючерсы/опционы);</li>
                    <li>пары с достаточной собственной историей дополнительно режутся тем же net/PF-порогом.</li>
                  </ul>

                  <h3 id="regime">5. Режим рынка: стратегия только боковик</h3>
                  <p>
                    Mean-reversion плохо работает в сильном тренде: спред может «уехать» вместе с рынком
                    и не вернуться к среднему. Поэтому перед входами смотрим <strong>ADX индекса IMOEX</strong>
                    (виджет «Режим рынка» на дашборде):
                  </p>
                  <ul>
                    <li><strong>SIDEWAYS</strong> (ADX низкий) — боковик, mean-reversion активна;</li>
                    <li><strong>NEUTRAL</strong> — переходная зона: входы разрешены, размер уменьшен;</li>
                    <li><strong>TREND</strong> (ADX высокий) — <em>не торговать</em>: новые входы блокируются,
                      в рекомендациях будет явная формулировка про тренд и боковик.</li>
                  </ul>
                  <p>
                    Трендовой стратегии в модуле cointegration нет — только ставка на сжатие спреда в боковике.
                  </p>

                  <h3 id="signals">6. Как появляется торговый сигнал</h3>
                  <p>
                    Пороги по умолчанию: вход при |Z| ≥ <strong>2.0</strong>, цель возврата около <strong>Z ≈ 0</strong>.
                    Z считается в скользящем окне (~60 дней), хедж может подстраиваться фильтром Калмана.
                  </p>
                  <p>
                    Важная деталь: <strong>вход не на первом касании</strong> порога ±2.
                    Ждём, пока Z уже был за порогом и развернулся к нулю — меньше ложных входов
                    «в расширяющийся» дисбаланс.
                  </p>
                  <ul>
                    <li><strong>LONG / SHORT</strong> — есть подтверждённый вход.</li>
                    <li><strong>WATCH</strong> — спред экстремальный, но разворота ещё нет (или зона внимания).</li>
                    <li><strong>HOLD / NO_SIGNAL</strong> — сейчас не входим.</li>
                    <li><strong>WATCH (microstructure)</strong> — Z и разворот ок, но INTRADAY gate ATAS заблокировал вход
                      (тонкий объём, широкий spread proxy, несогласованность ног).</li>
                  </ul>
                  <p>
                    Смотреть картинку удобнее на странице пары: стрелки входа, зона «ждём разворот»,
                    линия KAMA / спреда.
                  </p>

                  <h3 id="news">7. Новостной / фундаментальный фильтр (после техники)</h3>
                  <p>
                    Порядок жёсткий: <strong>сначала техника</strong>, затем фундамент,
                    и только потом итоговая рекомендация и paper.
                    Фильтр работает в режиме <strong>DAILY / multi-day</strong> (удержание несколько дней).
                    В <strong>INTRADAY</strong> фундамент намеренно пропускается — новости запаздывают.
                  </p>
                  <p>
                    Источники: MOEX sitenews и RSS (<code>imoex.news.rss-feeds</code> — Interfax / RBC / Vedomosti и др.).
                    Те же правила-триггеры (earnings miss, guidance down, SPO, M&amp;A, санкции…).
                    При расхождении с LONG/SHORT в «Итоге» будет явный
                    <strong>CONFLICT: техника vs фундамент</strong>.
                    На <a href="/view/final">Итог + новости</a> лента RSS показана как <em>контекст FA</em>, не как сигнал.
                  </p>
                  <table class="params">
                    <thead><tr><th>Итог</th><th>Что это значит</th></tr></thead>
                    <tbody>
                      <tr><td><strong>ENTER</strong></td><td>Техника ок, фундаментальных блокеров нет — можно открывать paper.</td></tr>
                      <tr><td><strong>REDUCE</strong></td><td>CONFLICT средней силы — размер меньше.</td></tr>
                      <tr><td><strong>WATCH</strong></td><td>Следим, но не открываем как полноценный вход.</td></tr>
                      <tr><td><strong>BLOCK</strong></td><td>CONFLICT / жёсткий стоп: halt, делистинг, earnings miss, SPO, санкции…</td></tr>
                    </tbody>
                  </table>
                  <p>Именно страница <a href="/view/final">Итог + новости</a> — операторский «разрешено / нет» после FA:
                    развёрнутый explain (пайплайн, причины пустой таблицы, словарь ENTER/REDUCE/WATCH/BLOCK),
                    сводка «почему такие», expandable-разбор по строкам и RSS-контекст.
                    В JSON и UI у каждой строки поле <strong><code>rationale</code></strong> — краткое «почему»:
                    Z, фундамент (или «пропущен INTRADAY»), режим ADX, решение и слоты.</p>

                  <h3 id="size">8. Размер позиции и лимиты портфеля</h3>
                  <p>
                    Профиль оператора: счёт <strong>от ~100 000 ₽</strong>, узкая книга
                    <strong>1–2 пары</strong> (не широкий портфель). Базовый notional на ногу Y
                    считается как <strong>доля equity</strong> (<code>notional-per-leg-pct</code>, по умолчанию 30%):
                    при 100k ≈ 30k на ногу, при 200k ≈ 60k. Дальше размер уменьшается или увеличивается
                    через dynamic sizing: волатильность спреда, расстояние до стопа по Z, REDUCE и режим NEUTRAL.
                    Плечо в модели не используется, пока equity ниже порога (~1 млн ₽).
                  </p>
                  <ul>
                    <li>dual-book: слоты от equity (~100k → 1 daily + 2 intraday); gross 40/60 <strong>независимо</strong> по книгам;</li>
                    <li>если DAILY без сигналов — его gross остаётся неиспользованным, INTRADAY не «добирает» остаток;</li>
                    <li>без плеча при equity &lt; 1M;</li>
                    <li>не больше 1 открытой пары на сектор внутри книги;</li>
                    <li>DAILY: удержание несколько дней + FA; INTRADAY: flatten к close, без FA;</li>
                    <li>качество пары для входа: R², half-life в разумных границах, минимум сделок в бэктесте;</li>
                    <li>не открываем, если |Z| уже слишком близко к стоп-уровню.</li>
                  </ul>

                  <h3 id="exits">9. Как выходим из позиции</h3>
                  <p>Выход — не только «дождались Z≈0». В paper работают несколько правил:</p>
                  <ul>
                    <li><strong>Mean-reversion</strong> — спред вернулся к цели около нуля;</li>
                    <li><strong>Partial take-profit</strong> — на полпути к нулю по Z <em>или</em> у POC ноги (INTRADAY, ±15 bps);</li>
                    <li><strong>Trailing по Z</strong> — отдали от лучшей точки — закрываем;</li>
                    <li><strong>Stop по |Z|</strong> (в т.ч. адаптивный) — спред ушёл ещё дальше против нас;</li>
                    <li><strong>Time-stop</strong> — слишком долго в позиции без результата;</li>
                    <li><strong>CUSUM / слом связи</strong> — структурный сдвиг спреда, сильный сдвиг β или коинтеграция «развалилась»;</li>
                    <li><strong>Смена сигнала</strong> — логика пары перевернулась.</li>
                  </ul>

                  <h3 id="paper">10. Paper trading и walk-forward</h3>
                  <p>
                    <a href="/view/paper">Paper journal</a> — учебный журнал без брокера.
                    На каждом анализе система сама открывает ENTER/REDUCE, ведёт mark-to-market
                    и закрывает по правилам выше. PnL считается по количествам и ценам ног
                    (с учётом slippage и borrow), а не как «1 Z = 1%».
                    Slippage задаётся <strong>отдельно по книгам</strong>: DAILY ~20 bps, INTRADAY ~40 bps (stress).
                    На закрытых сделках — колонка <strong>«Комментарий к закрытию»</strong>:
                    <code>mean-reversion</code>, <code>stop</code>, <code>time-stop</code>, <code>flatten</code>, <code>partial-tp</code>
                    (полный текст причины остаётся в Notes).
                  </p>
                  <p>
                    <a href="/view/walk-forward">Walk-forward</a> режет историю на train/test окна:
                    на обучении проверяем коинтеграцию, на тесте гоняем правила без подглядывания вперёд.
                    Это проверка «не подогнали ли мы всё под прошлый год», а не гарантия прибыли.
                  </p>

                  <h3 id="validation">11. Валидация на истории (historical replay)</h3>
                  <p>
                    Дополнительно к walk-forward есть <strong>bar-by-bar replay</strong> всего paper-пайплайна
                    на сохранённых свечах: на каждом баре система «видит» только историю ≤ as-of,
                    строит Z/сигнал и синхронизирует paper — как если бы вы торговали день за днём.
                    {{RESEARCH_BADGE}}
                  </p>
                  <p>Запуск через API (нужны локальные свечи в <code>data/candles/</code>):</p>
                  <pre class="code-block">POST /api/analysis/historical-replay?tickerY=SBER&amp;tickerX=LKOH&amp;from=2023-01-01&amp;to=2025-12-31&amp;book=DAILY</pre>
                  <p>
                    Ответ: сделки, net/realized PnL ₽, win rate, max drawdown.
                    Подробнее в <a href="/view/guide">Как пользоваться системой</a>.
                    Долгий локальный candle-архив и deep research replay — профиль Full Core (roadmap).
                  </p>

                  <h3 id="intraday-events">12. INTRADAY: календарь событий</h3>
                  <p>
                    Фундаментальный фильтр для INTRADAY намеренно отключён (новости запаздывают),
                    но добавлен <strong>event overlay</strong>: файл <code>data/event-calendar.json</code>
                    (шаблон — <code>event-calendar.example.json</code> в корне репозитория).
                  </p>
                  <ul>
                    <li>за <strong>45 минут</strong> до события (отчётность, макро, дивиденды) — блок новых входов INTRADAY;</li>
                    <li>открытые INTRADAY-позиции по затронутым тикерам — принудительный flatten;</li>
                    <li>тикер <code>*</code> — событие для всего рынка.</li>
                  </ul>
                  <p>Конфиг: <code>imoex.session.event-calendar-enabled</code>, <code>event-flatten-minutes-before</code>.</p>

                  <h3 id="limits">13. Честные ограничения</h3>
                  <ul>
                    <li>Стратегия классическая (textbook pairs) — только боковик, без трендового модуля.</li>
                    <li>Коинтеграция на истории не обещает коинтеграцию завтра.</li>
                    <li>Новости по ISS — эвристика, не полный fundamental research.</li>
                    <li>Slippage в paper — модельный (bps), не стакан MOEX; INTRADAY может быть хуже 40 bps.</li>
                    <li>ATAS-слой в TRINITY — прокси по OHLCV ISS, не полная лента сделок; с T-Invest sandbox точность исполнения вырастет.</li>
                    <li>Historical replay не заменяет брокерский demo (T-Invest sandbox) — следующий шаг к live.</li>
                    <li>Нужны месяцы чистого paper track-record, прежде чем судить об alpha.</li>
                  </ul>
                  <div class="callout">
                    Это research / decision-support, не индивидуальная инвестиционная рекомендация.
                    Параметры порогов живут в <code>application.yml</code> (<code>imoex.cointegration</code>,
                    <code>universe</code>, <code>microstructure</code>, <code>risk</code>, <code>regime</code>, <code>news</code>, <code>paper</code>).
                  </div>
                </article>
                """.replace("{{RESEARCH_BADGE}}", researchBadge);
    }

    /**
     * Инструкция для оператора: запуск, пульт, разделы UI, автопрогоны и алерты.
     */
    public String renderGuide() {
        String body = """
                <article class="strategy-doc">
                  <h2>Как пользоваться системой</h2>
                  <p class="lead">
                    Краткая инструкция для оператора TRINITY: от первого запуска до ежедневного мониторинга paper,
                    автопрогонов и уведомлений о новых сделках. Подробная теория стратегии — на странице
                    <a href="/view/strategy">Описание торговой стратегии</a>.
                  </p>

                  <nav class="strategy-toc" aria-label="Содержание">
                    <strong>Содержание</strong>
                    <ol>
                      <li><a href="#start">Первый запуск</a></li>
                      <li><a href="#ops">Пульт оператора</a></li>
                      <li><a href="#pages">Разделы меню</a></li>
                      <li><a href="#daily">Ежедневный цикл</a></li>
                      <li><a href="#auto">Автопрогоны (cron)</a></li>
                      <li><a href="#alerts">Алерты и звук</a></li>
                      <li><a href="#dual">Две книги: DAILY и INTRADAY</a></li>
                      <li><a href="#empty">Пустой journal — это нормально?</a></li>
                      <li><a href="#checklist">Чеклист</a></li>
                    </ol>
                  </nav>

                  <h3 id="start">1. Первый запуск</h3>
                  <ol class="pipeline">
                    <li><strong>Java 17+</strong> и <strong>Maven 3.9+</strong> установлены; вы в корне репозитория (там, где <code>pom.xml</code>).</li>
                    <li>Создайте <code>application-local.yml</code> в корне репо с паролем API и ключом <code>imoex.run.unlock</code> (без них приложение не стартует).</li>
                    <li>Запустите: <code>mvn -pl trinity-app -am spring-boot:run</code> и дождитесь <code>Started TrinityApplication</code>.</li>
                    <li>Откройте <a href="/view">http://localhost:8080/view</a> — спокойный дашборд (KPI и сигналы).</li>
                    <li>В <a href="/view/settings">Настройках</a> сохраните логин API; первый раз нажмите
                      <strong>«Анализ + скачать свечи»</strong> — скачает историю с MOEX ISS (может занять много минут).</li>
                    <li>Дальше обычно достаточно <strong>«Анализ + paper»</strong> — с дашборда или из настроек.</li>
                  </ol>
                  <div class="callout">
                    GET-страницы <code>/view/*</code> открываются без пароля. Кнопки пульта шлют POST на API —
                    нужны логин и пароль из <code>application-local.yml</code> (по умолчанию user <code>imoex</code>).
                  </div>

                  <h3 id="ops">2. Пульт и брокер (Настройки)</h3>
                  <p>
                    Полный <strong>пульт оператора</strong> и <strong>консоль брокера</strong> — только в
                    <a href="/view/settings">Настройках</a>. Дашборд — обзор: виджеты, «что сделать сейчас», сигналы.
                    На остальных страницах сверху компактная полоска: быстрый <strong>Анализ + paper</strong>
                    и ссылка в настройки.
                  </p>
                  <table class="params">
                    <thead><tr><th>Кнопка</th><th>Что делает</th></tr></thead>
                    <tbody>
                      <tr><td><strong>Анализ + paper</strong></td><td>Полный цикл обеих книг (DAILY → INTRADAY) без скачивания свечей. Типичный будний пересчёт.</td></tr>
                      <tr><td><strong>Анализ + скачать свечи</strong></td><td>То же, но с <code>refresh=true</code> — обновляет дневные и часовые свечи с биржи.</td></tr>
                      <tr><td><strong>Только новости / paper</strong></td><td>Быстро: новости MOEX/RSS + синхронизация paper без полного Engle–Granger.</td></tr>
                      <tr><td><strong>Walk-forward</strong></td><td>Пересчёт OOS-отчёта по топ-парам (daily).</td></tr>
                      <tr><td><strong>Скачать свечи</strong></td><td>Только загрузка данных, без анализа.</td></tr>
                    </tbody>
                  </table>
                  <p>
                    Логин и пароль сохраняются в <em>этом браузере</em> (localStorage). Журнал действий пульта — в блоке «Лог» в Настройках.
                  </p>

                  <h3 id="pages">3. Разделы верхнего меню</h3>
                  <table class="params">
                    <thead><tr><th>Раздел</th><th>Зачем открывать</th></tr></thead>
                    <tbody>
                      <tr><td><a href="/view">Дашборд</a></td><td>Спокойный обзор: KPI (Paper / Брокер / Final / Режим), сигналы и топ-пары.</td></tr>
                      <tr><td><a href="/view/settings">Настройки</a></td><td>Пульт оператора, алерты, лог, консоль брокера (токен, песочница, сверка).</td></tr>
                      <tr><td><a href="/view/final">Итог + новости</a></td><td><strong>Главный операторский экран</strong> — ENTER / REDUCE / WATCH / BLOCK после фундамента (DAILY), развёрнутый explain-panel (почему 0 или N строк), словарь действий и RSS-контекст для FA (не сигнал). INTRADAY FA/RSS пропускает.</td></tr>
                      <tr><td><a href="/view/signals">Сигналы</a></td><td>Сырые LONG / SHORT до новостного фильтра.</td></tr>
                      <tr><td><a href="/view/recommendations">Все рекомендации</a></td><td>Полная таблица технических рекомендаций.</td></tr>
                      <tr><td><a href="/view/paper">Paper</a></td><td>Журнал бумажных сделок: OPEN / CLOSED, PnL ₽, колонка «Книга» (DAILY / INTRADAY).</td></tr>
                      <tr><td><a href="/view/walk-forward">Walk-forward</a></td><td>Out-of-sample проверка на истории (не гарантия будущего).</td></tr>
                      <tr><td><a href="/view/strategy">Описание стратегии</a></td><td>Теория: коинтеграция, Z-score, режим боковика, выходы.</td></tr>
                    </tbody>
                  </table>
                  <p>
                    График пары: <code>/view/charts/ТИКЕР_Y/ТИКЕР_X</code> (ссылки есть из таблиц и paper).
                  </p>

                  <h3 id="daily">4. Ежедневный цикл оператора</h3>
                  <p>Рекомендуемый порядок после закрытия сессии или утром перед решением:</p>
                  <ol class="pipeline">
                    <li>Убедиться, что приложение запущено (<code>mvn -pl trinity-app -am spring-boot:run</code>).</li>
                    <li>Нажать «Анализ + paper» (или дождаться вечернего cron — см. ниже).</li>
                    <li>Открыть <a href="/view/final">Итог + новости</a> — что разрешено по DAILY после FA.</li>
                    <li>Открыть <a href="/view/paper">Paper</a> — что реально открылось в обеих книгах.</li>
                    <li>При сомнениях — график пары и виджет «Режим рынка» на дашборде (TREND блокирует новые входы).</li>
                  </ol>

                  <h3 id="auto">5. Автопрогоны (cron)</h3>
                  <p>
                    Пока сервер работает, планировщик сам гоняет анализ — ручная кнопка не обязательна каждый раз.
                    Статус последних прогонов пишется в лог пульта (строки <code>INTRADAY cron: …</code>).
                  </p>
                  <table class="params">
                    <thead><tr><th>Книга</th><th>Расписание (по умолчанию)</th><th>Что внутри</th></tr></thead>
                    <tbody>
                      <tr><td><strong>DAILY</strong></td><td>Пн–Пт <strong>19:05</strong></td><td>Дневные свечи → техника → FA → paper (<code>paper-journal.json</code>)</td></tr>
                      <tr><td><strong>INTRADAY</strong></td><td>Пн–Пт <strong>:05</strong> с 10:00 до 18:00</td><td>1H свечи ISS → техника → paper без FA (<code>paper-journal-intraday.json</code>), flatten ~18:30</td></tr>
                    </tbody>
                  </table>
                  <p>
                    Включение/выключение и cron — в <code>application.yml</code>:
                    <code>imoex.paper.auto-run-daily</code>, <code>auto-run-intraday</code>,
                    <code>daily-cron</code>, <code>intraday-cron</code>.
                  </p>
                  <div class="callout">
                    На выходных новых дневных свечей нет — вечерний DAILY почти ничего не меняет.
                    INTRADAY в нерабочие дни не запускается.
                  </div>

                  <h3 id="alerts">6. Алерты при новой paper-сделке</h3>
                  <p>
                    Если открыта <em>любая</em> страница <code>/view/*</code>, браузер раз в минуту опрашивает сервер.
                    При новом OPEN в paper вы получите:
                  </p>
                  <ul>
                    <li><strong>Баннер справа сверху</strong> в окне браузера (на macOS, Windows и Linux одинаково) + короткий звук (два тона);</li>
                    <li><strong>Системное уведомление ОС</strong> — если нажали «Уведомления macOS / Windows» и разрешили в браузере.</li>
                  </ul>
                  <table class="params">
                    <thead><tr><th>Платформа</th><th>Баннер в браузере</th><th>Уведомление ОС</th></tr></thead>
                    <tbody>
                      <tr><td><strong>macOS</strong></td><td>Правый верхний угол страницы</td><td>Notification Center — справа сверху (как у почты / Slack)</td></tr>
                      <tr><td><strong>Windows</strong></td><td>Правый верхний угол страницы</td><td>Центр уведомлений — обычно <strong>правый нижний</strong> угол (позицию задаёт Windows, не TRINITY)</td></tr>
                    </tbody>
                  </table>
                  <p>
                    В <a href="/view/settings">Настройках</a>: чекбоксы «Алерты при новой paper-сделке» и «Звук».
                    После ручного «Анализ + paper» опрос срабатывает сразу.
                    Вкладка может быть в фоне, но <strong>браузер должен быть запущен</strong> — это не push с сервера без открытой страницы.
                  </p>
                  <div class="callout">
                    Первый визит: уже существующие сделки в journal не спамят алертами — их id запоминаются автоматически.
                  </div>

                  <h3 id="dual">7. Две книги: DAILY и INTRADAY</h3>
                  <p>
                    Один цикл «Анализ + paper» всегда гоняет <strong>обе</strong> книги подряд. Ручного переключателя «сегодня daily / intraday» нет.
                  </p>
                  <ul>
                    <li><strong>DAILY</strong> — удержание несколько дней, проходит фундамент (новости), ~40% gross капитала.</li>
                    <li><strong>INTRADAY</strong> — 1H бары, без FA, закрытие к концу сессии, ~60% gross.</li>
                    <li>Лимиты <strong>независимы</strong>: если DAILY пустой, его доля <em>не перетекает</em> в INTRADAY.</li>
                  </ul>

                  <h3 id="empty">8. Пустой journal — это нормально?</h3>
                  <p>Да, если сейчас нет подходящих сигналов. Paper открывается только при:</p>
                  <ul>
                    <li>LONG / SHORT с подтверждённым разворотом Z (не WATCH);</li>
                    <li>для DAILY — итог ENTER или REDUCE после FA;</li>
                    <li>режим не TREND (ADX);</li>
                    <li>пара проходит фильтры качества (half-life, R², |Z| не у стопа);</li>
                    <li>есть свободный слот в книге.</li>
                  </ul>
                  <p>
                    На странице <a href="/view/paper">Paper</a> в пустом журнале показывается диагностика по каждой книге
                    (сколько пар, max |Z|, лидер).
                  </p>

                  <h3 id="checklist">9. Чеклист «всё работает»</h3>
                  <ul>
                    <li>В логе терминала: <code>Started TrinityApplication</code></li>
                    <li>Кнопка «Анализ + paper» завершается без 401 (логин/пароль верные)</li>
                    <li>В <code>data/candles/</code> есть JSON тикеров (после первого refresh)</li>
                    <li><a href="/view/final">Итог + новости</a> — таблица и explain-panel (пустая таблица нормальна: нет LONG/SHORT/WATCH после техники или всё отфильтровано до FA; читайте блоки «почему 0 строк»)</li>
                    <li>На дашборде видны виджеты режима рынка (SIDEWAYS / NEUTRAL / TREND)</li>
                    <li>При тестовом OPEN — баннер и звук в браузере (алерты включены)</li>
                  </ul>
                  <div class="callout">
                    TRINITY — research / decision-support, не автоисполнение у брокера и не гарантия прибыли.
                    Перед реальными деньгами — свой paper track-record и учёт издержек шорта.
                  </div>
                </article>
                """;
        return page("TRINITY — как пользоваться", body, nav("guide"));
    }

    private String regimeBanner(com.moex.cointegration.model.MarketRegimeSnapshot regime) {
        if (regime == null) {
            regime = com.moex.cointegration.model.MarketRegimeSnapshot.unknown();
        }
        String css = switch (regime.label()) {
            case "SIDEWAYS" -> "regime-ok";
            case "NEUTRAL" -> "regime-warn";
            case "TREND" -> "regime-bad";
            default -> "regime-muted";
        };
        String adx = Double.isNaN(regime.adx()) ? "—" : String.format("%.1f", regime.adx());
        return """
                <div class="regime-banner %s">
                  <strong>Режим рынка:</strong> %s (ADX=%s) — %s
                </div>
                """.formatted(css, escape(regime.label()), escape(adx), escape(regime.detail()));
    }

    private String summaryBlock(AnalysisReport report, int recCount, long actionable) {
        return """
                <section class="cards">
                  <div class="card"><span class="label">Дата анализа</span><span class="value">%s</span></div>
                  <div class="card"><span class="label">Акций</span><span class="value">%d</span></div>
                  <div class="card"><span class="label">Пар протестировано</span><span class="value">%d</span></div>
                  <div class="card"><span class="label">Коинтегрировано</span><span class="value">%d</span></div>
                  <div class="card"><span class="label">Сигналов входа</span><span class="value accent">%d</span></div>
                  <div class="card"><span class="label">Рекомендаций</span><span class="value">%d</span></div>
                </section>
                """.formatted(
                report.analysisDate(),
                report.tickersAnalyzed(),
                report.pairsTested(),
                report.cointegratedPairs(),
                actionable,
                recCount
        );
    }

    private String recommendationsTable(List<TradingRecommendation> rows, String emptyMessage) {
        if (rows.isEmpty()) {
            return "<p class=\"empty-msg\">" + escape(emptyMessage) + "</p>";
        }

        StringBuilder table = new StringBuilder();
        table.append("""
                <div class="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Пара Y / X</th>
                      <th>Сигнал</th>
                      <th>Z-score</th>
                      <th>Дата</th>
                      <th>Beta</th>
                      <th>Half-life</th>
                      <th>Sharpe</th>
                      <th>Рекомендация</th>
                      <th>Графики</th>
                    </tr>
                  </thead>
                  <tbody>
                """);

        for (TradingRecommendation r : rows) {
            table.append("<tr>");
            table.append("<td><strong>").append(escape(r.tickerY())).append("</strong> / ")
                    .append(escape(r.tickerX())).append("</td>");
            table.append("<td>").append(signalBadge(r.signal())).append("</td>");
            table.append("<td class=\"num\">").append(formatZ(r.currentZScore())).append("</td>");
            table.append("<td>").append(r.asOfDate()).append("</td>");
            table.append("<td class=\"num\">").append(formatNum(r.hedgeRatio())).append("</td>");
            table.append("<td class=\"num\">").append(formatNum(r.halfLifeDays())).append(" д</td>");
            table.append("<td class=\"num\">").append(formatNum(r.sharpeRatio())).append("</td>");
            table.append("<td class=\"details\">")
                    .append("<div class=\"summary\">").append(escape(r.summary())).append("</div>")
                    .append("<div class=\"explain\">").append(nl2br(escape(r.details()))).append("</div></td>");
            table.append("<td class=\"links\">")
                    .append(chartPageLink(r.tickerY(), r.tickerX()))
                    .append("</td>");
            table.append("</tr>");
        }

        table.append("</tbody></table></div>");
        return table.toString();
    }

    private String topPairsTable(List<PairAnalysisResult> pairs) {
        if (pairs.isEmpty()) {
            return "<p class=\"empty-msg\">Топ-пар нет.</p>";
        }

        StringBuilder table = new StringBuilder();
        table.append("""
                <div class="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>#</th>
                      <th>Пара</th>
                      <th>Sharpe</th>
                      <th>p-value</th>
                      <th>Half-life</th>
                      <th>Coverage</th>
                      <th>Max DD</th>
                      <th>Beta</th>
                      <th>График</th>
                    </tr>
                  </thead>
                  <tbody>
                """);

        int rank = 1;
        for (PairAnalysisResult p : pairs) {
            table.append("<tr>");
            table.append("<td>").append(rank++).append("</td>");
            table.append("<td><strong>").append(escape(p.tickerY())).append("</strong> / ")
                    .append(escape(p.tickerX())).append("</td>");
            table.append("<td class=\"num\">").append(formatNum(p.sharpeRatio())).append("</td>");
            table.append("<td class=\"num\">").append(formatNum(p.pValue())).append("</td>");
            table.append("<td class=\"num\">").append(formatNum(p.halfLifeDays())).append(" д</td>");
            String cov = p.coveragePercent() == null ? "—"
                    : String.format(Locale.ROOT, "%.1f%%", p.coveragePercent());
            table.append("<td class=\"num\" title=\"")
                    .append(escape(p.coverageWarning() == null ? "" : p.coverageWarning()))
                    .append("\">").append(cov).append("</td>");
            table.append("<td class=\"num\">").append(formatPct(p.maxDrawdown())).append("</td>");
            table.append("<td class=\"num\">").append(formatNum(p.hedgeRatio())).append("</td>");
            table.append("<td class=\"links\">").append(chartPageLink(p.tickerY(), p.tickerX())).append("</td>");
            table.append("</tr>");
        }

        table.append("</tbody></table></div>");
        return table.toString();
    }

    /** Страница интерактивного графика пары. */
    public String renderChartPage(String tickerY, String tickerX) {
        String body = """
                <div class="chart-head">
                  <a class="back" href="/view/signals">← к сигналам</a>
                  <h2>График пары {{Y}} / {{X}}</h2>
                  <p class="meta" id="chart-meta">Загрузка данных…</p>
                  <div class="legend">
                    <span class="lg buy">▲ зелёная стрелка — купить спред (после разворота к 0)</span>
                    <span class="lg sell">▼ красная стрелка — продать спред (после разворота к 0)</span>
                    <span class="lg exit">● серый — выход к равновесию</span>
                    <span class="lg kama">линия KAMA — адаптивная средняя спреда</span>
                  </div>
                  <div class="explain" id="chart-explain"></div>
                </div>
                <div class="chart-block">
                  <h3>1. Свечи {{Y}}</h3>
                  <div id="chart-price" class="chart"></div>
                </div>
                <div class="chart-block">
                  <h3>1b. Свечи {{X}}</h3>
                  <div id="chart-price-x" class="chart"></div>
                </div>
                <div class="chart-block">
                  <h3>3. Дивергенция: нормализованные цены (старт = 100)</h3>
                  <div id="chart-divergence" class="chart"></div>
                </div>
                <div class="chart-block">
                  <h3>4. Спред + KAMA (Kaufman Adaptive MA)</h3>
                  <div id="chart-spread" class="chart"></div>
                </div>
                <div class="chart-block">
                  <h3>5. Z-score и сигналы входа/выхода</h3>
                  <div id="chart-z" class="chart tall"></div>
                </div>
                <script src="https://unpkg.com/lightweight-charts@3.8.0/dist/lightweight-charts.standalone.production.js"></script>
                <script>
                (async function () {
                  const y = "{{Y}}";
                  const x = "{{X}}";
                  const resp = await fetch("/api/charts/" + y + "/" + x + "/data");
                  if (!resp.ok) {
                    const err = await resp.json().catch(() => ({ error: resp.statusText }));
                    document.getElementById("chart-meta").textContent = "Ошибка: " + (err.error || resp.status);
                    return;
                  }
                  const data = await resp.json();
                  document.getElementById("chart-meta").textContent =
                    "Сигнал: " + data.signal + " | Z=" + data.currentZScore.toFixed(2)
                    + " | beta=" + data.hedgeRatio.toFixed(3)
                    + " | half-life≈" + data.halfLifeDays.toFixed(0) + "д"
                    + " | Sharpe=" + data.sharpeRatio.toFixed(2);
                  document.getElementById("chart-explain").innerHTML =
                    '<div class="summary">' + escapeHtml(data.summary || "") + "</div>"
                    + "<div>" + escapeHtml(data.details || "").replace(/\\n/g, "<br>") + "</div>";

                  const common = { layout: { background: { color: "#ffffff" }, textColor: "#1a1a2e" },
                    grid: { vertLines: { color: "#eee" }, horzLines: { color: "#eee" } },
                    rightPriceScale: { borderColor: "#ddd" },
                    timeScale: { borderColor: "#ddd" } };

                  // Price candles Y
                  const priceEl = document.getElementById("chart-price");
                  const priceChart = LightweightCharts.createChart(priceEl, { ...common, height: 320 });
                  const candles = priceChart.addCandlestickSeries({
                    upColor: "#16a34a", downColor: "#dc2626", borderVisible: false,
                    wickUpColor: "#16a34a", wickDownColor: "#dc2626"
                  });
                  candles.setData(data.candlesY.map(b => ({
                    time: b.time, open: b.open, high: b.high, low: b.low, close: b.close
                  })));

                  const priceXEl = document.getElementById("chart-price-x");
                  const priceXChart = LightweightCharts.createChart(priceXEl, { ...common, height: 280 });
                  const candlesX = priceXChart.addCandlestickSeries({
                    upColor: "#16a34a", downColor: "#dc2626", borderVisible: false,
                    wickUpColor: "#16a34a", wickDownColor: "#dc2626"
                  });
                  candlesX.setData(data.candlesX.map(b => ({
                    time: b.time, open: b.open, high: b.high, low: b.low, close: b.close
                  })));

                  // Divergence normalized
                  const divEl = document.getElementById("chart-divergence");
                  const divChart = LightweightCharts.createChart(divEl, { ...common, height: 260 });
                  const ny = divChart.addLineSeries({ color: "#0f3460", lineWidth: 2, title: y });
                  const nx = divChart.addLineSeries({ color: "#e94560", lineWidth: 2, title: x });
                  ny.setData(data.normalizedY.map(p => ({ time: p.time, value: p.value })));
                  nx.setData(data.normalizedX.map(p => ({ time: p.time, value: p.value })));

                  // Spread + KAMA
                  const spEl = document.getElementById("chart-spread");
                  const spChart = LightweightCharts.createChart(spEl, { ...common, height: 260 });
                  const spread = spChart.addLineSeries({ color: "#0f3460", lineWidth: 2, title: "Spread" });
                  const kama = spChart.addLineSeries({ color: "#f59e0b", lineWidth: 2, title: "KAMA" });
                  spread.setData(data.spread.map(p => ({ time: p.time, value: p.value })));
                  kama.setData(data.kama.map(p => ({ time: p.time, value: p.value })));

                  // Z-score
                  const zEl = document.getElementById("chart-z");
                  const zChart = LightweightCharts.createChart(zEl, { ...common, height: 360 });
                  const zSeries = zChart.addLineSeries({ color: "#7c3aed", lineWidth: 2, title: "Z" });
                  zSeries.setData(data.zScore.map(p => ({ time: p.time, value: p.value })));
                  zSeries.createPriceLine({ price: 0, color: "#94a3b8", lineWidth: 1, lineStyle: 2, title: "0" });
                  zSeries.createPriceLine({ price: data.zEntry, color: "#dc2626", lineWidth: 1, lineStyle: 2, title: "+" + data.zEntry });
                  zSeries.createPriceLine({ price: -data.zEntry, color: "#16a34a", lineWidth: 1, lineStyle: 2, title: "-" + data.zEntry });

                  const markers = (data.markers || [])
                    .filter(m => m.series === "zscore")
                    .map(m => ({
                      time: m.time,
                      position: m.position,
                      color: m.color,
                      shape: m.shape,
                      text: m.text
                    }));
                  // lightweight-charts keeps one marker per time — keep last (current signal wins)
                  const byTime = {};
                  markers.forEach(m => { byTime[m.time] = m; });
                  zSeries.setMarkers(Object.values(byTime).sort((a, b) => a.time.localeCompare(b.time)));

                  [priceChart, priceXChart, divChart, spChart, zChart].forEach(c => c.timeScale().fitContent());

                  function escapeHtml(s) {
                    return String(s).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;");
                  }
                })();
                </script>
                """
                .replace("{{Y}}", escape(tickerY))
                .replace("{{X}}", escape(tickerX));
        return page("График " + tickerY + "/" + tickerX, body, nav("none"));
    }

    /** Лёгкий экран сигнала trend: M5 + DOM + entry/SL/TP. */
    public String renderTrendSignalPage() {
        String body = """
                <section class="signal-desk" id="trend-signal-desk">
                  <div class="signal-desk-head">
                    <div>
                      <p class="meta"><a href="/view">← дашборд</a></p>
                      <h2>Сигнал · Trend BR</h2>
                      <p class="meta" id="signal-desk-meta">Загрузка desk…</p>
                    </div>
                    <div class="signal-desk-actions">
                      <label class="check-label signal-follow-label">
                        <input type="checkbox" id="signal-desk-follow" checked>
                        Следить за свечой
                      </label>
                      <label class="check-label signal-follow-label">
                        <input type="checkbox" id="signal-desk-profile" checked>
                        Профиль
                      </label>
                      <label class="check-label signal-follow-label">
                        <input type="checkbox" id="signal-desk-footprint">
                        Footprint
                      </label>
                      <label class="check-label signal-follow-label">
                        <input type="checkbox" id="signal-desk-volume">
                        Объём M5
                      </label>
                      <button type="button" class="btn btn-ghost is-active" id="signal-desk-pad"
                              title="Отступ справа: текущая свеча не лепится к профилю объёмов"
                              aria-pressed="true">Автоотступ</button>
                      <button type="button" class="btn btn-ghost" id="signal-desk-fit">Весь график</button>
                      <button type="button" class="btn btn-ghost" id="signal-desk-refresh">Обновить</button>
                      <a class="btn btn-ghost" href="/view/settings#trend-playbook-settings">Режим</a>
                    </div>
                  </div>
                  <div class="signal-desk-strip" id="signal-desk-strip">
                    <div class="signal-chip"><span class="k">Инструмент</span><strong id="sig-instrument">—</strong></div>
                    <div class="signal-chip"><span class="k">Delivery</span><strong id="sig-delivery">—</strong></div>
                    <div class="signal-chip"><span class="k">Side</span><strong id="sig-side">—</strong></div>
                    <div class="signal-chip"><span class="k">Mode</span><strong id="sig-mode">—</strong></div>
                    <div class="signal-chip"><span class="k">Потенциал TP1</span><strong id="sig-potential">—</strong></div>
                    <div class="signal-chip"><span class="k">Paper сегодня</span><strong id="sig-paper-today">—</strong></div>
                    <div class="signal-chip"><span class="k">Statement</span><strong id="sig-paper-total">—</strong></div>
                  </div>
                  <p class="signal-summary" id="sig-summary">—</p>
                  <div class="signal-paper-panel" id="signal-paper-panel" hidden>
                    <p class="signal-brief-title">Paper statement · BR</p>
                    <p class="meta" id="signal-paper-meta">—</p>
                    <table class="signal-paper-table">
                      <thead><tr><th>Вход</th><th>Выход</th><th>Side</th><th>Qty</th><th>Reason</th><th>PnL</th><th>Tag</th></tr></thead>
                      <tbody id="signal-paper-body"></tbody>
                    </table>
                  </div>
                  <div class="signal-desk-grid">
                    <div class="signal-chart-wrap">
                      <button type="button" class="signal-chart-pad-btn is-on" id="signal-chart-pad"
                              title="Автоотступ справа (белое пространство у текущей свечи)"
                              aria-pressed="true" aria-label="Автоотступ">⇆</button>
                      <div id="signal-chart" class="chart signal-chart"></div>
                      <div id="signal-volume-wrap" class="signal-volume-wrap" hidden>
                        <div id="signal-volume" class="chart signal-volume"></div>
                      </div>
                      <p class="meta" id="signal-chart-hint">
                        M5 · чек-лист:
                        <span class="lg-hi">HI/LO</span> тренд ·
                        <span class="lg-hist">HIST</span> история ·
                        <span class="lg-zone">TOP/BOT</span> зоны ·
                        <span class="lg-hist">профиль</span> горизонтальный VAP ·
                        footprint · ENTRY/SL/TP при сетапе
                      </p>
                      <div class="signal-brief" id="signal-desk-brief" aria-live="polite">
                        <p class="signal-brief-title">
                          Сейчас на рынке
                          <button type="button" class="btn btn-ghost btn-xs" id="sig-kick-btn" title="Сбросить залипание: day-lock + one-setup + cooldown">
                            Пинок робота
                          </button>
                        </p>
                        <div class="signal-brief-body" id="signal-desk-brief-body">Загрузка среза…</div>
                        <div class="signal-compliance" id="signal-desk-compliance" aria-label="Чек-лист">
                          <p class="meta" id="signal-compliance-meta">Чек-лист…</p>
                        </div>
                      </div>
                    </div>
                    <aside class="signal-dom" aria-label="Стакан">
                      <div class="signal-dom-head">
                        <h3>Стакан · DOM</h3>
                        <p class="meta" id="signal-dom-meta">—</p>
                      </div>
                      <div class="signal-dom-imbalance" id="signal-dom-imbalance" hidden>
                        <div class="signal-dom-imb-bid" id="signal-dom-imb-bid"></div>
                        <div class="signal-dom-imb-ask" id="signal-dom-imb-ask"></div>
                      </div>
                      <div class="signal-dom-ladder-wrap">
                        <div class="signal-dom-ladder-head">
                          <span title="Лимиты на покупку">Bid</span>
                          <span title="Объём в стакане">Qty</span>
                          <span>Цена</span>
                          <span title="Объём в стакане">Qty</span>
                          <span title="Лимиты на продажу">Ask</span>
                          <span title="Проторговано: buy × sell">Tape</span>
                        </div>
                        <div class="signal-dom-ladder" id="signal-dom-body" role="table" aria-label="Биржевой стакан">
                          <div class="signal-dom-empty">Загрузка DOM…</div>
                        </div>
                      </div>
                      <p class="meta signal-dom-legend">
                        Зелёная зона — bids · красная — asks · Tape = агрессор buy×sell (~90м)
                      </p>
                    </aside>
                  </div>
                </section>
                <script src="https://unpkg.com/lightweight-charts@3.8.0/dist/lightweight-charts.standalone.production.js"></script>
                <script>
                (function () {
                  let chart = null;
                  let volumeChart = null;
                  let candleSeries = null;
                  let volumeSeries = null;
                  let priceLines = [];
                  let lastOverlayKey = "";
                  let overlayStructure = {};
                  let lastCandleTime = null;
                  let lastBarsRaw = [];
                  let userPinned = false;
                  let followLive = true;
                  let rightPadOn = true;
                  let showVolume = false;
                  let showProfile = true;
                  let showFootprint = false;
                  let lastProfile = [];
                  let lastFootprint = [];
                  const DESK_MS = 8000;
                  const BOOK_MS = 2000;
                  const RIGHT_PAD_ON = 22;
                  const RIGHT_PAD_OFF = 4;
                  const HI_LO_COLOR = "#b91c1c";
                  const ZONE_EDGE = "#6d28d9";

                  function $(id) { return document.getElementById(id); }
                  function fmtPot(v) {
                    if (v == null || typeof v !== "number" || !isFinite(v)) return "—";
                    return "~" + (v >= 0 ? "+" : "") + Math.round(v).toLocaleString("ru-RU") + " ₽";
                  }
                  function fmtPnl(v) {
                    if (v == null || typeof v !== "number" || !isFinite(v)) return "—";
                    const s = (v >= 0 ? "+" : "") + Math.round(v).toLocaleString("ru-RU") + " ₽";
                    return s;
                  }
                  function shortTime(iso) {
                    if (!iso) return "—";
                    const m = String(iso).match(/T(\\d{2}:\\d{2})/);
                    return m ? m[1] : iso;
                  }
                  function renderPaper(paper) {
                    const st = (paper && paper.statement) || {};
                    const todayEl = $("sig-paper-today");
                    const totalEl = $("sig-paper-total");
                    if (todayEl) {
                      todayEl.textContent = fmtPnl(st.todayPnlRub);
                      todayEl.classList.toggle("is-buy", (st.todayPnlRub || 0) > 0);
                      todayEl.classList.toggle("is-sell", (st.todayPnlRub || 0) < 0);
                    }
                    if (totalEl) {
                      const w = (st.wins || 0) + "/" + (st.losses || 0);
                      totalEl.textContent = fmtPnl(st.realizedPnlRub) + " · " + w;
                      totalEl.classList.toggle("is-buy", (st.realizedPnlRub || 0) > 0);
                      totalEl.classList.toggle("is-sell", (st.realizedPnlRub || 0) < 0);
                    }
                    const panel = $("signal-paper-panel");
                    const body = $("signal-paper-body");
                    const meta = $("signal-paper-meta");
                    const rows = (paper && paper.recentTrades) || [];
                    if (!panel || !body) return;
                    if (!rows.length) {
                      panel.hidden = true;
                      return;
                    }
                    panel.hidden = false;
                    if (meta) {
                      meta.textContent = "Закрыто " + (st.closedCount || rows.length)
                        + " · сегодня " + fmtPnl(st.todayPnlRub)
                        + " · всего " + fmtPnl(st.realizedPnlRub)
                        + (st.note ? " · " + st.note : "");
                    }
                    body.innerHTML = rows.map(function (t) {
                      const pnl = t.pnlRub;
                      const cls = pnl > 0 ? "is-buy" : (pnl < 0 ? "is-sell" : "");
                      return "<tr>"
                        + "<td>" + shortTime(t.openedAt) + "</td>"
                        + "<td>" + shortTime(t.closedAt) + "</td>"
                        + "<td>" + (t.side || "—") + "</td>"
                        + "<td>" + (t.qty != null ? t.qty : "—") + "</td>"
                        + "<td>" + (t.exitReason || "—") + "</td>"
                        + "<td class='" + cls + "'>" + fmtPnl(pnl) + "</td>"
                        + "<td>" + (t.tag || "—") + "</td>"
                        + "</tr>";
                    }).join("");
                  }
                  function fmtPx(v) {
                    if (v == null || typeof v !== "number" || !isFinite(v)) return "—";
                    return v.toFixed(2);
                  }
                  function buildOperatorBrief(data) {
                    const bars = data.bars || [];
                    const last = bars.length ? bars[bars.length - 1] : null;
                    const close = last && typeof last.close === "number" ? last.close : null;
                    const look1h = bars.slice(Math.max(0, bars.length - 12));
                    const lookSession = bars.slice(Math.max(0, bars.length - 80));
                    let peak1h = null, trough1h = null;
                    look1h.forEach(function (b) {
                      if (!b) return;
                      if (typeof b.high === "number") peak1h = peak1h == null ? b.high : Math.max(peak1h, b.high);
                      if (typeof b.low === "number") trough1h = trough1h == null ? b.low : Math.min(trough1h, b.low);
                    });
                    let peakS = null, troughS = null;
                    lookSession.forEach(function (b) {
                      if (!b) return;
                      if (typeof b.high === "number") peakS = peakS == null ? b.high : Math.max(peakS, b.high);
                      if (typeof b.low === "number") troughS = troughS == null ? b.low : Math.min(troughS, b.low);
                    });
                    const pts = function (a, b) {
                      if (a == null || b == null || !isFinite(a) || !isFinite(b)) return null;
                      return Math.round(Math.abs(a - b) / 0.01);
                    };
                    const signedPts = function (from, to) {
                      if (from == null || to == null || !isFinite(from) || !isFinite(to)) return null;
                      return Math.round((to - from) / 0.01);
                    };
                    const nearZone = function (z, px) {
                      if (!z || px == null) return false;
                      const pad = 0.12;
                      return px >= (z.low - pad) && px <= (z.high + pad);
                    };
                    const relZone = function (z, px, name) {
                      if (!z || px == null) return "";
                      if (px > z.high + 0.05) return "выше " + name + " (+" + pts(px, z.high) + "п)";
                      if (px < z.low - 0.05) return "ниже " + name + " (−" + pts(z.low, px) + "п)";
                      return "в полосе " + name + " (" + fmtPx(z.low) + "–" + fmtPx(z.high) + ")";
                    };
                    const esc = function (t) {
                      return String(t == null ? "" : t)
                        .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
                    };
                    const minsLabel = function (m) {
                      if (m == null || !isFinite(m)) return "";
                      const abs = Math.abs(Math.round(m));
                      if (abs < 60) return (m >= 0 ? "через " : "") + abs + " мин" + (m < 0 ? " назад" : "");
                      const h = Math.floor(abs / 60);
                      const mm = abs % 60;
                      const core = h + "ч" + (mm ? " " + mm + "м" : "");
                      return m >= 0 ? "через " + core : core + " назад";
                    };

                    const sit = data.situation || {};
                    const sig = data.signal || {};
                    const plan = data.plan || {};
                    const st = data.structure || {};
                    const paperSt = (data.paper && data.paper.statement) || {};
                    const events = data.events || [];
                    const manage = data.manage || {};
                    const posture = sit.posture || "SCANNING";
                    const state = plan.state || sig.state || data.engineState || sit.engineState || "—";
                    const reason = sit.why || data.summary || plan.rationale || sig.summary || "";
                    const side = (sit.setupLevels && sit.setupLevels.side)
                      || sig.side || plan.side || "NONE";
                    const mode = (sit.setupLevels && sit.setupLevels.mode) || plan.mode || sig.mode || "";
                    const htf = sit.htf || st.htf || "?";
                    const bias = sit.bias || st.bias || "?";
                    const mkt = sit.marketState || st.marketState || "?";
                    const tapeLive = (data.barsSource === "tape")
                      || String(data.barsSource || "").indexOf("tape") >= 0;
                    const liveBroker = !!sit.liveExecution || !!data.liveExecution;
                    const autoJ = !!sit.autoExecution || !!data.autoExecution;

                    // ——— 1. Рынок сейчас ———
                    let marketHtml = "<p class='signal-brief-kicker'>Рынок сейчас</p><p>";
                    marketHtml += "BR <strong>" + (close != null ? fmtPx(close) : "—") + "</strong>";
                    const topRel = relZone(st.zoneTop, close, "TOP");
                    const botRel = relZone(st.zoneBottom, close, "BOT");
                    if (topRel && nearZone(st.zoneTop, close)) marketHtml += " — " + topRel;
                    else if (botRel && nearZone(st.zoneBottom, close)) marketHtml += " — " + botRel;
                    else if (topRel && botRel) marketHtml += " — между зонами: " + topRel + ", " + botRel;
                    else if (topRel || botRel) marketHtml += " — " + (topRel || botRel);

                    if (sit.dayMovePoints != null) {
                      const dm = sit.dayMovePoints;
                      marketHtml += ". День " + (dm >= 0 ? "+" : "") + dm + "п от открытия сессии";
                      if (dm <= -80) marketHtml += " (dump — macro BEARISH proxy)";
                      else if (dm >= 80) marketHtml += " (rally — macro BULLISH proxy)";
                    }
                    const drop1 = (peak1h != null && close != null && peak1h > close + 0.08) ? pts(peak1h, close) : null;
                    const rally1 = (trough1h != null && close != null && close > trough1h + 0.08) ? pts(close, trough1h) : null;
                    if (drop1 != null && (rally1 == null || drop1 >= rally1)) {
                      marketHtml += ". За ~1ч срыв с " + fmtPx(peak1h) + " (−" + drop1 + "п)";
                    } else if (rally1 != null) {
                      marketHtml += ". За ~1ч отскок от " + fmtPx(trough1h) + " (+" + rally1 + "п)";
                    }
                    if (peakS != null && troughS != null && close != null) {
                      const span = pts(peakS, troughS);
                      if (span != null && span >= 40) {
                        marketHtml += ". В сессии диапазон ~" + span + "п ("
                          + fmtPx(troughS) + "–" + fmtPx(peakS) + ")";
                      }
                    }
                    marketHtml += ". Режим <code>" + esc(mkt) + "</code>, HTF=" + esc(htf)
                      + ", bias=" + esc(bias) + ".";
                    if (sit.structureNote) {
                      marketHtml += " " + esc(String(sit.structureNote).slice(0, 180));
                    }
                    marketHtml += " Лента " + (tapeLive ? "живая" : "архив/ISS")
                      + ", ~" + (data.barCount || 0) + " M5.</p>";

                    if (st.zoneTop || st.zoneBottom) {
                      marketHtml += "<p class='signal-brief-note'>Зоны дня: ";
                      if (st.zoneTop) {
                        marketHtml += "<span class='lg-zone'>TOP</span> "
                          + fmtPx(st.zoneTop.low) + "–" + fmtPx(st.zoneTop.high);
                      }
                      if (st.zoneBottom) {
                        marketHtml += (st.zoneTop ? ", " : "")
                          + "<span class='lg-zone-bot'>BOT</span> "
                          + fmtPx(st.zoneBottom.low) + "–" + fmtPx(st.zoneBottom.high);
                      }
                      marketHtml += ". HI/LO " + fmtPx(st.lookbackHigh) + " / " + fmtPx(st.lookbackLow) + ".";
                      if (st.previousZeroPoint != null) {
                        marketHtml += " Zero §4 " + fmtPx(st.previousZeroPoint)
                          + (st.zeroPointBroken ? " (пробита)." : " (держится).");
                      }
                      marketHtml += "</p>";
                    }

                    if (sit.domBidLots5 != null) {
                      const skew = sit.domSkew || 0;
                      marketHtml += "<p class='signal-brief-note'>Стакан (топ-5): bid "
                        + Math.round(sit.domBidLots5) + " / ask " + Math.round(sit.domAskLots5)
                        + " лотов — "
                        + (skew > 40 ? "давление покупателей" : (skew < -40 ? "давление продавцов" : "баланс"))
                        + ".</p>";
                    }

                    // ——— 2. Робот ———
                    let robotHtml = "<p class='signal-brief-kicker'>Робот</p>";
                    const postureRu = ({
                      IN_TRADE: "В СДЕЛКЕ",
                      WAITING_FILL: "ЖДЁТ ИСПОЛНЕНИЯ",
                      WATCHING_ZONE: "СМОТРИТ ЗОНУ",
                      NOT_IN_TRADE: "НЕ В СДЕЛКЕ",
                      SCANNING: "СКАНИРУЕТ"
                    })[posture] || posture;

                    robotHtml += "<p><strong>" + postureRu + "</strong> · <code>" + esc(state) + "</code>";
                    if (side && side !== "NONE") robotHtml += " · " + esc(side) + (mode ? (" " + esc(mode)) : "");
                    robotHtml += " · канал: "
                      + (liveBroker ? "LIVE (осторожно)" : (autoJ ? "paper AUTO_JOURNAL" : "SIGNAL_ONLY"))
                      + ".</p>";

                    if (posture === "IN_TRADE") {
                      robotHtml += "<p><strong>Почему в сделке:</strong> " + esc(reason) + "</p>";
                      if (sit.setupLevels) {
                        const lv = sit.setupLevels;
                        robotHtml += "<p class='signal-brief-note'>Уровни: entry "
                          + fmtPx(lv.entry) + " · SL " + fmtPx(lv.stop)
                          + " · TP1 " + fmtPx(lv.tp1) + " · TP2 " + fmtPx(lv.tp2)
                          + (lv.qty != null ? (" · qty " + lv.qty) : "") + ".</p>";
                      }
                      if (manage.note) {
                        robotHtml += "<p class='signal-brief-note'>Manage §12: " + esc(manage.note)
                          + (manage.movedToBe ? " · уже BE" : "")
                          + (manage.trailing ? " · trail" : "") + ".</p>";
                      }
                    } else if (posture === "WAITING_FILL") {
                      robotHtml += "<p><strong>Почему ждёт fill:</strong> " + esc(reason) + "</p>";
                      if (sit.activeLock) {
                        const lk = sit.activeLock;
                        robotHtml += "<p class='signal-brief-note'>Lock зоны "
                          + fmtPx(lk.low) + "–" + fmtPx(lk.high)
                          + " · mid " + fmtPx(lk.mid)
                          + " — unlock ≥40п от mid или новый день.</p>";
                      }
                      if (sit.setupLevels) {
                        const lv = sit.setupLevels;
                        robotHtml += "<p class='signal-brief-note'>Сетка: avg "
                          + fmtPx(lv.entry) + " · SL " + fmtPx(lv.stop)
                          + " · TP1 " + fmtPx(lv.tp1) + ".</p>";
                      }
                      robotHtml += "<p class='signal-brief-note'>Следующий шаг: дождаться касания лимитов на M5; "
                        + "при уходе цены далеко — unlock и новый поиск.</p>";
                    } else {
                      robotHtml += "<p><strong>Почему не в сделке:</strong> " + esc(reason) + "</p>";
                      const r = String(reason).toUpperCase();
                      let next = "Наблюдение: при выполнении §6–8 появится BUY/SELL.";
                      if (r.indexOf("MAX FILLS") >= 0 || r.indexOf("MAX SETUPS") >= 0) {
                        next = "Дневной лимит сетапов исчерпан — новых входов сегодня не будет.";
                      } else if (r.indexOf("MAX DAY LOSS") >= 0) {
                        next = "Сработал дневной лимит убытка — робот в паузе до завтра.";
                      } else if (r.indexOf("EVENT") >= 0) {
                        next = "Календарный blackout вокруг события — ждите окончания окна.";
                      } else if (r.indexOf("SESSION") >= 0) {
                        next = "Вне торгового окна playbook — входы откроются в сессии.";
                      } else if (r.indexOf("§6") >= 0 || r.indexOf("PROFILE") >= 0) {
                        next = "Нет валидного профиля на активном уровне — ждите касание TOP/BOT с объёмом или пинок.";
                      } else if (r.indexOf("MACRO") >= 0 || r.indexOf("KNIFE") >= 0 || r.indexOf("FA/") >= 0) {
                        next = "Macro-proxy режет нож против дня — не ловим дно/потолок против импульса.";
                      } else if (r.indexOf("HTF") >= 0 || htf === "FLAT") {
                        next = "HTF плоский: приоритет bounce у day-locked TOP/BOT; RETEST после break+hold.";
                      } else if (posture === "WATCHING_ZONE") {
                        next = "Зона размечена — ждите bounce/retest confirm на следующей M5 у фиолетовой полосы.";
                      } else if (r.indexOf("COOLDOWN") >= 0) {
                        next = "Cooldown после стопа — пауза до конца таймера.";
                      }
                      robotHtml += "<p class='signal-brief-note'>Что делать: " + next + "</p>";
                    }

                    if (sit.setupsToday != null) {
                      robotHtml += "<p class='signal-brief-note'>Квота: fills сегодня "
                        + sit.setupsToday
                        + (sit.maxSetupsPerDay > 0 ? (" / " + sit.maxSetupsPerDay) : " (без лимита)")
                        + (sit.realizedDayPnlRub != null
                          ? (" · day PnL engine " + (sit.realizedDayPnlRub >= 0 ? "+" : "")
                            + Math.round(sit.realizedDayPnlRub) + " ₽")
                          : "")
                        + (sit.maxDayLossRub > 0 ? (" · day-loss cap −" + sit.maxDayLossRub + " ₽") : "")
                        + ".</p>";
                    }

                    // ——— 3. Новости / календарь ———
                    let newsHtml = "<p class='signal-brief-kicker'>Новости и события</p>";
                    const upcoming = events.filter(function (e) { return e.status === "UPCOMING"; }).slice(0, 3);
                    const recent = events.filter(function (e) { return e.status === "PAST"; }).slice(0, 2);

                    if (!events.length) {
                      newsHtml += "<p class='signal-brief-note'>В календаре BR рядом нет EIA/API окон. "
                        + "Живой RSS сюда не подмешивается (только event-calendar).</p>";
                    } else {
                      if (sit.eventBlackout) {
                        newsHtml += "<p><strong>Blackout сейчас:</strong> " + esc(sit.eventBlock) + "</p>";
                      }
                      if (upcoming.length) {
                        newsHtml += "<p>Скоро: ";
                        newsHtml += upcoming.map(function (e) {
                          return "<strong>" + esc(e.title) + "</strong> (" + esc(e.type) + ") "
                            + esc(e.date) + " " + esc(e.time) + " MSK — " + minsLabel(e.minutesTo)
                            + (e.inBlackout ? " · уже в блоке" : "");
                        }).join("; ") + ".</p>";
                      }
                      if (recent.length) {
                        newsHtml += "<p class='signal-brief-note'>Недавно: ";
                        newsHtml += recent.map(function (e) {
                          return esc(e.title) + " " + minsLabel(e.minutesTo);
                        }).join("; ") + ".";
                        // price reaction proxy
                        if (sit.dayMovePoints != null && Math.abs(sit.dayMovePoints) >= 40) {
                          newsHtml += " В цене дня уже виден импульс "
                            + (sit.dayMovePoints >= 0 ? "+" : "") + sit.dayMovePoints
                            + "п — возможная реакция на фон/событие (прокси, не факт surprise).";
                        } else {
                          newsHtml += " Явной «реакции дня» по импульсу не видно (день спокойный).";
                        }
                        newsHtml += "</p>";
                      }
                      if (!upcoming.length && !recent.length && !sit.eventBlackout) {
                        const next = events[0];
                        newsHtml += "<p>Ближайшее в горизонте: <strong>" + esc(next.title) + "</strong> "
                          + esc(next.date) + " " + esc(next.time) + " — " + minsLabel(next.minutesTo) + ".</p>";
                      }
                      newsHtml += "<p class='signal-brief-note'>" + esc(sit.newsDisclaimer
                        || "FA = календарь + macro-proxy по цене, не лента новостей.") + "</p>";
                    }

                    if (sit.sessionBlock) {
                      newsHtml += "<p class='signal-brief-note'>Сессия: " + esc(sit.sessionBlock) + "</p>";
                    } else if (sit.sessionTradable === false) {
                      newsHtml += "<p class='signal-brief-note'>Сессия: вне окна входов.</p>";
                    } else {
                      newsHtml += "<p class='signal-brief-note'>Сессия tradable "
                        + esc(sit.sessionOpen || "09:00") + "–" + esc(sit.sessionClose || "23:50")
                        + " (с буферами open/close).</p>";
                    }

                    // ——— 4. Paper ———
                    let paperHtml = "";
                    if (paperSt && typeof paperSt.todayPnlRub === "number") {
                      const tag = (paperSt.todayPnlRub >= 0 ? "+" : "")
                        + Math.round(paperSt.todayPnlRub).toLocaleString("ru-RU") + " ₽";
                      paperHtml = "<p class='signal-brief-kicker'>Paper</p><p>Сегодня <strong>"
                        + tag + "</strong> · W/L " + (paperSt.wins || 0) + "/" + (paperSt.losses || 0)
                        + " · statement "
                        + ((paperSt.realizedPnlRub >= 0 ? "+" : "")
                          + Math.round(paperSt.realizedPnlRub || 0).toLocaleString("ru-RU") + " ₽")
                        + ".</p>";
                    }

                    return marketHtml + robotHtml + newsHtml + paperHtml;
                  }
                  function renderCompliance(data) {
                    const el = $("signal-compliance-meta");
                    if (!el) return;
                    const rows = data.checklistCompliance || [];
                    let core = 0, ext = 0;
                    rows.forEach(function (r) {
                      if (r.status === "IMPLEMENTED") core++;
                      else if (r.status === "EXTENSION") ext++;
                    });
                    let t = "Чек-лист §1–18: " + core + " IMPLEMENTED · hardenings: " + ext + " EXTENSION";
                    if (data.blockReason) {
                      t += " · блок: " + String(data.blockReason).slice(0, 90);
                    }
                    if (data.setupsToday != null) {
                      t += " · fillsToday=" + data.setupsToday;
                    }
                    el.textContent = t;
                  }
                  function clearLines() {
                    if (!candleSeries) return;
                    priceLines.forEach(function (l) { try { candleSeries.removePriceLine(l); } catch (_) {} });
                    priceLines = [];
                  }
                  function addLine(price, color, title, opts) {
                    if (!candleSeries || !(price > 0)) return;
                    const o = opts || {};
                    const line = candleSeries.createPriceLine({
                      price: price,
                      color: color,
                      lineWidth: o.lineWidth != null ? o.lineWidth : 1,
                      lineStyle: o.lineStyle != null ? o.lineStyle : 2,
                      axisLabelVisible: o.axisLabelVisible !== false,
                      title: title
                    });
                    priceLines.push(line);
                  }
                  function finitePrice(v) {
                    return typeof v === "number" && isFinite(v) && v > 0;
                  }
                  function ensureZoneOverlay() {
                    const el = $("signal-chart");
                    if (!el) return null;
                    let ov = $("signal-zone-overlay");
                    if (!ov) {
                      ov = document.createElement("div");
                      ov.id = "signal-zone-overlay";
                      ov.className = "signal-zone-overlay";
                      el.appendChild(ov);
                    }
                    return ov;
                  }
                  function layoutZoneBands() {
                    const ov = ensureZoneOverlay();
                    if (!ov || !candleSeries) return;
                    ov.innerHTML = "";
                    const st = overlayStructure || {};
                    const items = [];
                    if (st.zoneTop) items.push({ z: st.zoneTop, role: "top", title: "TOP" });
                    if (st.zoneBottom) items.push({ z: st.zoneBottom, role: "bot", title: "BOT" });
                    items.forEach(function (item) {
                      if (!finitePrice(item.z.high) || !finitePrice(item.z.low)) return;
                      const y1 = candleSeries.priceToCoordinate(item.z.high);
                      const y2 = candleSeries.priceToCoordinate(item.z.low);
                      if (y1 == null || y2 == null) return;
                      const top = Math.min(y1, y2);
                      const height = Math.abs(y2 - y1);
                      if (!(height >= 1)) return;
                      const band = document.createElement("div");
                      band.className = "signal-zone-band is-" + item.role;
                      band.style.height = Math.max(height, 14) + "px";
                      // Keep band centered on true mid when we pad for visibility
                      if (height < 14) {
                        band.style.top = (top - (14 - height) / 2) + "px";
                      } else {
                        band.style.top = top + "px";
                      }
                      const label = document.createElement("span");
                      label.className = "signal-zone-label";
                      label.textContent = item.title + " "
                        + Number(item.z.low).toFixed(2) + "–" + Number(item.z.high).toFixed(2);
                      band.appendChild(label);
                      ov.appendChild(band);
                    });
                  }
                  function ensureProfileOverlay() {
                    const el = $("signal-chart");
                    if (!el) return null;
                    let ov = $("signal-profile-overlay");
                    if (!ov) {
                      ov = document.createElement("div");
                      ov.id = "signal-profile-overlay";
                      ov.className = "signal-profile-overlay";
                      el.appendChild(ov);
                    }
                    return ov;
                  }
                  function layoutProfile(levels) {
                    const ov = ensureProfileOverlay();
                    if (!ov || !candleSeries) return;
                    ov.innerHTML = "";
                    ov.hidden = !showProfile;
                    if (!showProfile || !levels || !levels.length) return;
                    const maxW = 72;
                    levels.forEach(function (lvl) {
                      if (!finitePrice(lvl.price) || !(lvl.volume > 0)) return;
                      const y = candleSeries.priceToCoordinate(lvl.price);
                      if (y == null) return;
                      const bar = document.createElement("div");
                      bar.className = "signal-vap-bar";
                      const w = Math.max(2, Math.round((lvl.strength || 0) * maxW));
                      bar.style.top = (y - 1) + "px";
                      bar.style.width = w + "px";
                      bar.title = Number(lvl.price).toFixed(2) + " · vol " + Math.round(lvl.volume);
                      ov.appendChild(bar);
                    });
                  }
                  function ensureFootprintOverlay() {
                    const el = $("signal-chart");
                    if (!el) return null;
                    let ov = $("signal-footprint-overlay");
                    if (!ov) {
                      ov = document.createElement("div");
                      ov.id = "signal-footprint-overlay";
                      ov.className = "signal-footprint-overlay";
                      el.appendChild(ov);
                    }
                    return ov;
                  }
                  function layoutFootprint(fps) {
                    const ov = ensureFootprintOverlay();
                    if (!ov || !candleSeries || !chart) return;
                    ov.innerHTML = "";
                    ov.hidden = !showFootprint;
                    if (!showFootprint || !fps || !fps.length) return;
                    const ts = chart.timeScale();
                    // last 8 footprint bars only (readable)
                    const slice = fps.slice(Math.max(0, fps.length - 8));
                    slice.forEach(function (fb) {
                      const t = toChartTime(fb.time);
                      if (t == null) return;
                      const x = ts.timeToCoordinate(t);
                      if (x == null) return;
                      const col = document.createElement("div");
                      col.className = "signal-fp-col";
                      col.style.left = (x - 18) + "px";
                      const levels = (fb.levels || []).slice(0, 14);
                      levels.forEach(function (lv) {
                        if (!finitePrice(lv.price)) return;
                        const y = candleSeries.priceToCoordinate(lv.price);
                        if (y == null) return;
                        const cell = document.createElement("div");
                        cell.className = "signal-fp-cell";
                        cell.style.top = (y - 6) + "px";
                        const buy = lv.buy || 0;
                        const sell = lv.sell || 0;
                        cell.innerHTML = "<span class=\\"b\\">" + buy + "</span>"
                          + "<span class=\\"x\\">×</span>"
                          + "<span class=\\"s\\">" + sell + "</span>";
                        col.appendChild(cell);
                      });
                      ov.appendChild(col);
                    });
                  }
                  function layoutMarketOverlays() {
                    layoutZoneBands();
                    layoutProfile(lastProfile);
                    layoutFootprint(lastFootprint);
                  }
                  function overlayKey(plan, sig, st) {
                    const zt = st && st.zoneTop ? (st.zoneTop.low + "/" + st.zoneTop.high) : "";
                    const zb = st && st.zoneBottom ? (st.zoneBottom.low + "/" + st.zoneBottom.high) : "";
                    return [
                      st && st.lookbackHigh, st && st.lookbackLow,
                      st && st.historicalHigh, st && st.historicalLow, st && st.previousZeroPoint,
                      zt, zb,
                      plan && plan.side, plan && plan.entry, plan && plan.stopLoss,
                      plan && plan.tp1, plan && plan.actionable, sig && sig.side
                    ].join("|");
                  }
                  function applyOverlays(plan, sig, candles, structure) {
                    const st = structure || {};
                    overlayStructure = st;
                    const key = overlayKey(plan, sig, st);
                    if (key !== lastOverlayKey) {
                      lastOverlayKey = key;
                      clearLines();
                      // §3 historical — dashed gray
                      if (finitePrice(st.historicalHigh)
                          && st.historicalHigh !== st.lookbackHigh) {
                        addLine(st.historicalHigh, "#94a3b8", "HIST↑", { lineWidth: 1, lineStyle: 2 });
                      }
                      if (finitePrice(st.historicalLow)
                          && st.historicalLow !== st.lookbackLow) {
                        addLine(st.historicalLow, "#94a3b8", "HIST↓", { lineWidth: 1, lineStyle: 2 });
                      }
                      // §4 zero
                      if (finitePrice(st.previousZeroPoint)) {
                        addLine(st.previousZeroPoint, "#ca8a04", "ZERO", { lineWidth: 1, lineStyle: 2 });
                      }
                      // §5 current trend extremes — thick solid red
                      if (finitePrice(st.lookbackHigh)) {
                        addLine(st.lookbackHigh, HI_LO_COLOR, "HI", { lineWidth: 2, lineStyle: 0 });
                      }
                      if (finitePrice(st.lookbackLow)) {
                        addLine(st.lookbackLow, HI_LO_COLOR, "LO", { lineWidth: 2, lineStyle: 0 });
                      }
                      // Zone edges as thin purple guides (fill = HTML band)
                      if (st.zoneTop) {
                        if (finitePrice(st.zoneTop.high)) {
                          addLine(st.zoneTop.high, ZONE_EDGE, "TOP↑", { lineWidth: 1, lineStyle: 0 });
                        }
                        if (finitePrice(st.zoneTop.low)) {
                          addLine(st.zoneTop.low, ZONE_EDGE, "TOP↓", { lineWidth: 1, lineStyle: 0 });
                        }
                      }
                      if (st.zoneBottom) {
                        if (finitePrice(st.zoneBottom.high)) {
                          addLine(st.zoneBottom.high, ZONE_EDGE, "BOT↑", { lineWidth: 1, lineStyle: 0 });
                        }
                        if (finitePrice(st.zoneBottom.low)) {
                          addLine(st.zoneBottom.low, ZONE_EDGE, "BOT↓", { lineWidth: 1, lineStyle: 0 });
                        }
                      }
                      if (plan) {
                        const entry = plan.entry || (plan.grid && plan.grid.avg);
                        if (finitePrice(entry)) addLine(entry, "#0f766e", "ENTRY", { lineWidth: 2, lineStyle: 0 });
                        if (finitePrice(plan.stopLoss)) addLine(plan.stopLoss, "#b91c1c", "SL", { lineWidth: 1, lineStyle: 2 });
                        if (finitePrice(plan.tp1)) addLine(plan.tp1, "#16a34a", "TP1", { lineWidth: 1, lineStyle: 2 });
                        if (finitePrice(plan.tp2)) addLine(plan.tp2, "#15803d", "TP2", { lineWidth: 1, lineStyle: 2 });
                      }
                      if (plan && plan.actionable && candles && candles.length) {
                        const last = candles[candles.length - 1];
                        const buy = plan.buy === true || (sig && sig.side === "BUY");
                        candleSeries.setMarkers([{
                          time: last.time,
                          position: buy ? "belowBar" : "aboveBar",
                          color: buy ? "#16a34a" : "#dc2626",
                          shape: buy ? "arrowUp" : "arrowDown",
                          text: buy ? "BUY" : "SELL"
                        }]);
                      } else if (candleSeries) {
                        candleSeries.setMarkers([]);
                      }
                    }
                    layoutMarketOverlays();
                  }
                  function ensureVolumeChart() {
                    const wrap = $("signal-volume-wrap");
                    const el = $("signal-volume");
                    if (!el || !wrap) return;
                    wrap.hidden = !showVolume;
                    if (!showVolume) return;
                    if (volumeChart) {
                      volumeChart.applyOptions({ width: el.clientWidth });
                      return;
                    }
                    volumeChart = LightweightCharts.createChart(el, {
                      width: el.clientWidth,
                      height: 120,
                      layout: { backgroundColor: "#ffffff", textColor: "#1a2228" },
                      grid: { vertLines: { color: "#eef1f3" }, horzLines: { color: "#eef1f3" } },
                      rightPriceScale: { borderColor: "#d5dde2" },
                      timeScale: { borderColor: "#d5dde2", visible: false },
                      handleScroll: false,
                      handleScale: false
                    });
                    volumeSeries = volumeChart.addHistogramSeries({
                      color: "rgba(2, 132, 199, 0.45)",
                      priceFormat: { type: "volume" }
                    });
                    if (chart) {
                      chart.timeScale().subscribeVisibleLogicalRangeChange(function (range) {
                        if (range && volumeChart) {
                          try { volumeChart.timeScale().setVisibleLogicalRange(range); } catch (_) {}
                        }
                      });
                    }
                  }
                  function updateVolume(bars) {
                    if (!showVolume) return;
                    ensureVolumeChart();
                    if (!volumeSeries || !bars || !bars.length) return;
                    const data = bars.map(function (b) {
                      const t = toChartTime(b.time);
                      if (t == null) return null;
                      const up = b.close >= b.open;
                      return {
                        time: t,
                        value: b.volume || 0,
                        color: up ? "rgba(22, 163, 74, 0.45)" : "rgba(220, 38, 38, 0.4)"
                      };
                    }).filter(Boolean);
                    volumeSeries.setData(data);
                    if (chart) {
                      const range = chart.timeScale().getVisibleLogicalRange();
                      if (range) {
                        try { volumeChart.timeScale().setVisibleLogicalRange(range); } catch (_) {}
                      }
                    }
                  }
                  function atRightEdge() {
                    if (!chart) return true;
                    try {
                      const ts = chart.timeScale();
                      const range = ts.getVisibleLogicalRange();
                      if (!range) return true;
                      const barsInfo = candleSeries.barsInLogicalRange(range);
                      if (!barsInfo) return true;
                      return barsInfo.barsAfter != null && barsInfo.barsAfter < 3;
                    } catch (_) {
                      return true;
                    }
                  }
                  function currentRightOffset() {
                    return rightPadOn ? RIGHT_PAD_ON : RIGHT_PAD_OFF;
                  }
                  function syncPadButtons() {
                    const deskBtn = $("signal-desk-pad");
                    const chartBtn = $("signal-chart-pad");
                    [deskBtn, chartBtn].forEach(function (btn) {
                      if (!btn) return;
                      btn.setAttribute("aria-pressed", rightPadOn ? "true" : "false");
                      btn.classList.toggle("is-on", rightPadOn);
                      btn.classList.toggle("is-active", rightPadOn);
                    });
                    if (deskBtn) {
                      deskBtn.textContent = rightPadOn ? "Автоотступ · вкл" : "Автоотступ";
                    }
                  }
                  function applyRightPad(scrollLive) {
                    if (!chart) return;
                    try {
                      chart.timeScale().applyOptions({ rightOffset: currentRightOffset() });
                    } catch (_) {}
                    if (scrollLive !== false) {
                      try { chart.timeScale().scrollToRealTime(); } catch (_) {}
                      userPinned = false;
                    }
                    syncPadButtons();
                  }
                  function toggleRightPad() {
                    rightPadOn = !rightPadOn;
                    applyRightPad(true);
                  }
                  function ensureChart() {
                    const el = $("signal-chart");
                    if (!el || chart) return;
                    chart = LightweightCharts.createChart(el, {
                      width: el.clientWidth,
                      height: 420,
                      layout: {
                        backgroundColor: "#ffffff",
                        textColor: "#1a2228"
                      },
                      grid: {
                        vertLines: { color: "#eef1f3" },
                        horzLines: { color: "#eef1f3" }
                      },
                      crosshair: {
                        mode: 1,
                        vertLine: { color: "rgba(30,42,50,0.35)", labelBackgroundColor: "#1a2228" },
                        horzLine: { color: "rgba(30,42,50,0.35)", labelBackgroundColor: "#1a2228" }
                      },
                      rightPriceScale: { borderColor: "#d5dde2" },
                      timeScale: {
                        borderColor: "#d5dde2",
                        timeVisible: true,
                        secondsVisible: false,
                        rightOffset: currentRightOffset(),
                        barSpacing: 8
                      },
                      handleScroll: { mouseWheel: true, pressedMouseMove: true, horzTouchDrag: true },
                      handleScale: { axisPressedMouseMove: true, mouseWheel: true, pinch: true }
                    });
                    syncPadButtons();
                    candleSeries = chart.addCandlestickSeries({
                      upColor: "#16a34a", downColor: "#dc2626",
                      borderUpColor: "#16a34a", borderDownColor: "#dc2626",
                      wickUpColor: "#16a34a", wickDownColor: "#dc2626"
                    });
                    chart.timeScale().subscribeVisibleLogicalRangeChange(function () {
                      layoutMarketOverlays();
                      if (!followLive) {
                        userPinned = true;
                        return;
                      }
                      userPinned = !atRightEdge();
                    });
                    window.addEventListener("resize", function () {
                      if (chart && el) chart.applyOptions({ width: el.clientWidth });
                      layoutMarketOverlays();
                    });
                    ensureZoneOverlay();
                    ensureProfileOverlay();
                    ensureFootprintOverlay();
                  }
                  function toChartTime(iso) {
                    if (!iso) return null;
                    const d = new Date(iso.includes("T") ? iso : iso.replace(" ", "T"));
                    if (isNaN(d.getTime())) return null;
                    return Math.floor(d.getTime() / 1000);
                  }
                  function updateCandles(candles, forceFit) {
                    if (!candleSeries || !candles.length) return;
                    const prevRange = chart.timeScale().getVisibleLogicalRange();
                    const stickRight = forceFit || (followLive && !userPinned) || atRightEdge();
                    if (lastCandleTime == null || forceFit || candles.length < 3) {
                      candleSeries.setData(candles);
                    } else {
                      const last = candles[candles.length - 1];
                      const prev = candles[candles.length - 2];
                      if (last.time === lastCandleTime) {
                        candleSeries.update(last);
                      } else if (prev && prev.time === lastCandleTime) {
                        candleSeries.update(last);
                      } else {
                        candleSeries.setData(candles);
                      }
                    }
                    lastCandleTime = candles[candles.length - 1].time;
                    if (forceFit) {
                      chart.timeScale().fitContent();
                      userPinned = false;
                      try { chart.timeScale().applyOptions({ rightOffset: currentRightOffset() }); } catch (_) {}
                      try { chart.timeScale().scrollToRealTime(); } catch (_) {}
                    } else if (stickRight) {
                      try { chart.timeScale().applyOptions({ rightOffset: currentRightOffset() }); } catch (_) {}
                      chart.timeScale().scrollToRealTime();
                    } else if (prevRange) {
                      try { chart.timeScale().setVisibleLogicalRange(prevRange); } catch (_) {}
                    }
                    requestAnimationFrame(layoutMarketOverlays);
                  }
                  function renderDom(book) {
                    const body = $("signal-dom-body");
                    const meta = $("signal-dom-meta");
                    const imb = $("signal-dom-imbalance");
                    const imbBid = $("signal-dom-imb-bid");
                    const imbAsk = $("signal-dom-imb-ask");
                    if (!body) return;
                    if (!book || ((!book.bids || !book.bids.length) && (!book.asks || !book.asks.length))) {
                      body.innerHTML = "<div class=\\"signal-dom-empty\\">Нет DOM</div>";
                      if (meta) meta.textContent = book && book.summary ? book.summary : "—";
                      if (imb) imb.hidden = true;
                      return;
                    }
                    const bids = (book.bids || []).slice(0, 50);
                    const asks = (book.asks || []).slice(0, 50);
                    const tape = book.tapeByPrice || {};
                    const bestBid = bids.length ? Number(bids[0].p) : null;
                    const bestAsk = asks.length ? Number(asks[0].p) : null;
                    let bidLots = 0, askLots = 0;
                    bids.forEach(function (b) { bidLots += Number(b.q) || 0; });
                    asks.forEach(function (a) { askLots += Number(a.q) || 0; });
                    const totLots = bidLots + askLots;
                    if (imb && imbBid && imbAsk && totLots > 0) {
                      imb.hidden = false;
                      const bp = Math.round(100 * bidLots / totLots);
                      imbBid.style.width = bp + "%";
                      imbAsk.style.width = (100 - bp) + "%";
                      imbBid.title = "Bid " + Math.round(bidLots) + " лотов (" + bp + "%)";
                      imbAsk.title = "Ask " + Math.round(askLots) + " лотов (" + (100 - bp) + "%)";
                    } else if (imb) {
                      imb.hidden = true;
                    }
                    if (meta) {
                      const age = book.asOf ? (" · " + new Date(book.asOf).toLocaleTimeString("ru-RU")) : "";
                      const spr = (bestBid != null && bestAsk != null)
                        ? (" · spr " + (bestAsk - bestBid).toFixed(2))
                        : "";
                      meta.textContent = (book.instrumentId || "BR")
                        + " · depth " + Math.max(bids.length, asks.length)
                        + spr + age;
                    }
                    let maxQ = 1;
                    bids.forEach(function (b) { maxQ = Math.max(maxQ, Number(b.q) || 0); });
                    asks.forEach(function (a) { maxQ = Math.max(maxQ, Number(a.q) || 0); });
                    Object.keys(tape).forEach(function (k) {
                      const t = tape[k];
                      if (t && t.total) maxQ = Math.max(maxQ, Number(t.total) || 0);
                    });
                    const pxKey = function (p) {
                      return (Math.round(Number(p) * 100) / 100).toFixed(2);
                    };
                    const barW = function (q) {
                      return Math.max(4, Math.round(100 * (Number(q) || 0) / maxQ));
                    };
                    let html = "";
                    // Asks: reverse so highest at top, best ask at bottom of ask zone
                    for (let i = asks.length - 1; i >= 0; i--) {
                      const a = asks[i];
                      const p = Number(a.p);
                      const q = Number(a.q) || 0;
                      const key = pxKey(p);
                      const t = tape[key] || {};
                      const buy = Number(t.buy) || 0;
                      const sell = Number(t.sell) || 0;
                      const isBest = bestAsk != null && Math.abs(p - bestAsk) < 1e-9;
                      html += "<div class=\\"dom-row dom-ask" + (isBest ? " is-best" : "") + "\\">"
                        + "<span class=\\"dom-bid-px\\"></span>"
                        + "<span class=\\"dom-bid-q\\"></span>"
                        + "<span class=\\"dom-px\\">" + p.toFixed(2) + "</span>"
                        + "<span class=\\"dom-ask-q\\"><i style=\\"width:" + barW(q) + "%\\"></i><em>" + q + "</em></span>"
                        + "<span class=\\"dom-ask-px\\">" + p.toFixed(2) + "</span>"
                        + "<span class=\\"dom-tape\\">"
                        + (buy || sell
                          ? ("<b class=\\"b\\">" + buy + "</b><i>×</i><b class=\\"s\\">" + sell + "</b>")
                          : "")
                        + "</span>"
                        + "</div>";
                    }
                    if (bestBid != null && bestAsk != null) {
                      const mid = ((bestBid + bestAsk) / 2).toFixed(2);
                      const spr = (bestAsk - bestBid).toFixed(2);
                      html += "<div class=\\"dom-row dom-spread\\">"
                        + "<span class=\\"dom-spread-label\\">SPREAD " + spr + " · mid " + mid + "</span>"
                        + "</div>";
                    }
                    for (let i = 0; i < bids.length; i++) {
                      const b = bids[i];
                      const p = Number(b.p);
                      const q = Number(b.q) || 0;
                      const key = pxKey(p);
                      const t = tape[key] || {};
                      const buy = Number(t.buy) || 0;
                      const sell = Number(t.sell) || 0;
                      const isBest = bestBid != null && Math.abs(p - bestBid) < 1e-9;
                      html += "<div class=\\"dom-row dom-bid" + (isBest ? " is-best" : "") + "\\">"
                        + "<span class=\\"dom-bid-px\\">" + p.toFixed(2) + "</span>"
                        + "<span class=\\"dom-bid-q\\"><i style=\\"width:" + barW(q) + "%\\"></i><em>" + q + "</em></span>"
                        + "<span class=\\"dom-px\\">" + p.toFixed(2) + "</span>"
                        + "<span class=\\"dom-ask-q\\"></span>"
                        + "<span class=\\"dom-ask-px\\"></span>"
                        + "<span class=\\"dom-tape\\">"
                        + (buy || sell
                          ? ("<b class=\\"b\\">" + buy + "</b><i>×</i><b class=\\"s\\">" + sell + "</b>")
                          : "")
                        + "</span>"
                        + "</div>";
                    }
                    body.innerHTML = html;
                    // keep best ask/bid near center of scroll
                    const bestEl = body.querySelector(".dom-spread") || body.querySelector(".is-best");
                    if (bestEl && typeof bestEl.scrollIntoView === "function") {
                      try { bestEl.scrollIntoView({ block: "center" }); } catch (_) {}
                    }
                  }
                  async function kickRobot() {
                    const btn = $("sig-kick-btn");
                    if (btn) btn.disabled = true;
                    try {
                      const res = await fetch("/api/trend/kick?mode=hard&reason=desk-button", {
                        method: "POST",
                        headers: { Accept: "application/json" }
                      });
                      const data = await res.json().catch(function () { return {}; });
                      if (!res.ok) throw new Error(data.error || ("HTTP " + res.status));
                      await loadDesk(true);
                      const brief = $("signal-desk-brief-body");
                      if (brief) {
                        brief.innerHTML = "<p><strong>Пинок:</strong> "
                          + (data.reason || "hard")
                          + " · kicksToday=" + (data.kickCountToday || "?")
                          + " · " + (data.deskSummary || data.engineState || "")
                          + "</p>" + brief.innerHTML;
                      }
                    } catch (e) {
                      alert("Пинок не удался: " + (e && e.message ? e.message : e));
                    } finally {
                      if (btn) btn.disabled = false;
                    }
                  }
                  async function loadBook() {
                    try {
                      const res = await fetch("/api/marketdata/book", { headers: { Accept: "application/json" } });
                      if (!res.ok) return;
                      renderDom(await res.json());
                    } catch (_) {}
                  }
                  async function loadDesk(forceFit) {
                    const meta = $("signal-desk-meta");
                    try {
                      const res = await fetch("/api/trend/desk", { headers: { Accept: "application/json" } });
                      if (!res.ok) throw new Error("HTTP " + res.status);
                      const data = await res.json();
                      if (meta) {
                        meta.textContent = (data.instrument || "BR") + " · bars=" + (data.barCount || 0)
                          + " · source=" + (data.barsSource || "?")
                          + " · " + (data.engineState || "")
                          + (followLive && !userPinned ? " · follow" : " · zoom locked");
                      }
                      $("sig-instrument").textContent = data.instrument || "—";
                      $("sig-delivery").textContent = data.delivery || "—";
                      const sig = data.signal || {};
                      const plan = data.plan || {};
                      $("sig-side").textContent = sig.side || plan.side || "—";
                      $("sig-mode").textContent = sig.mode || plan.mode || "—";
                      $("sig-potential").textContent = fmtPot(data.potentialPnlRub);
                      $("sig-summary").textContent = data.summary || sig.summary || "—";
                      $("sig-side").classList.toggle("is-buy", (sig.side || plan.side) === "BUY");
                      $("sig-side").classList.toggle("is-sell", (sig.side || plan.side) === "SELL");
                      renderPaper(data.paper);
                      const briefBody = $("signal-desk-brief-body");
                      if (briefBody) briefBody.innerHTML = buildOperatorBrief(data);
                      renderCompliance(data);

                      ensureChart();
                      const candles = (data.bars || []).map(function (b) {
                        const t = toChartTime(b.time);
                        if (t == null) return null;
                        return { time: t, open: b.open, high: b.high, low: b.low, close: b.close };
                      }).filter(Boolean);
                      if (candles.length) {
                        updateCandles(candles, !!forceFit);
                        applyOverlays(plan, sig, candles, data.structure || {});
                      }
                      lastBarsRaw = data.bars || [];
                      lastProfile = data.profile || [];
                      lastFootprint = data.footprint || [];
                      updateVolume(lastBarsRaw);
                      layoutMarketOverlays();
                      if (data.book) renderDom(data.book);
                    } catch (err) {
                      if (meta) meta.textContent = "Ошибка desk: " + (err.message || err);
                    }
                  }
                  const btn = $("signal-desk-refresh");
                  if (btn) btn.addEventListener("click", function () { loadDesk(true); });
                  const kickBtn = $("sig-kick-btn");
                  if (kickBtn) kickBtn.addEventListener("click", kickRobot);
                  const fitBtn = $("signal-desk-fit");
                  if (fitBtn) fitBtn.addEventListener("click", function () {
                    userPinned = false;
                    followLive = true;
                    const follow = $("signal-desk-follow");
                    if (follow) follow.checked = true;
                    if (chart) {
                      chart.timeScale().fitContent();
                      applyRightPad(true);
                    }
                    loadDesk(true);
                  });
                  const padBtn = $("signal-desk-pad");
                  if (padBtn) padBtn.addEventListener("click", toggleRightPad);
                  const chartPadBtn = $("signal-chart-pad");
                  if (chartPadBtn) chartPadBtn.addEventListener("click", toggleRightPad);
                  syncPadButtons();
                  const follow = $("signal-desk-follow");
                  if (follow) {
                    follow.checked = true;
                    follow.addEventListener("change", function () {
                      followLive = !!follow.checked;
                      if (followLive) {
                        userPinned = false;
                        if (chart) {
                          applyRightPad(true);
                        }
                      }
                    });
                  }
                  const volToggle = $("signal-desk-volume");
                  if (volToggle) {
                    volToggle.addEventListener("change", function () {
                      showVolume = !!volToggle.checked;
                      ensureVolumeChart();
                      if (showVolume) updateVolume(lastBarsRaw);
                      else if ($("signal-volume-wrap")) $("signal-volume-wrap").hidden = true;
                    });
                  }
                  const profToggle = $("signal-desk-profile");
                  if (profToggle) {
                    showProfile = !!profToggle.checked;
                    profToggle.addEventListener("change", function () {
                      showProfile = !!profToggle.checked;
                      layoutProfile(lastProfile);
                    });
                  }
                  const fpToggle = $("signal-desk-footprint");
                  if (fpToggle) {
                    showFootprint = !!fpToggle.checked;
                    fpToggle.addEventListener("change", function () {
                      showFootprint = !!fpToggle.checked;
                      layoutFootprint(lastFootprint);
                    });
                  }
                  loadDesk(true);
                  loadBook();
                  setInterval(function () { loadDesk(false); }, DESK_MS);
                  setInterval(loadBook, BOOK_MS);
                })();
                </script>
                """;
        return page("TRINITY — сигнал Trend", body, nav("trend-signal"), OpsMode.NONE);
    }

    /**
     * Итоговая таблица: техника + новости + решение ENTER/REDUCE/WATCH/BLOCK.
     */
    public String renderFinalTable(List<FinalTradeRecommendation> rows) {
        return renderFinalTable(
                rows,
                List.of(),
                MarketRegimeSnapshot.unknown(),
                null,
                RssHeadlineService.Snapshot.disabled()
        );
    }

    public String renderFinalTable(
            List<FinalTradeRecommendation> rows,
            List<TradingRecommendation> technical,
            MarketRegimeSnapshot regime,
            AnalysisReport report,
            RssHeadlineService.Snapshot rss
    ) {
        if (rows == null) {
            rows = List.of();
        }
        if (technical == null) {
            technical = List.of();
        }
        if (regime == null) {
            regime = MarketRegimeSnapshot.unknown();
        }
        if (rss == null) {
            rss = RssHeadlineService.Snapshot.disabled();
        }

        StringBuilder body = new StringBuilder();
        body.append("""
                <div class="hint">
                  <strong>Итог после фундамента (multi-day / DAILY).</strong>
                  Порядок: техника → cluster gate → фундамент (MOEX + RSS) → рекомендация → paper.
                  В INTRADAY фундамент и RSS-контекст намеренно пропускаются — новости запаздывают.
                  Это research / decision-support, не инвестиционная рекомендация и не обещание прибыли.
                </div>
                """);

        body.append(renderFinalExplainPanel(rows, technical, regime, report));

        if (!rows.isEmpty()) {
            body.append(renderFinalSummaryStrip(rows));
            body.append("""
                    <div class="table-wrap">
                    <table>
                      <thead>
                        <tr>
                          <th>Итог</th>
                          <th>Пара</th>
                          <th>Техсигнал</th>
                          <th>Z</th>
                          <th>Нов. риск</th>
                          <th>Асимм.</th>
                          <th>Почему</th>
                          <th>График</th>
                        </tr>
                      </thead>
                      <tbody>
                    """);

            for (FinalTradeRecommendation f : rows) {
                body.append("<tr>");
                body.append("<td>").append(decisionBadge(f.decision())).append("</td>");
                body.append("<td><strong>").append(escape(f.tickerY())).append("</strong> / ")
                        .append(escape(f.tickerX())).append("</td>");
                body.append("<td>").append(signalBadge(f.technical().signal())).append("</td>");
                body.append("<td class=\"num\">").append(formatZ(f.technical().currentZScore())).append("</td>");
                body.append("<td>").append(newsBadge(f.news().riskLevel().name())).append("</td>");
                body.append("<td>").append(f.news().asymmetric() ? "да" : "нет").append("</td>");
                body.append("<td class=\"details\">");
                body.append("<div class=\"summary\">").append(escape(f.decisionSummary())).append("</div>");
                body.append(renderFinalRowProse(f));
                body.append("</td>");
                body.append("<td class=\"links\">").append(chartPageLink(f.tickerY(), f.tickerX())).append("</td>");
                body.append("</tr>");
            }

            body.append("</tbody></table></div>");
        }

        body.append(renderFinalNewsSection(rss));
        return page("TRINITY — итог", body.toString(), nav("final"));
    }

    private String renderFinalExplainPanel(
            List<FinalTradeRecommendation> rows,
            List<TradingRecommendation> technical,
            MarketRegimeSnapshot regime,
            AnalysisReport report
    ) {
        boolean empty = rows.isEmpty();
        StringBuilder sb = new StringBuilder();
        sb.append("<section class=\"final-explain\" id=\"final-explain\">");
        sb.append("<p class=\"final-explain-lead\">");
        if (empty) {
            sb.append("Сейчас в таблице <strong>нет строк</strong> — это не «поломка», а честный итог пайплайна: ")
                    .append("до paper доходят только пары, прошедшие технику и (для DAILY) фундаментальный слой.");
        } else {
            long actionable = rows.stream()
                    .filter(f -> f.decision() == FinalTradeDecision.ENTER
                            || f.decision() == FinalTradeDecision.REDUCE_SIZE)
                    .count();
            sb.append("Ниже — <strong>").append(rows.size()).append("</strong> итоговых строк после FA. ")
                    .append("Actionable (ENTER/REDUCE): <strong>").append(actionable).append("</strong>. ")
                    .append("Читайте «почему именно такие», затем детали по строкам.");
        }
        sb.append("</p>");

        sb.append("<div class=\"final-explain-grid\">");

        sb.append("<article class=\"final-explain-block\">");
        sb.append("<h3>Что значит «Итог» в пайплайне</h3>");
        sb.append("<p>TRINITY — mean-reversion по коинтегрированным парам IMOEX <em>только в боковике</em>. ")
                .append("Страница «Итог» — операторский вердикт после цепочки, а не сырой LONG/SHORT.</p>");
        sb.append("<ol class=\"final-pipeline\">");
        sb.append("<li><strong>Техника</strong> — EG/FDR, Z-score, качество пары, разворот входа.</li>");
        sb.append("<li><strong>Cluster gate</strong> — месячная eligibility секторов (net&gt;0, PF≥1.1); OIL_GAS вне pairs.</li>");
        sb.append("<li><strong>FA (только DAILY)</strong> — новости MOEX + RSS; CONFLICT с техникой снижает или блокирует вход. ")
                .append("INTRADAY FA пропускает.</li>");
        sb.append("<li><strong>Рекомендация</strong> — ENTER / REDUCE / WATCH / BLOCK.</li>");
        sb.append("<li><strong>Paper</strong> — журнал открывает только ENTER/REDUCE при свободном слоте и не-TREND.</li>");
        sb.append("</ol>");
        sb.append("<p class=\"meta\">Research / decision-support: система помогает думать, не исполняет у брокера и не гарантирует результат.</p>");
        sb.append("</article>");

        sb.append("<article class=\"final-explain-block\">");
        if (empty) {
            sb.append("<h3>Почему сейчас 0 строк</h3>");
            sb.append("<p>Ниже — реальные причины по текущим данным (если что-то не сработало в этом прогоне, пункт отмечен).</p>");
            sb.append("<ul class=\"final-reasons\">");
            for (String reason : diagnoseEmptyFinalReasons(rows, technical, regime, report)) {
                sb.append("<li>").append(reason).append("</li>");
            }
            sb.append("</ul>");
            sb.append("<div class=\"final-conflict-note\">");
            sb.append("<h4>Что такое CONFLICT (техника vs фундамент)</h4>");
            sb.append("<p><strong>Коротко:</strong> техника говорит «спред перетянут, mean-reversion», ")
                    .append("а новости/фундамент по одной ноге пары — «здесь шок или структурный риск».</p>");
            sb.append("<p>Для новичка: представьте, что стрелки на графике красивые, но по одной акции вышла ")
                    .append("плохая отчётность, SPO или санкционный заголовок. Спред может «уехать» не к среднему, ")
                    .append("а ещё дальше. Поэтому CONFLICT → REDUCE (осторожный размер) или BLOCK (не открывать).</p>");
            sb.append("<p class=\"meta\">Когда CONFLICT уже есть в таблице — в колонке «Почему» будет явная фраза ")
                    .append("«CONFLICT: техника vs фундамент» плюс тип триггера. Сейчас строк нет, поэтому живых CONFLICT-примеров в таблице нет.</p>");
            sb.append("</div>");
        } else {
            sb.append("<h3>Почему именно такие решения</h3>");
            sb.append(renderFinalWhyProse(rows, regime));
            List<FinalTradeRecommendation> conflicts = rows.stream()
                    .filter(f -> f.decisionSummary() != null && f.decisionSummary().contains("CONFLICT"))
                    .toList();
            if (!conflicts.isEmpty()) {
                sb.append("<div class=\"final-conflict-note\">");
                sb.append("<h4>CONFLICT в текущей таблице (").append(conflicts.size()).append(")</h4>");
                sb.append("<p><strong>Педагогика:</strong> техника и фундамент расходятся. ")
                        .append("Это не «ошибка модели», а честный стоп/снижение размера, пока шок не переварится.</p>");
                sb.append("<ul>");
                int shown = 0;
                for (FinalTradeRecommendation c : conflicts) {
                    if (shown++ >= 5) {
                        sb.append("<li>… и ещё ").append(conflicts.size() - 5).append("</li>");
                        break;
                    }
                    sb.append("<li><strong>").append(escape(c.tickerY())).append("/")
                            .append(escape(c.tickerX())).append("</strong> — ")
                            .append(escape(c.decisionSummary()));
                    if (c.news() != null && !c.news().hits().isEmpty()) {
                        NewsTriggerHit top = c.news().hits().get(0);
                        sb.append("<br><span class=\"meta\">")
                                .append(escape(top.type().name())).append(": ")
                                .append(escape(top.explanation())).append("</span>");
                    }
                    sb.append("</li>");
                }
                sb.append("</ul></div>");
            }
        }
        sb.append("</article>");

        sb.append("<article class=\"final-explain-block\">");
        sb.append("<h3>Что делать оператору дальше</h3>");
        sb.append("<ol>");
        if (empty) {
            sb.append("<li>Если анализ давно не гоняли — на <a href=\"/view/settings\">Настройках</a> «Анализ + paper».</li>");
            sb.append("<li>Проверить виджет <strong>режима рынка</strong> на <a href=\"/view\">дашборде</a>: TREND (высокий ADX) блокирует новые входы.</li>");
            sb.append("<li>Открыть <a href=\"/view/signals\">Сигналы</a> и <a href=\"/view/recommendations\">Все рекомендации</a> — есть ли сырой LONG/SHORT до FA.</li>");
            sb.append("<li>Если техника есть, а итог пуст — пересчитать «Только новости / paper» или полный цикл (FA мог не сохраниться).</li>");
            sb.append("<li>Смотреть <a href=\"/view/paper\">Paper</a>: пустой journal при пустом итоге — нормальная дисциплина, не «баг».</li>");
        } else {
            sb.append("<li>Сверить ENTER/REDUCE с графиком пары и размером слота (капитал без плеча до 1M).</li>");
            sb.append("<li>При CONFLICT / BLOCK — прочитать новости по ноге; не «продавливать» вход ради активности.</li>");
            sb.append("<li>Проверить режим ADX на дашборде — даже ENTER не откроется в paper при TREND.</li>");
            sb.append("<li>Сверить, что реально легло в <a href=\"/view/paper\">Paper</a>.</li>");
            sb.append("<li>RSS ниже — только контекст FA, не отдельный сигнал на вход.</li>");
        }
        sb.append("</ol>");
        sb.append("</article>");

        sb.append("<article class=\"final-explain-block final-glossary\">");
        sb.append("<h3>Словарь: ENTER / REDUCE / WATCH / BLOCK</h3>");
        sb.append("<dl>");
        sb.append("<dt>ENTER</dt><dd><strong>Новичку:</strong> техника и фундамент согласны — пару можно разбирать к открытию. ")
                .append("<em>Профи:</em> полный слот книги в рамках CapitalAllocator; всё равно считайте borrow, slippage и стоп по Z — ")
                .append("это не гарантия mean-reversion.</dd>");
        sb.append("<dt>REDUCE</dt><dd><strong>Новичку:</strong> вход возможен, но уменьшенным размером (часто CONFLICT средней силы или caution). ")
                .append("<em>Профи:</em> типично × reduce-factor / risk policy; сохраняйте асимметрию ног и room-to-stop.</dd>");
        sb.append("<dt>WATCH</dt><dd><strong>Новичку:</strong> наблюдать, не открывать новую сделку (ждём разворот Z, режим, или мягкий FA). ")
                .append("<em>Профи:</em> не путать с HOLD у нуля — WATCH часто = «порог есть, подтверждения нет» или TREND-gate.</dd>");
        sb.append("<dt>BLOCK</dt><dd><strong>Новичку:</strong> вход запрещён — структурный/новостной блокер или жёсткий CONFLICT. ")
                .append("<em>Профи:</em> halt, delisting, earnings miss, SPO, санкции и т.п.; paper не откроет.</dd>");
        sb.append("</dl>");
        sb.append("<p class=\"meta\">Ни один статус не обещает доходность. TRINITY — desk research для оператора pairs.</p>");
        sb.append("</article>");

        sb.append("</div></section>");
        return sb.toString();
    }

    private List<String> diagnoseEmptyFinalReasons(
            List<FinalTradeRecommendation> rows,
            List<TradingRecommendation> technical,
            MarketRegimeSnapshot regime,
            AnalysisReport report
    ) {
        List<String> reasons = new ArrayList<>();
        long techActionable = technical.stream()
                .filter(r -> r.signal() == TradingSignal.LONG_SPREAD || r.signal() == TradingSignal.SHORT_SPREAD)
                .count();
        long techWatch = technical.stream().filter(r -> r.signal() == TradingSignal.WATCH).count();
        long regimeWatch = technical.stream()
                .filter(r -> r.signal() == TradingSignal.WATCH)
                .filter(r -> r.summary() != null && (r.summary().contains("тренд") || r.summary().toUpperCase(Locale.ROOT).contains("TREND")))
                .count();

        if (technical.isEmpty()) {
            reasons.add("<strong>Нет технических рекомендаций</strong> — анализ не прогонялся, не загрузился с диска, "
                    + "или после FDR/качества не осталось пар. "
                    + (report == null
                    ? "Отчёт анализа в памяти пуст."
                    : "В отчёте: тикеров " + report.tickersAnalyzed()
                    + ", протестировано пар " + report.pairsTested()
                    + ", коинтегрировано " + report.cointegratedPairs() + "."));
        } else if (techActionable == 0 && techWatch == 0) {
            reasons.add("<strong>Нет LONG/SHORT/WATCH</strong> — все пары в HOLD/NO_SIGNAL "
                    + "(|Z| ниже порога, half-life/R²/coverage, нет разворота). Итоговая таблица строится только по LONG/SHORT/WATCH.");
        } else if (techActionable == 0) {
            reasons.add("<strong>Есть WATCH, нет LONG/SHORT</strong> — техника видит напряжение спреда, но подтверждённого входа нет. "
                    + "Итог мог бы содержать WATCH-строки после FA; если таблица пуста, перезапустите цикл «Анализ + paper» / «Только новости».");
        }

        if (regime.blockEntries() || "TREND".equalsIgnoreCase(regime.label()) || regimeWatch > 0) {
            reasons.add("<strong>Режим TREND / ADX</strong> — сейчас "
                    + escape(regime.label()) + " (ADX="
                    + (Double.isNaN(regime.adx()) ? "—" : String.format(Locale.ROOT, "%.1f", regime.adx()))
                    + "). " + escape(regime.detail())
                    + (regimeWatch > 0 ? " Техника пометила WATCH по тренду у " + regimeWatch + " пар(ы)." : ""));
        } else {
            reasons.add("<strong>Режим рынка:</strong> " + escape(regime.label())
                    + " — блокера TREND сейчас нет (ADX="
                    + (Double.isNaN(regime.adx()) ? "—" : String.format(Locale.ROOT, "%.1f", regime.adx()))
                    + ").");
        }

        reasons.add("<strong>Cluster gate</strong> — ежемесячный пересмотр секторов (net&gt;0, PF≥1.1) и hard-ban OIL_GAS "
                + "режут пары <em>до</em> техники. Если в отчёте мало коинтегрированных пар при живом индексе — смотрите cluster-review / сектора.");

        reasons.add("<strong>FA CONFLICT</strong> — при расхождении техники и новостей строка обычно <em>остаётся</em> "
                + "как REDUCE/BLOCK с текстом «CONFLICT: техника vs фундамент», а не исчезает. "
                + "Пустая таблица чаще значит «нечего было прогонять через FA», а не «всё CONFLICT-нули».");

        reasons.add("<strong>WATCH/BLOCK vs actionable view</strong> — paper и виджет дашборда смотрят на ENTER/REDUCE. "
                + "Даже при непустой таблице actionable может быть 0, если все строки WATCH/BLOCK.");

        reasons.add("<strong>FA blocked entries</strong> — news risk BLOCK/HIGH по ноге пары запрещает новый вход (halt, delisting, miss, SPO…). "
                + "Такие пары видны как BLOCK в непустой таблице; при пустой — сначала нужна техника LONG/SHORT/WATCH.");

        if (!reasons.isEmpty() && technical.isEmpty() && report != null && report.cointegratedPairs() == 0) {
            reasons.add(0, "<strong>Нет коинтегрированных пар в последнем отчёте</strong> — FDR/p-value/качество не дали universe для сигналов.");
        }

        return reasons;
    }

    private String renderFinalWhyProse(List<FinalTradeRecommendation> rows, MarketRegimeSnapshot regime) {
        Map<FinalTradeDecision, Long> counts = new EnumMap<>(FinalTradeDecision.class);
        for (FinalTradeDecision d : FinalTradeDecision.values()) {
            counts.put(d, 0L);
        }
        for (FinalTradeRecommendation f : rows) {
            counts.merge(f.decision(), 1L, Long::sum);
        }
        long conflicts = rows.stream()
                .filter(f -> f.decisionSummary() != null && f.decisionSummary().contains("CONFLICT"))
                .count();
        long faBlocked = rows.stream()
                .filter(f -> f.decision() == FinalTradeDecision.BLOCK)
                .count();

        StringBuilder sb = new StringBuilder();
        sb.append("<p>Сводка по действиям: ");
        sb.append("ENTER ").append(counts.get(FinalTradeDecision.ENTER));
        sb.append(", REDUCE ").append(counts.get(FinalTradeDecision.REDUCE_SIZE));
        sb.append(", WATCH ").append(counts.get(FinalTradeDecision.WATCH));
        sb.append(", BLOCK ").append(counts.get(FinalTradeDecision.BLOCK));
        sb.append(". Режим рынка: <strong>").append(escape(regime.label())).append("</strong>");
        if (regime.blockEntries()) {
            sb.append(" — новые paper-входы режет ADX даже при ENTER в таблице");
        }
        sb.append(".</p>");

        sb.append("<ul>");
        if (counts.get(FinalTradeDecision.ENTER) > 0) {
            sb.append("<li><strong>ENTER</strong> — техника подтверждена, FA без жёстких блокеров; размер по слотам книги.</li>");
        }
        if (counts.get(FinalTradeDecision.REDUCE_SIZE) > 0) {
            sb.append("<li><strong>REDUCE</strong> — caution / CONFLICT средней силы: вход только урезанным размером.</li>");
        }
        if (counts.get(FinalTradeDecision.WATCH) > 0) {
            sb.append("<li><strong>WATCH</strong> — наблюдение: нет подтверждённого входа, режим, или мягкий FA.</li>");
        }
        if (faBlocked > 0) {
            sb.append("<li><strong>BLOCK (").append(faBlocked).append(")</strong> — FA или структурный стоп; не открывать.</li>");
        }
        if (conflicts > 0) {
            sb.append("<li><strong>CONFLICT-маркеры:</strong> ").append(conflicts)
                    .append(" строк(и) с явным расхождением техники и фундамента — см. блок ниже.</li>");
        } else {
            sb.append("<li>Явных CONFLICT-строк в decisionSummary сейчас нет.</li>");
        }
        sb.append("</ul>");

        // top blockers from news summaries / rationale
        List<String> blockers = rows.stream()
                .filter(f -> f.decision() == FinalTradeDecision.BLOCK
                        || f.decision() == FinalTradeDecision.WATCH
                        || f.decision() == FinalTradeDecision.REDUCE_SIZE)
                .map(f -> {
                    String tip = f.rationale() != null && !f.rationale().isBlank()
                            ? f.rationale()
                            : f.decisionSummary();
                    return escape(f.tickerY() + "/" + f.tickerX() + " — " + tip);
                })
                .limit(5)
                .toList();
        if (!blockers.isEmpty()) {
            sb.append("<p><strong>Топ пояснений (не ENTER):</strong></p><ul>");
            for (String b : blockers) {
                sb.append("<li>").append(b).append("</li>");
            }
            sb.append("</ul>");
        }
        return sb.toString();
    }

    private String renderFinalSummaryStrip(List<FinalTradeRecommendation> rows) {
        long enter = rows.stream().filter(f -> f.decision() == FinalTradeDecision.ENTER).count();
        long reduce = rows.stream().filter(f -> f.decision() == FinalTradeDecision.REDUCE_SIZE).count();
        long watch = rows.stream().filter(f -> f.decision() == FinalTradeDecision.WATCH).count();
        long block = rows.stream().filter(f -> f.decision() == FinalTradeDecision.BLOCK).count();
        long conflicts = rows.stream()
                .filter(f -> f.decisionSummary() != null && f.decisionSummary().contains("CONFLICT"))
                .count();
        return """
                <div class="final-summary-strip meta">
                  Строк: <strong>%d</strong>
                  · ENTER <strong>%d</strong>
                  · REDUCE <strong>%d</strong>
                  · WATCH <strong>%d</strong>
                  · BLOCK <strong>%d</strong>
                  · CONFLICT-маркеров: <strong>%d</strong>
                </div>
                """.formatted(rows.size(), enter, reduce, watch, block, conflicts);
    }

    private String renderFinalRowProse(FinalTradeRecommendation f) {
        StringBuilder sb = new StringBuilder();
        if (f.rationale() != null && !f.rationale().isBlank()) {
            sb.append("<div class=\"rationale meta\"><strong>Почему (кратко):</strong> ")
                    .append(escape(f.rationale())).append("</div>");
        }
        if (f.beginnerGuide() != null && !f.beginnerGuide().isBlank()) {
            sb.append("<details class=\"final-row-detail\">");
            sb.append("<summary>Разбор для оператора</summary>");
            sb.append("<div class=\"final-row-prose\">");
            sb.append(nl2br(escape(enrichBeginnerGuideProse(f))));
            sb.append("</div></details>");
        } else {
            sb.append("<div class=\"explain\">").append(nl2br(escape(f.news().summary())));
            appendNewsHits(sb, f);
            sb.append("</div>");
        }
        // always surface news hits if beginner guide path
        if (f.beginnerGuide() != null && !f.beginnerGuide().isBlank()
                && f.news() != null && !f.news().hits().isEmpty()) {
            sb.append("<div class=\"explain final-news-hits\">");
            appendNewsHits(sb, f);
            sb.append("</div>");
        }
        return sb.toString();
    }

    private void appendNewsHits(StringBuilder sb, FinalTradeRecommendation f) {
        if (f.news() == null || f.news().hits().isEmpty()) {
            return;
        }
        sb.append("<br><br>");
        int i = 0;
        for (NewsTriggerHit hit : f.news().hits()) {
            if (i++ >= 3) {
                sb.append("…<br>");
                break;
            }
            sb.append("• ").append(escape(hit.ticker())).append(": ")
                    .append(escape(hit.title())).append("<br>");
        }
    }

    /**
     * Обогащает beginnerGuide спокойной прозой: lead + уже сохранённый разбор.
     */
    private String enrichBeginnerGuideProse(FinalTradeRecommendation f) {
        StringBuilder sb = new StringBuilder();
        sb.append(switch (f.decision()) {
            case ENTER -> "Ведущая мысль: вход разрешён после техники и FA — можно разбирать размер слота, не «покупать на эмоциях».";
            case REDUCE_SIZE -> "Ведущая мысль: вход только уменьшенным размером — часто CONFLICT или caution по новостям.";
            case WATCH -> "Ведущая мысль: пока наблюдаем — новой сделки нет, даже если Z выглядит «интересным».";
            case BLOCK -> "Ведущая мысль: вход запрещён. Красивая техника не отменяет новостной/структурный стоп.";
        });
        sb.append("\n\n");
        sb.append(f.beginnerGuide());
        return sb.toString();
    }

    private String renderFinalNewsSection(RssHeadlineService.Snapshot rss) {
        StringBuilder sb = new StringBuilder();
        sb.append("<section class=\"final-news\" id=\"final-news\">");
        sb.append("<h2>Новости (RSS) — контекст FA</h2>");
        sb.append("<p class=\"final-news-lead\">Лента для слоя фундамента на <strong>DAILY</strong>: помогает понять фон, ")
                .append("в котором FA мог выставить CONFLICT / BLOCK. ")
                .append("<em>Это не торговый сигнал и не замена разбору пары.</em> ")
                .append("Для книги INTRADAY FA и RSS-контекст намеренно пропускаются (новости запаздывают к 1H-ритму).</p>");

        if (!rss.enabled()) {
            sb.append("<div class=\"final-news-placeholder\">");
            sb.append("<p><strong>Новости (RSS) — подключается</strong></p>");
            sb.append("<p class=\"meta\">").append(escape(rss.status())).append("</p>");
            sb.append("<p class=\"meta\">Хук конфига: <code>imoex.news.rss-enabled</code>, ")
                    .append("<code>rss-max-items</code>, <code>rss-feeds</code>.</p>");
            sb.append("</div>");
        } else if (rss.items().isEmpty()) {
            sb.append("<div class=\"final-news-placeholder\">");
            sb.append("<p>").append(escape(rss.status())).append("</p>");
            sb.append("<p class=\"meta\">Кэш 15 мин · HTTP-таймаут ~5 с — страница не блокируется надолго.</p>");
            sb.append("</div>");
        } else {
            sb.append("<p class=\"meta\">").append(escape(rss.status()));
            if (rss.fetchedAt() != null) {
                sb.append(" · обновлено ").append(escape(rss.fetchedAt().toString()));
            }
            sb.append("</p>");
            sb.append("<div class=\"final-news-grid\">");
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM HH:mm");
            for (RssHeadline h : rss.items()) {
                sb.append("<article class=\"final-news-card\">");
                sb.append("<div class=\"final-news-meta\">");
                sb.append("<span class=\"final-news-source\">").append(escape(h.source())).append("</span>");
                if (h.publishedAt() != null) {
                    sb.append("<time>").append(escape(fmt.format(h.publishedAt()))).append("</time>");
                }
                sb.append("</div>");
                if (h.url() != null && !h.url().isBlank()) {
                    sb.append("<a class=\"final-news-title\" href=\"").append(escape(h.url()))
                            .append("\" target=\"_blank\" rel=\"noopener noreferrer\">")
                            .append(escape(h.title())).append("</a>");
                } else {
                    sb.append("<div class=\"final-news-title\">").append(escape(h.title())).append("</div>");
                }
                if (h.tickerHint() != null && !h.tickerHint().isBlank()) {
                    sb.append("<div class=\"final-news-tickers\">").append(escape(h.tickerHint())).append("</div>");
                }
                sb.append("</article>");
            }
            sb.append("</div>");
        }
        sb.append("</section>");
        return sb.toString();
    }

    private String decisionBadge(FinalTradeDecision decision) {
        String css = switch (decision) {
            case ENTER -> "badge enter";
            case REDUCE_SIZE -> "badge reduce";
            case WATCH -> "badge watch";
            case BLOCK -> "badge block";
        };
        String label = switch (decision) {
            case ENTER -> "ENTER";
            case REDUCE_SIZE -> "REDUCE";
            case WATCH -> "WATCH";
            case BLOCK -> "BLOCK";
        };
        return "<span class=\"" + css + "\">" + label + "</span>";
    }

    private String newsBadge(String level) {
        String css = switch (level) {
            case "LOW" -> "badge news-low";
            case "MEDIUM" -> "badge news-med";
            case "HIGH" -> "badge news-high";
            default -> "badge news-block";
        };
        return "<span class=\"" + css + "\">" + escape(level) + "</span>";
    }

    private String signalBadge(TradingSignal signal) {
        String css = switch (signal) {
            case LONG_SPREAD -> "badge long";
            case SHORT_SPREAD -> "badge short";
            case WATCH -> "badge watch";
            case HOLD -> "badge hold";
            case NO_SIGNAL -> "badge skip";
        };
        String label = switch (signal) {
            case LONG_SPREAD -> "КУПИТЬ спред";
            case SHORT_SPREAD -> "ПРОДАТЬ спред";
            case WATCH -> "НАБЛЮДАТЬ";
            case HOLD -> "ЖДАТЬ";
            case NO_SIGNAL -> "ПРОПУСК";
        };
        return "<span class=\"" + css + "\">" + escape(label) + "</span>";
    }

    private String chartPageLink(String y, String x) {
        String url = "/view/charts/" + escape(y) + "/" + escape(x);
        return "<a href=\"" + url + "\" target=\"_blank\">График</a>";
    }

    private String nl2br(String text) {
        return text.replace("\n", "<br>");
    }

    /**
     * Paper track-record таблица.
     */
    public String renderPaperJournal(PaperJournal journal) {
        return renderPaperJournal(journal, List.of(), List.of());
    }

    public String renderPaperJournal(
            PaperJournal journal,
            List<TradingRecommendation> dailyRecs,
            List<TradingRecommendation> intradayRecs
    ) {
        StringBuilder body = new StringBuilder();
        body.append("""
                <div class="hint">
                  <strong>Paper journal — dual-book.</strong> DAILY и INTRADAY в одном автоматическом цикле; cash PnL по qty×price,
                  slippage/borrow из конфига. Капитал без плеча при equity &lt; 1M; слоты/gross из CapitalAllocator
                  (40/60 фиксированно, без перетока между книгами). INTRADAY flatten к ~18:30.
                  Отдельные файлы journal; на этой странице — объединённый взгляд.
                </div>
                """);
        List<PaperTradeEntry> entries = journal.entries() == null ? List.of() : journal.entries();
        long open = journal.openCount() != null ? journal.openCount()
                : entries.stream().filter(e -> "OPEN".equals(e.status())).count();
        long closed = journal.closedCount() != null ? journal.closedCount()
                : entries.stream().filter(e -> "CLOSED".equals(e.status())).count();
        body.append("<div class=\"cards\">");
        body.append(card("Всего", String.valueOf(entries.size()), false));
        body.append(card("OPEN", String.valueOf(open), false));
        body.append(card("CLOSED", String.valueOf(closed), false));
        body.append(card("Realized ₽*",
                journal.realizedPnlRub() == null ? "—" : String.format("%.0f", journal.realizedPnlRub()),
                journal.realizedPnlRub() != null && journal.realizedPnlRub() >= 0));
        body.append(card("Unrealized ₽*",
                journal.unrealizedPnlRub() == null ? "—" : String.format("%.0f", journal.unrealizedPnlRub()),
                journal.unrealizedPnlRub() != null && journal.unrealizedPnlRub() >= 0));
        double net = (journal.realizedPnlRub() == null ? 0 : journal.realizedPnlRub())
                + (journal.unrealizedPnlRub() == null ? 0 : journal.unrealizedPnlRub());
        body.append(card("Net ₽* (R+U)", String.format("%.0f", net), net >= 0));
        body.append(card("Обновлено", journal.updatedAt() == null ? "—" : journal.updatedAt().toString(), false));
        body.append("</div>");

        if (entries.isEmpty()) {
            body.append("<div class=\"callout\">");
            body.append("<p><strong>Журнал пуст</strong> — сегодня нет paper-входов (вариантов сделок нет).</p>");
            body.append("<p>Сделки появляются только при <strong>ENTER</strong> / <strong>REDUCE_SIZE</strong> ");
            body.append("после техники и FA (DAILY). Сейчас сигналы ниже порога |Z|≥2 — это нормально.</p>");
            body.append("<ul>");
            body.append("<li>").append(bookDiag("DAILY", dailyRecs)).append("</li>");
            body.append("<li>").append(bookDiag("INTRADAY", intradayRecs)).append("</li>");
            body.append("</ul>");
            body.append("<p class=\"meta\">Типичные причины: |Z| &lt; 2, half-life вне порога, режим TREND (ADX), ");
            body.append("нет коинтегрированных пар после FDR. Нажмите «Анализ + paper», когда Z вырастет.</p>");
            body.append("</div>");
        } else {
            body.append("<div class=\"table-wrap\"><table><thead><tr>");
            body.append("<th>Статус</th><th>Книга</th><th>Пара</th><th>Сигнал</th><th>Decision</th>");
            body.append("<th class=\"num\">Entry Z</th><th class=\"num\">Mark/Exit Z</th>");
            body.append("<th class=\"num\">Notional Y</th>");
            body.append("<th class=\"num\">PnL %*</th><th class=\"num\">PnL ₽*</th>");
            body.append("<th>Opened</th><th>Closed</th><th>Комментарий к закрытию</th><th>Notes</th><th></th>");
            body.append("</tr></thead><tbody>");
            for (PaperTradeEntry e : entries) {
                Double markOrExit = e.exitZ() != null ? e.exitZ() : e.markZ();
                Double pct = e.pnlPct() != null ? e.pnlPct() : e.unrealizedPnlPct();
                Double rub = e.pnlRub() != null ? e.pnlRub() : e.unrealizedPnlRub();
                body.append("<tr>");
                body.append("<td>").append(escape(e.status())).append("</td>");
                body.append("<td>").append(escape(e.book() == null ? "DAILY" : e.book())).append("</td>");
                body.append("<td>").append(escape(e.tickerY())).append(" / ").append(escape(e.tickerX())).append("</td>");
                body.append("<td>").append(signalBadge(e.signal())).append("</td>");
                body.append("<td>").append(decisionBadge(e.decision())).append("</td>");
                body.append("<td class=\"num\">").append(formatZ(e.entryZ())).append("</td>");
                body.append("<td class=\"num\">")
                        .append(markOrExit == null ? "—" : formatZ(markOrExit)).append("</td>");
                body.append("<td class=\"num\">").append(String.format("%.0f", e.notionalY())).append("</td>");
                body.append("<td class=\"num\">")
                        .append(pct == null ? "—" : formatPct(pct)).append("</td>");
                body.append("<td class=\"num\">")
                        .append(rub == null ? "—" : String.format("%.0f", rub)).append("</td>");
                body.append("<td>").append(e.openedAt() == null ? "—" : escape(e.openedAt().toString())).append("</td>");
                body.append("<td>").append(e.closedAt() == null ? "—" : escape(e.closedAt().toString())).append("</td>");
                body.append("<td>").append(escape(e.closeComment() == null ? "" : e.closeComment())).append("</td>");
                body.append("<td>").append(escape(e.notes() == null ? "" : e.notes())).append("</td>");
                body.append("<td class=\"links\">").append(chartPageLink(e.tickerY(), e.tickerX())).append("</td>");
                body.append("</tr>");
            }
            body.append("</tbody></table></div>");
            body.append("<p class=\"meta\">* Псевдо-PnL: 1 единица Z ≈ 1% notional Y. Не брокерский результат.</p>");
        }
        return page("Paper journal", body.toString(), nav("paper"));
    }

    /**
     * Walk-forward OOS отчёт.
     */
    public String renderWalkForward(WalkForwardReport report) {
        StringBuilder body = new StringBuilder();
        body.append("""
                <div class="hint">
                  <strong>Walk-forward.</strong> Train → тест коинтеграции; test → Kalman/rolling-Z симуляция
                  с commission + borrow. Смотрите median OOS Sharpe: in-sample лидеры часто не держатся.
                </div>
                """);
        if (report == null || report.pairs() == null || report.pairs().isEmpty()) {
            body.append("<p class=\"empty\">Нет walk-forward отчёта. Нажмите «Walk-forward» в <a href=\"/view/settings\">Настройках</a> или запустите полный анализ.</p>");
            return page("Walk-forward", body.toString(), nav("walkforward"));
        }

        body.append("<div class=\"cards\">");
        body.append(card("Дата", report.analysisDate().toString(), false));
        body.append(card("Пар", String.valueOf(report.pairsEvaluated()), false));
        body.append(card("Median OOS > 0", String.valueOf(report.pairsWithPositiveMedianOosSharpe()), true));
        body.append(card("Mean median OOS", formatNum(report.meanMedianOosSharpe()), false));
        body.append("</div>");

        body.append("<div class=\"table-wrap\"><table><thead><tr>");
        body.append("<th>Пара</th><th class=\"num\">Windows</th><th class=\"num\">Coint win</th>");
        body.append("<th class=\"num\">Median OOS Sharpe</th><th class=\"num\">Mean OOS Sharpe</th>");
        body.append("<th class=\"num\">Mean OOS DD</th><th class=\"num\">Mean OOS Ret</th><th></th>");
        body.append("</tr></thead><tbody>");
        for (WalkForwardReport.PairWalkForward p : report.pairs()) {
            var s = p.summary();
            body.append("<tr>");
            body.append("<td>").append(escape(p.tickerY())).append(" / ").append(escape(p.tickerX())).append("</td>");
            body.append("<td class=\"num\">").append(s.windows()).append("</td>");
            body.append("<td class=\"num\">").append(s.cointegratedWindows()).append("</td>");
            body.append("<td class=\"num\">").append(formatNum(s.medianOosSharpe())).append("</td>");
            body.append("<td class=\"num\">").append(formatNum(s.meanOosSharpe())).append("</td>");
            body.append("<td class=\"num\">").append(formatPct(s.meanOosMaxDrawdown())).append("</td>");
            body.append("<td class=\"num\">").append(formatPct(s.meanOosReturn())).append("</td>");
            body.append("<td class=\"links\">").append(chartPageLink(p.tickerY(), p.tickerX())).append("</td>");
            body.append("</tr>");
        }
        body.append("</tbody></table></div>");
        return page("Walk-forward OOS", body.toString(), nav("walkforward"));
    }

    private String card(String label, String value, boolean accent) {
        return "<div class=\"card\"><span class=\"label\">" + escape(label)
                + "</span><span class=\"value" + (accent ? " accent" : "") + "\">"
                + escape(value) + "</span></div>";
    }

    private String nav(String active) {
        return """
                <nav class="topnav">
                  <a href="/view" class="%s">Дашборд</a>
                  <a href="/view/settings" class="%s">Настройки</a>
                  <a href="/view/trend-signal" class="%s">Сигнал Trend</a>
                  <a href="/view/guide" class="%s">Как пользоваться системой</a>
                  <a href="/view/final" class="%s">Итог + новости</a>
                  <a href="/view/signals" class="%s">Сигналы</a>
                  <a href="/view/recommendations" class="%s">Все рекомендации</a>
                  <a href="/view/paper" class="%s">Paper</a>
                  <a href="/view/walk-forward" class="%s">Walk-forward</a>
                  <a href="/view/strategy" class="%s">Описание торговой стратегии</a>
                  <a href="/view/full-core" class="%s">Full Core</a>
                </nav>
                """.formatted(
                active.equals("dashboard") ? "active" : "",
                active.equals("settings") ? "active" : "",
                active.equals("trend-signal") ? "active" : "",
                active.equals("guide") ? "active" : "",
                active.equals("final") ? "active" : "",
                active.equals("signals") ? "active" : "",
                active.equals("recommendations") ? "active" : "",
                active.equals("paper") ? "active" : "",
                active.equals("walkforward") ? "active" : "",
                active.equals("strategy") ? "active" : "",
                active.equals("fullcore") ? "active" : ""
        );
    }

    private String page(String title, String body, String nav) {
        return page(title, body, nav, OpsMode.COMPACT);
    }

    private String page(String title, String body, String nav, OpsMode opsMode) {
        UpsellAccess access = upsellService != null ? upsellService.access() : null;
        String upsellAttr = (access != null && access.enabled()) ? "on" : "off";
        String phase = access != null ? access.phase() : "OFF";
        return PAGE_TEMPLATE
                .replace("{{TITLE}}", escape(title))
                .replace("{{UPSELL}}", upsellAttr)
                .replace("{{UPSELL_PHASE}}", escape(phase))
                .replace("{{NAV}}", nav)
                .replace("{{OPS}}", opsHtml(opsMode))
                .replace("{{BODY}}", body);
    }

    private String formatZ(double z) {
        return z >= 0 ? String.format("+%.2f", z) : String.format("%.2f", z);
    }

    private String formatNum(double v) {
        return Double.isNaN(v) ? "—" : String.format("%.2f", v);
    }

    private String formatPct(double v) {
        return Double.isNaN(v) ? "—" : String.format("%.1f%%", v * 100);
    }

    private static String bookDiag(String book, List<TradingRecommendation> recs) {
        if (recs == null || recs.isEmpty()) {
            return "<strong>" + book + "</strong>: нет технических рекомендаций (книга не прогонялась или нет коинтеграции)";
        }
        long actionable = recs.stream()
                .filter(r -> r.signal() == TradingSignal.LONG_SPREAD || r.signal() == TradingSignal.SHORT_SPREAD)
                .count();
        long watch = recs.stream().filter(r -> r.signal() == TradingSignal.WATCH).count();
        double maxZ = recs.stream().mapToDouble(r -> Math.abs(r.currentZScore())).max().orElse(0);
        String top = recs.stream()
                .max(java.util.Comparator.comparingDouble(r -> Math.abs(r.currentZScore())))
                .map(r -> r.tickerY() + "/" + r.tickerX() + " " + r.signal() + " |Z|=" + String.format("%.2f", Math.abs(r.currentZScore())))
                .orElse("—");
        return String.format(
                "<strong>%s</strong>: пар %d, LONG/SHORT %d, WATCH %d, max |Z|=%.2f; лидер: %s",
                book, recs.size(), actionable, watch, maxZ, top
        );
    }

    /**
     * Gated Full Core preview page — value copy + soft CTA; no fabricated PnL.
     * feature: options | calendar-arb | trend | (blank = overview)
     */
    public String renderFullCore(String feature) {
        UpsellAccess access = upsellService.access();
        String key = feature == null ? "" : feature.trim().toLowerCase(Locale.ROOT);
        String title;
        String previewTitle;
        String previewBody;
        String earlyNote;
        switch (key) {
            case "options" -> {
                title = "Отчёт по опционам";
                previewTitle = "Опционный research-слой";
                previewBody = "Сводка по опционам на акции/фьючерсы: структура экспозиции, события и risk notes. "
                        + "Модуль в дорожной карте — это preview макета, не live-расчёт и не обещание доходности.";
                earlyNote = "Опционы — дальше трёх столпов TRINITY; early access для клиентов полного Core.";
            }
            case "calendar-arb", "arb" -> {
                title = "Календарный арбитраж";
                previewTitle = "Доска calendar spread (фьючерсы)";
                previewBody = "Контуры near/far, базис и roll — strategy 3. Сейчас макет locked-preview: "
                        + "research / decision-support без fake PnL и без auto-execution.";
                earlyNote = "Strategy 3 в разработке — early access / preorder для клиентов полного Core.";
            }
            case "trend" -> {
                title = "Trend desk";
                previewTitle = "Трендовый стол (strategy 2)";
                previewBody = "Breakout / regime desk поверх microstructure-задела. Не включён в live paper pairs — "
                        + "roadmap #2. Preview без fabricated метрик.";
                earlyNote = "Strategy 2 в разработке — early access / preorder для клиентов полного Core.";
            }
            default -> {
                title = "Full Core";
                previewTitle = "Полный Core — research-контур";
                previewBody = "Локальный candle-архив, deep replay, calendar arbitrage и trend desk. "
                        + "Ниже — честные locked-preview модулей: видно ценность, действие заблокировано "
                        + "вне trial / подписки. Research / decision-support.";
                earlyNote = "Инвестиция в следующие стратегии бренда, не в «уже готовый» live-модуль.";
            }
        }

        boolean locked = access.locksVisible();
        String statusLine;
        if (!access.enabled()) {
            statusLine = "Upsell выключен в конфиге.";
        } else if (access.hasFullCoreAccess()) {
            statusLine = "Full Core trial · осталось "
                    + (access.daysRemaining() == null ? "—" : access.daysRemaining()) + " дн. "
                    + "Модули ниже — early access / в разработке.";
        } else if ("EXPIRED".equals(access.phase())) {
            statusLine = "Trial закончился. Модули заблокированы — доступны в полном Core.";
        } else {
            statusLine = "Доступно в полном Core.";
        }

        String lockClass = locked ? "full-core-preview is-locked" : "full-core-preview is-trial";
        String badge = locked
                ? "<span class=\"full-core-badge\">Доступно в полном Core</span>"
                : "<span class=\"full-core-badge full-core-badge--trial\">Full Core trial</span>";

        String body = """
                <article class="strategy-doc full-core-page" id="full-core">
                  <p class="settings-eyebrow">TRINITY · коммерческий контур</p>
                  <h2>%s</h2>
                  <p class="lead">%s</p>
                  %s
                  <section class="%s" aria-label="Превью модуля">
                    %s
                    <h3>%s</h3>
                    <p>%s</p>
                    <div class="full-core-mock" aria-hidden="true">
                      <div class="full-core-mock-row"><span>Сводка</span><span class="muted">preview</span></div>
                      <div class="full-core-mock-row"><span>Сигналы / board</span><span class="muted">заблокировано</span></div>
                      <div class="full-core-mock-row"><span>Действие</span><span class="muted">нет live-исполнения</span></div>
                    </div>
                    <p class="meta">%s</p>
                  </section>
                  %s
                  <p class="meta"><a href="/view">← К дашборду</a> · <a href="/view/strategy#core-roadmap">Roadmap</a></p>
                </article>
                """.formatted(
                escape(title),
                escape(statusLine),
                tierLadderHtml(access),
                lockClass,
                badge,
                escape(previewTitle),
                escape(previewBody),
                escape(earlyNote),
                softCtaBlock(access)
        );
        return page("TRINITY — " + title, body, nav("fullcore"), OpsMode.NONE);
    }

    private UpsellAccess accessOrOff() {
        return upsellService != null ? upsellService.access()
                : new UpsellAccess(false, false, "OFF", null, null, null, 5000, 7500, 15000);
    }

    private String formatPrice(int rub) {
        return UpsellService.formatRub(rub);
    }

    private String trialBanner() {
        UpsellAccess access = accessOrOff();
        if (!access.enabled()) {
            return "";
        }
        if (access.hasFullCoreAccess()) {
            int days = access.daysRemaining() == null ? 0 : access.daysRemaining();
            return """
                    <aside class="trial-banner" role="status">
                      <span class="full-core-badge full-core-badge--trial">Full Core trial</span>
                      <p>Осталось <strong>%d</strong> дн. Locked-preview модули открыты как early access (в разработке).</p>
                      <a href="/view/full-core">Обзор Full Core</a>
                    </aside>
                    """.formatted(days);
        }
        if ("EXPIRED".equals(access.phase())) {
            return """
                    <aside class="trial-banner trial-banner--expired" role="status">
                      <span class="full-core-badge">Доступно в полном Core</span>
                      <p>Trial закончился. Ниже — честные превью модулей без fake PnL.</p>
                      <a href="/view/full-core">Открыть превью</a>
                    </aside>
                    """;
        }
        return "";
    }

    private String dashboardFullCoreTeasers() {
        UpsellAccess access = accessOrOff();
        if (!access.enabled()) {
            return "";
        }
        boolean locked = access.locksVisible();
        String badge = locked
                ? "<span class=\"full-core-badge\">Доступно в полном Core</span>"
                : "<span class=\"full-core-badge full-core-badge--trial\">trial</span>";
        return """
                <section class="dash-section full-core-teasers" id="full-core-teasers" aria-label="Full Core превью">
                  <div class="full-core-teasers-head">
                    <h2>Full Core</h2>
                    %s
                  </div>
                  <p class="meta">
                    Locked-preview: видно ценность модуля, действие заблокировано вне trial.
                    Research / decision-support — без обещания доходности.
                  </p>
                  <div class="locked-teaser-grid">
                    %s
                    %s
                    %s
                  </div>
                </section>
                """.formatted(
                badge,
                lockedTeaserLink(
                        "Посмотреть отчёт по опционам",
                        "Макет опционного research-слоя",
                        "/view/full-core?feature=options",
                        locked
                ),
                lockedTeaserLink(
                        "Доска календарного арбитража",
                        "Strategy 3 · futures calendar",
                        "/view/full-core?feature=calendar-arb",
                        locked
                ),
                lockedTeaserLink(
                        "Trend desk",
                        "Strategy 2 · roadmap",
                        "/view/full-core?feature=trend",
                        locked
                )
        );
    }

    private String lockedTeaserLink(String label, String hint, String href, boolean locked) {
        String cls = locked ? "locked-teaser is-locked" : "locked-teaser is-trial";
        String lock = locked ? "<span class=\"locked-teaser-lock\" aria-hidden=\"true\"></span>" : "";
        return """
                <a class="%s" href="%s" data-core-upsell="teaser">
                  %s
                  <strong>%s</strong>
                  <span class="locked-teaser-hint">%s</span>
                </a>
                """.formatted(cls, escape(href), lock, escape(label), escape(hint));
    }

    private String fullCoreBadge(String featureKey) {
        UpsellAccess access = accessOrOff();
        if (!access.enabled() || access.hasFullCoreAccess()) {
            return "";
        }
        String tip = "Full Core · " + formatPrice(access.fullPriceRub())
                + " ₽/мес. Календарный арбитраж / deep research. Research / decision-support.";
        return "<a class=\"full-core-badge\" href=\"/view/full-core?feature="
                + escape(featureKey == null ? "" : featureKey)
                + "\" title=\"" + escape(tip) + "\" data-core-upsell=\""
                + escape(featureKey == null ? "full-core" : featureKey)
                + "\">Доступно в полном Core</a>";
    }

    private String coreRoadmapBlock() {
        UpsellAccess access = accessOrOff();
        if (!access.enabled()) {
            return "";
        }
        return """
                <aside class="core-teaser" id="core-roadmap" data-core-upsell="full-core">
                  <span class="full-core-badge">Доступно в полном Core</span>
                  <div class="core-teaser-copy">
                    <strong>Roadmap #2 Trend · #3 Calendar arb</strong>
                    <p>
                      #2 — робот «Уровни + профиль» (BR M5) в sandbox/journal; live FORTS за флагом.
                      #3 calendar arb — early access / preorder Full Core.
                      Research / decision-support до OOS; без обещания доходности.
                    </p>
                  </div>
                  %s
                  <p class="meta"><a href="/view/full-core">Открыть превью Full Core</a></p>
                </aside>
                """.formatted(tierLadderHtml(access));
    }

    private String tierLadderHtml(UpsellAccess access) {
        if (access == null || !access.enabled()) {
            return "";
        }
        return """
                <ul class="tier-ladder" aria-label="Тарифная лестница">
                  <li><span class="tier-name">Обзор</span><span class="tier-price">%s ₽</span></li>
                  <li><span class="tier-name">Оператор</span><span class="tier-price">%s ₽</span></li>
                  <li class="is-anchor"><span class="tier-name">Full Core</span><span class="tier-price">%s ₽</span></li>
                </ul>
                """.formatted(
                formatPrice(access.overviewPriceRub()),
                formatPrice(access.operatorPriceRub()),
                formatPrice(access.fullPriceRub())
        );
    }

    private String softCtaBlock(UpsellAccess access) {
        if (access == null || !access.enabled()) {
            return "";
        }
        if (access.hasFullCoreAccess()) {
            return """
                    <div class="full-core-cta">
                      <p>Сейчас у вас reverse trial полного Core. Модули #2/#3 — early access, код live pairs не подменяется.</p>
                    </div>
                    """;
        }
        return """
                <div class="full-core-cta">
                  <p>
                    Полный Core — <strong>%s ₽/мес</strong>
                    (якорь относительно Оператора %s ₽). Мягкий CTA, без биллинга в этом scaffold.
                  </p>
                  <div class="ops-actions">
                    <a class="btn btn-primary" href="/view/strategy#core-roadmap">Early access / roadmap</a>
                    <button type="button" class="btn btn-ghost" data-core-upsell="cta" id="full-core-soft-cta">Подробнее</button>
                  </div>
                </div>
                """.formatted(formatPrice(access.fullPriceRub()), formatPrice(access.operatorPriceRub()));
    }

    private String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
