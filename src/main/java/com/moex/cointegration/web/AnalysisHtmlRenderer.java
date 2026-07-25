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
              <link href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;600&family=IBM+Plex+Sans:wght@400;500;600&family=Source+Serif+4:opsz,wght@8..60,500;8..60,600&display=swap" rel="stylesheet">
              <link rel="stylesheet" href="/css/operator.css">
            </head>
            <body>
              <header class="site-header">
                <h1 class="brand">IMOEX Cointegration</h1>
                <p class="tagline">Research · paper trading · walk-forward — без брокера, с прозрачным journal</p>
              </header>
              {{NAV}}
              <main>
                {{OPS}}
                {{BODY}}
                <p class="footnote">Research / decision-support. Не индивидуальная инвестиционная рекомендация. Paper PnL — псевдо-метрика.</p>
              </main>
              <script src="/js/operator.js"></script>
            </body>
            </html>
            """;

    private String opsPanel() {
        return """
                <section class="ops-panel" id="ops-panel">
                  <h2>Пульт оператора</h2>
                  <p class="ops-lead">
                    Запускайте анализ и обновляйте paper прямо отсюда — без консоли.
                    POST-запросы идут с HTTP Basic (логин ниже). Полный refresh свечей может занять много минут.
                  </p>
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

    /**
     * Главная страница: сводка, сигналы входа, топ-пары.
     */
    public String renderDashboard(AnalysisReport report, List<TradingRecommendation> recommendations) {
        long actionable = recommendations.stream()
                .filter(r -> r.signal() == TradingSignal.LONG_SPREAD || r.signal() == TradingSignal.SHORT_SPREAD)
                .count();

        StringBuilder body = new StringBuilder();
        body.append(summaryBlock(report, recommendations.size(), actionable));
        body.append("<h2>Сигналы входа (LONG / SHORT)</h2>");
        body.append("""
                <div class="hint">
                  <strong>Как читать сигнал.</strong> Парная торговля — это одновременно купить одну акцию и продать другую.
                  Зелёная стрелка на графике = момент «купить спред», красная = «продать спред».
                  Откройте <a href="/view/final">Итог + новости</a>: там техника уже пропущена через новостной фильтр (ENTER / REDUCE / BLOCK).
                  Также: <a href="/view/paper">Paper journal</a> и <a href="/view/walk-forward">Walk-forward OOS</a>.
                </div>
                """);
        body.append(recommendationsTable(
                recommendations.stream()
                        .filter(r -> r.signal() == TradingSignal.LONG_SPREAD || r.signal() == TradingSignal.SHORT_SPREAD)
                        .toList(),
                "Нет активных сигналов входа. См. полный список рекомендаций."
        ));
        body.append("<h2>Топ-пары по Sharpe</h2>");
        body.append(topPairsTable(report.topPairs()));

        return page("IMOEX — дашборд", body.toString(), nav("dashboard"));
    }

    /** Страница всех торговых рекомендаций. */
    public String renderAllRecommendations(List<TradingRecommendation> recommendations) {
        StringBuilder body = new StringBuilder();
        body.append("<p class=\"meta\">Всего рекомендаций: ").append(recommendations.size()).append("</p>");
        body.append(recommendationsTable(recommendations, "Рекомендаций пока нет. Нажмите «Анализ + paper» в пульте выше."));
        return page("IMOEX — рекомендации", body.toString(), nav("recommendations"));
    }

    /** Страница только actionable-сигналов. */
    public String renderSignals(List<TradingRecommendation> signals) {
        StringBuilder body = new StringBuilder();
        body.append("<p class=\"meta\">Сигналов входа: ").append(signals.size()).append("</p>");
        body.append(recommendationsTable(signals, "Нет пар с |Z| ≥ порога входа."));
        return page("IMOEX — сигналы", body.toString(), nav("signals"));
    }

    /** Страница «анализ не выполнен». */
    public String renderEmpty() {
        String body = """
                <div class="empty">
                  <h2>Анализ ещё не выполнен</h2>
                  <p>Нажмите <strong>«Анализ + paper»</strong> в пульте выше (логин по умолчанию
                  <code>imoex</code> / <code>change-me</code>). После завершения страница обновится сама.</p>
                  <p class="meta">Если свечей ещё нет — сначала «Скачать свечи», либо «Анализ + скачать свечи».</p>
                </div>
                """;
        return page("IMOEX — нет данных", body, nav("none"));
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
                  <strong>Итог после новостей (дневной горизонт).</strong>
                  Сначала техника (Z и стрелки), затем фильтр новостей MOEX за последние дни
                  и проверка «торгуется ли бумага». Смотрите колонку <em>Итог</em>:
                  ENTER — можно входить, REDUCE — уменьшенный размер, BLOCK — пропускать.
                </div>
                """);
        body.append("<p class=\"meta\">Строк: ").append(rows.size()).append("</p>");
        if (rows.isEmpty()) {
            body.append("<p class=\"empty-msg\">Итоговых рекомендаций нет. Запустите POST /api/analysis/run</p>");
            return page("IMOEX — итог", body.toString(), nav("final"));
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
                    .append(escape(f.decisionSummary())).append("</div>")
                    .append("<div class=\"explain\">").append(nl2br(escape(f.news().summary())));
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
        return page("IMOEX — итог", body.toString(), nav("final"));
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
        StringBuilder body = new StringBuilder();
        body.append("""
                <div class="hint">
                  <strong>Paper journal — автомат.</strong> Кнопка «Анализ + paper» или daily cron
                  открывает ENTER/REDUCE, держит позицию с mark-to-market и закрывает
                  при возврате Z≈0, стопе |Z| или time-stop. PnL — псевдо (1 Z ≈ 1% notional Y), без брокера.
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
            body.append("<p class=\"empty\">Журнал пуст. Нажмите «Анализ + paper» — появятся AUTO OPEN сделки.</p>");
        } else {
            body.append("<div class=\"table-wrap\"><table><thead><tr>");
            body.append("<th>Статус</th><th>Пара</th><th>Сигнал</th><th>Decision</th>");
            body.append("<th class=\"num\">Entry Z</th><th class=\"num\">Mark/Exit Z</th>");
            body.append("<th class=\"num\">Notional Y</th>");
            body.append("<th class=\"num\">PnL %*</th><th class=\"num\">PnL ₽*</th>");
            body.append("<th>Opened</th><th>Closed</th><th>Notes</th><th></th>");
            body.append("</tr></thead><tbody>");
            for (PaperTradeEntry e : entries) {
                Double markOrExit = e.exitZ() != null ? e.exitZ() : e.markZ();
                Double pct = e.pnlPct() != null ? e.pnlPct() : e.unrealizedPnlPct();
                Double rub = e.pnlRub() != null ? e.pnlRub() : e.unrealizedPnlRub();
                body.append("<tr>");
                body.append("<td>").append(escape(e.status())).append("</td>");
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
            body.append("<p class=\"empty\">Нет walk-forward отчёта. Нажмите «Walk-forward» в пульте или запустите полный анализ.</p>");
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
                  <a href="/view/final" class="%s">Итог + новости</a>
                  <a href="/view/signals" class="%s">Сигналы</a>
                  <a href="/view/recommendations" class="%s">Все рекомендации</a>
                  <a href="/view/paper" class="%s">Paper</a>
                  <a href="/view/walk-forward" class="%s">Walk-forward</a>
                  <a href="/api/analysis/final" target="_blank">JSON итог</a>
                </nav>
                """.formatted(
                active.equals("dashboard") ? "active" : "",
                active.equals("final") ? "active" : "",
                active.equals("signals") ? "active" : "",
                active.equals("recommendations") ? "active" : "",
                active.equals("paper") ? "active" : "",
                active.equals("walkforward") ? "active" : ""
        );
    }

    private String page(String title, String body, String nav) {
        return PAGE_TEMPLATE
                .replace("{{TITLE}}", escape(title))
                .replace("{{NAV}}", nav)
                .replace("{{OPS}}", opsPanel())
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
