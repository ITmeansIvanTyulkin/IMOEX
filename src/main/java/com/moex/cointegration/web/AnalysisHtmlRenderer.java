package com.moex.cointegration.web;

import com.moex.cointegration.model.AnalysisReport;
import com.moex.cointegration.model.FinalTradeDecision;
import com.moex.cointegration.model.FinalTradeRecommendation;
import com.moex.cointegration.model.NewsTriggerHit;
import com.moex.cointegration.model.PairAnalysisResult;
import com.moex.cointegration.model.PaperJournal;
import com.moex.cointegration.model.PaperTradeEntry;
import com.moex.cointegration.model.TradingRecommendation;
import com.moex.cointegration.model.TradingSignal;
import com.moex.cointegration.model.WalkForwardReport;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Формирует HTML-страницы с таблицами для просмотра в браузере.
 */
@Component
public class AnalysisHtmlRenderer {

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
              <link rel="stylesheet" href="/css/operator.css">
            </head>
            <body>
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
              <main>
                {{OPS}}
                {{BODY}}
                <div id="trinity-toast-stack" class="toast-stack" aria-live="assertive"></div>
                <div id="trinity-upsell-host" class="upsell-host" aria-live="polite"></div>
                <p class="footnote">TRINITY — research / decision-support. Не индивидуальная инвестиционная рекомендация. Paper PnL — research-метрика (qty×цена, не брокерский отчёт).</p>
              </main>
              <script src="/js/operator.js"></script>
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
                    Настройте один раз: логин API, алерты и запуск анализа.
                    Учётные данные сохраняются только в этом браузере.
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
                          <label for="ops-user">API user</label>
                          <input id="ops-user" type="text" autocomplete="username" spellcheck="false">
                        </div>
                        <div class="field">
                          <label for="ops-pass">API password</label>
                          <input id="ops-pass" type="password" autocomplete="current-password">
                        </div>
                        <button type="button" class="btn btn-ghost" id="ops-save-creds">Сохранить логин</button>
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
                    Быстрый запуск. Полный пульт и консоль брокера — в
                    <a href="/view/settings">Настройках</a>.
                  </p>
                  <div class="ops-compact-actions">
                    <input id="ops-user" type="hidden">
                    <input id="ops-pass" type="hidden">
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
                  <input id="ops-user" type="hidden">
                  <input id="ops-pass" type="hidden">
                  <div class="dash-cta-copy">
                    <p class="dash-cta-label">Действие</p>
                    <p class="dash-cta-text">Обновить сигналы и paper-журнал. Учётные данные и брокер — в настройках.</p>
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
        body.append(dashboardWidgetGrid(regime));
        body.append("""
                <aside class="next-steps" id="dash-next-steps">
                  <p class="next-steps-label">Что сделать сейчас</p>
                  <ol>
                    <li>Смотрите виджет «Режим рынка» — TREND блокирует новые входы.</li>
                    <li>Нажмите <em>Анализ + paper</em> ниже — обновит сигналы и журнал.</li>
                    <li>Разбор сделок: <a href="/view/final">Итог</a> / <a href="/view/paper">Paper</a>.
                      Токен и песочница — в <a href="/view/settings">Настройках</a>.</li>
                  </ol>
                </aside>
                """);
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
                </div>
                """.formatted(opsPanel(), brokerConsolePanel());
        return page("TRINITY — настройки", body, nav("settings"), OpsMode.NONE);
    }

    private String dashboardWidgetGrid(com.moex.cointegration.model.MarketRegimeSnapshot regime) {
        if (regime == null) {
            regime = com.moex.cointegration.model.MarketRegimeSnapshot.unknown();
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
        return """
                <section class="widget-grid" aria-label="Сводка дашборда">
                  <article class="widget-card" id="widget-paper">
                    <div class="widget-title">Paper</div>
                    <div class="widget-body">
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
                    </div>
                  </article>
                  <article class="widget-card" id="widget-broker">
                    <div class="widget-title">Брокер</div>
                    <div class="widget-body">
                      <div class="donut" id="widget-broker-donut" style="--p:0;--c:var(--info)">
                        <div class="donut-center">
                          <strong id="widget-broker-center">—</strong>
                          <span>статус</span>
                        </div>
                      </div>
                      <div class="widget-meta">
                        <div class="widget-stat"><span class="k"><i class="swatch info"></i>Сводка</span><span class="v" id="dash-broker-status">—</span></div>
                        <div class="widget-stat"><span class="k"><i class="swatch gold"></i>Контур</span><span class="v" id="widget-broker-mode">—</span></div>
                      </div>
                    </div>
                  </article>
                  <article class="widget-card" id="widget-final">
                    <div class="widget-title">Final</div>
                    <div class="widget-body">
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
                    </div>
                  </article>
                  <article class="widget-card" id="widget-regime">
                    <div class="widget-title">Режим рынка</div>
                    <div class="widget-body">
                      <div class="donut" id="widget-regime-donut" style="--p:100;--c:%s">
                        <div class="donut-center">
                          <strong id="widget-regime-center">%s</strong>
                          <span>ADX %s</span>
                        </div>
                      </div>
                      <div class="widget-meta">
                        <div class="widget-stat"><span class="k"><i class="swatch %s" id="widget-regime-swatch"></i>Режим</span><span class="v" id="widget-regime-label">%s</span></div>
                        <div class="widget-stat"><span class="k">Подсказка</span><span class="v" id="widget-regime-hint">%s</span></div>
                      </div>
                    </div>
                  </article>
                </section>
                """.formatted(color, escape(shortLabel), escape(adx), swatch, escape(label), escape(hint));
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
        String body = """
                <article class="strategy-doc">
                  <h2>Описание торговой стратегии</h2>
                  <p class="lead">
                    TRINITY сейчас в live paper ведёт <strong>DAILY</strong> pairs mean-reversion в боковике
                    (фокус — металлы / mining; нефть в equities-парах отложена на фьючерсы/опционы).
                    <strong>INTRADAY</strong> — только research (1H EG/Z/метрики), без paper-торговли.
                    Мы не угадываем направление рынка: ищем временный разрыв связанной пары и ставим на сжатие.
                    Календарный арбитраж и опционы — следующие стратегии бренда, пока в дорожной карте.
                  </p>

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
                      (<code>quant/trend</code>, <code>imoex.microstructure.trend</code>), включается на roadmap #2.
                    </p>
                  </aside>

                  <nav class="strategy-toc" aria-label="Содержание">
                    <strong>Содержание</strong>
                    <ol>
                      <li><a href="#atas">Функционал ATAS внутри TRINITY</a></li>
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
                    Источники: MOEX sitenews и опционально RSS Interfax / RBC.
                    Те же правила-триггеры (earnings miss, guidance down, SPO, M&amp;A, санкции…).
                    При расхождении с LONG/SHORT в «Итоге» будет явный
                    <strong>CONFLICT: техника vs фундамент</strong>.
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
                  <p>Именно страница <a href="/view/final">Итог + новости</a> — операторский «разрешено / нет» после FA.
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
                  </p>
                  <p>Запуск через API (нужны локальные свечи в <code>data/candles/</code>):</p>
                  <pre class="code-block">POST /api/analysis/historical-replay?tickerY=SBER&amp;tickerX=LKOH&amp;from=2023-01-01&amp;to=2025-12-31&amp;book=DAILY</pre>
                  <p>
                    Ответ: сделки, net/realized PnL ₽, win rate, max drawdown.
                    Подробнее в <a href="/view/guide">Как пользоваться системой</a>.
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
                """;
        return page("TRINITY — описание стратегии", body, nav("strategy"));
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
                    <li>Создайте <code>src/main/resources/application-local.yml</code> с паролем API и ключом <code>imoex.run.unlock</code> (без них приложение не стартует).</li>
                    <li>Запустите: <code>mvn spring-boot:run</code> и дождитесь <code>Started CointegrationApplication</code>.</li>
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
                      <tr><td><a href="/view/final">Итог + новости</a></td><td><strong>Главный операторский экран</strong> — ENTER / REDUCE / WATCH / BLOCK после фундамента (DAILY).</td></tr>
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
                    <li>Убедиться, что приложение запущено (<code>mvn spring-boot:run</code>).</li>
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
                    <li>В логе терминала: <code>Started CointegrationApplication</code></li>
                    <li>Кнопка «Анализ + paper» завершается без 401 (логин/пароль верные)</li>
                    <li>В <code>data/candles/</code> есть JSON тикеров (после первого refresh)</li>
                    <li><a href="/view/final">Итог + новости</a> показывает таблицу (может быть пустой — нет ENTER)</li>
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

    /**
     * Итоговая таблица: техника + новости + решение ENTER/REDUCE/WATCH/BLOCK.
     */
    public String renderFinalTable(List<FinalTradeRecommendation> rows) {
        StringBuilder body = new StringBuilder();
        body.append("""
                <div class="hint">
                  <strong>Итог после фундамента (multi-day / DAILY).</strong>
                  Порядок: техника → фундамент (MOEX + опционально RSS) → рекомендация → paper.
                  В INTRADAY фундамент пропускается. Смотрите колонку <em>Итог</em>:
                  ENTER / REDUCE / WATCH / BLOCK. При расхождении — текст
                  <em>CONFLICT: техника vs фундамент</em>.
                </div>
                """);
        body.append("<p class=\"meta\">Строк: ").append(rows.size()).append("</p>");
        if (rows.isEmpty()) {
            body.append("<p class=\"empty-msg\">Итоговых рекомендаций нет.</p>");
            return page("TRINITY — итог", body.toString(), nav("final"));
        }

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
            body.append("<td class=\"details\"><div class=\"summary\">")
                    .append(escape(f.decisionSummary())).append("</div>");
            if (f.rationale() != null && !f.rationale().isBlank()) {
                body.append("<div class=\"rationale meta\"><strong>Почему:</strong> ")
                        .append(escape(f.rationale())).append("</div>");
            }
            body.append("<div class=\"explain\">").append(nl2br(escape(f.news().summary())));
            if (!f.news().hits().isEmpty()) {
                body.append("<br><br>");
                int i = 0;
                for (NewsTriggerHit hit : f.news().hits()) {
                    if (i++ >= 3) {
                        body.append("…<br>");
                        break;
                    }
                    body.append("• ").append(escape(hit.ticker())).append(": ")
                            .append(escape(hit.title())).append("<br>");
                }
            }
            body.append("</div></td>");
            body.append("<td class=\"links\">").append(chartPageLink(f.tickerY(), f.tickerX())).append("</td>");
            body.append("</tr>");
        }

        body.append("</tbody></table></div>");
        return page("TRINITY — итог", body.toString(), nav("final"));
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
            body.append("<p><strong>Журнал пуст</strong> — за последний прогон нечего открывать в paper.</p>");
            body.append("<p>Paper создаёт сделки только при <strong>ENTER</strong> / <strong>REDUCE_SIZE</strong> ");
            body.append("(после техники и FA для DAILY; для INTRADAY — только техника).</p>");
            body.append("<ul>");
            body.append("<li>").append(bookDiag("DAILY", dailyRecs)).append("</li>");
            body.append("<li>").append(bookDiag("INTRADAY", intradayRecs)).append("</li>");
            body.append("</ul>");
            body.append("<p class=\"meta\">Типичные причины: |Z| &lt; 2, half-life вне порога, режим TREND (ADX), ");
            body.append("нет коинтегрированных пар после FDR. Это нормально — стратегия не форсирует входы.</p>");
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
                  <a href="/view/guide" class="%s">Как пользоваться системой</a>
                  <a href="/view/final" class="%s">Итог + новости</a>
                  <a href="/view/signals" class="%s">Сигналы</a>
                  <a href="/view/recommendations" class="%s">Все рекомендации</a>
                  <a href="/view/paper" class="%s">Paper</a>
                  <a href="/view/walk-forward" class="%s">Walk-forward</a>
                  <a href="/view/strategy" class="%s">Описание торговой стратегии</a>
                </nav>
                """.formatted(
                active.equals("dashboard") ? "active" : "",
                active.equals("settings") ? "active" : "",
                active.equals("guide") ? "active" : "",
                active.equals("final") ? "active" : "",
                active.equals("signals") ? "active" : "",
                active.equals("recommendations") ? "active" : "",
                active.equals("paper") ? "active" : "",
                active.equals("walkforward") ? "active" : "",
                active.equals("strategy") ? "active" : ""
        );
    }

    private String page(String title, String body, String nav) {
        return page(title, body, nav, OpsMode.COMPACT);
    }

    private String page(String title, String body, String nav, OpsMode opsMode) {
        return PAGE_TEMPLATE
                .replace("{{TITLE}}", escape(title))
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
