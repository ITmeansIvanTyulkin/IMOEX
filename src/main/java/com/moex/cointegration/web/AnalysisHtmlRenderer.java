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
                <p class="footnote">TRINITY — research / decision-support. Не индивидуальная инвестиционная рекомендация. Paper PnL — research-метрика (qty×цена, не брокерский отчёт).</p>
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
                    Запускайте анализ и обновляйте paper прямо отсюда.
                    <strong>INTRADAY</strong> обновляется автоматически в :05 каждого часа 10–18 (пн–пт), пока приложение запущено.
                    Логин и пароль оператора — в полях ниже (сохраняются только в этом браузере).
                  </p>
                  <div class="alert-prefs">
                    <label class="check-label"><input type="checkbox" id="ops-alerts-enabled" checked> Алерты при новой paper-сделке</label>
                    <label class="check-label"><input type="checkbox" id="ops-alerts-sound" checked> Звук</label>
                    <button type="button" class="btn btn-ghost" id="ops-notify-permission">Уведомления macOS / Windows</button>
                  </div>
                  <p class="meta alert-hint">Баннер справа сверху в браузере + системное уведомление (если разрешено). На Windows позиция задаётся ОС (обычно правый нижний угол).</p>
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
    public String renderDashboard(
            AnalysisReport report,
            List<TradingRecommendation> recommendations,
            com.moex.cointegration.model.MarketRegimeSnapshot regime
    ) {
        long actionable = recommendations.stream()
                .filter(r -> r.signal() == TradingSignal.LONG_SPREAD || r.signal() == TradingSignal.SHORT_SPREAD)
                .count();

        StringBuilder body = new StringBuilder();
        body.append(regimeBanner(regime));
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

        return page("TRINITY — дашборд", body.toString(), nav("dashboard"));
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
        body.append(recommendationsTable(recommendations, "Рекомендаций пока нет. Нажмите «Анализ + paper» в пульте выше."));
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
                  <p>Нажмите <strong>«Анализ + paper»</strong> в пульте выше.
                  Логин/пароль API — из вашего локального <code>application-local.yml</code>
                  (в репозитории секретов нет). После завершения страница обновится сама.</p>
                  <p class="meta">Если свечей ещё нет — сначала «Скачать свечи», либо «Анализ + скачать свечи».</p>
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
                    TRINITY сейчас полностью реализует модуль cointegration: парный трейдинг в боковике
                    на горизонте нескольких дней и интрадей (узкая книга 1–2 пары, профиль от ~100 тыс. ₽).
                    Мы не угадываем, вырастет ли рынок. Ищем две акции, которые обычно «ходят вместе»,
                    и торгуем их временный разрыв — ставку на то, что разрыв снова сожмётся.
                    Календарный арбитраж и опционы — следующие стратегии бренда, пока в дорожной карте.
                  </p>

                  <nav class="strategy-toc" aria-label="Содержание">
                    <strong>Содержание</strong>
                    <ol>
                      <li><a href="#idea">Идея простыми словами</a></li>
                      <li><a href="#pipeline">Что за чем происходит</a></li>
                      <li><a href="#universe">Как отбираются акции</a></li>
                      <li><a href="#pairs">Как пары попадают в анализ</a></li>
                      <li><a href="#regime">Режим рынка: только боковик</a></li>
                      <li><a href="#signals">Как появляется сигнал</a></li>
                      <li><a href="#news">Новостной фильтр</a></li>
                      <li><a href="#size">Размер позиции и лимиты</a></li>
                      <li><a href="#exits">Как выходим</a></li>
                      <li><a href="#paper">Paper и проверка на истории</a></li>
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
                    <span>MOEX daily+1H</span><i>→</i>
                    <span>Capital split</span><i>→</i>
                    <span>DAILY tech→FA→paper</span><i>→</i>
                    <span>INTRADAY tech→paper</span>
                  </div>
                  <ol class="pipeline">
                    <li><strong>Капитал.</strong> Equity → слоты и gross: ~40% DAILY / ~60% INTRADAY (без плеча до 1M). Доли <em>фиксированы</em>: пустой DAILY не отдаёт лимит INTRADAY.</li>
                    <li><strong>DAILY.</strong> Дневные свечи → EG/FDR/Z → фундамент (MOEX+RSS) → paper-journal.json.</li>
                    <li><strong>INTRADAY.</strong> 1H свечи ISS → EG/FDR/Z (окна ~48 баров, max-hold ~7ч) → без FA → paper-journal-intraday.json, flatten ~18:30.</li>
                    <li><strong>Режим.</strong> ADX индекса блокирует <em>новые</em> входы в обеих книгах при TREND.</li>
                  </ol>
                  <div class="callout">
                    Нет ручного переключателя «сегодня daily / сегодня intraday» — оба горизонта в одном автоматическом цикле
                    (кнопка «Анализ + paper» или cron). Источник свечей — только MOEX ISS (TradingView не подключаем).
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
                      <strong>Качество серии.</strong>
                      Считаем спред, Z, half-life, Sharpe симуляции. Слишком медленный возврат к среднему
                      или слабые метрики не дают входной сигнал.
                    </li>
                  </ol>
                  <div class="callout">
                    На дашборде «Топ-пары по Sharpe» — это уже прошедшие статистику и отобранные для обзора.
                    Сырой сигнал LONG/SHORT ещё не равен разрешению торговать: дальше режим рынка, новости и лимиты книги.
                  </div>

                  <h3 id="regime">5. Режим рынка: стратегия только боковик</h3>
                  <p>
                    Mean-reversion плохо работает в сильном тренде: спред может «уехать» вместе с рынком
                    и не вернуться к среднему. Поэтому перед входами смотрим <strong>ADX индекса IMOEX</strong>
                    (баннер «Режим рынка» на дашборде):
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
                  <p>Именно страница <a href="/view/final">Итог + новости</a> — операторский «разрешено / нет» после FA.</p>

                  <h3 id="size">8. Размер позиции и лимиты портфеля</h3>
                  <p>
                    Профиль оператора: счёт <strong>от ~100 000 ₽</strong>, узкая книга
                    <strong>1–2 пары</strong> (не широкий портфель). Базовый notional на ногу
                    в конфиге по умолчанию ~30 000 ₽ — чтобы одна пара с двумя ногами
                    помещалась в капитал без плеча. Дальше размер масштабируется:
                    волатильность спреда, расстояние до стопа по Z, REDUCE и режим NEUTRAL.
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
                    <li><strong>Partial take-profit</strong> — на полпути к нулю можно зафиксировать часть;</li>
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
                    (с учётом упрощённого slippage и borrow), а не как «1 Z = 1%».
                    Это всё ещё research-метрика, не брокерский отчёт.
                  </p>
                  <p>
                    <a href="/view/walk-forward">Walk-forward</a> режет историю на train/test окна:
                    на обучении проверяем коинтеграцию, на тесте гоняем правила без подглядывания вперёд.
                    Это проверка «не подогнали ли мы всё под прошлый год», а не гарантия прибыли.
                  </p>

                  <h3 id="limits">11. Честные ограничения</h3>
                  <ul>
                    <li>Стратегия классическая (textbook pairs) — только боковик, без трендового модуля.</li>
                    <li>Коинтеграция на истории не обещает коинтеграцию завтра.</li>
                    <li>Новости по ISS — эвристика, не полный fundamental research.</li>
                    <li>Шорт, borrow, проскальзывание и комиссии в жизни жёстче, чем в модели.</li>
                    <li>Нужны месяцы чистого paper track-record, прежде чем судить об alpha.</li>
                  </ul>
                  <div class="callout">
                    Это research / decision-support, не индивидуальная инвестиционная рекомендация.
                    Параметры порогов живут в <code>application.yml</code> (<code>imoex.cointegration</code>,
                    <code>universe</code>, <code>risk</code>, <code>regime</code>, <code>news</code>, <code>paper</code>).
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
                    <li>Откройте <a href="/view">http://localhost:8080/view</a> — дашборд и пульт оператора.</li>
                    <li>Первый раз нажмите <strong>«Анализ + скачать свечи»</strong> — скачает историю с MOEX ISS (может занять много минут).</li>
                    <li>Дальше обычно достаточно <strong>«Анализ + paper»</strong> — пересчёт без полного скачивания.</li>
                  </ol>
                  <div class="callout">
                    GET-страницы <code>/view/*</code> открываются без пароля. Кнопки пульта шлют POST на API —
                    нужны логин и пароль из <code>application-local.yml</code> (по умолчанию user <code>imoex</code>).
                  </div>

                  <h3 id="ops">2. Пульт оператора</h3>
                  <p>На каждой странице <code>/view/*</code> сверху — блок «Пульт оператора»:</p>
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
                    Логин и пароль сохраняются в <em>этом браузере</em> (localStorage). Журнал действий пульта — в блоке «Лог» под кнопками.
                  </p>

                  <h3 id="pages">3. Разделы верхнего меню</h3>
                  <table class="params">
                    <thead><tr><th>Раздел</th><th>Зачем открывать</th></tr></thead>
                    <tbody>
                      <tr><td><a href="/view">Дашборд</a></td><td>Сводка: режим рынка (ADX), топ-пары, последний прогон.</td></tr>
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
                    <li>При сомнениях — график пары и баннер «Режим рынка» на дашборде (TREND блокирует новые входы).</li>
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
                    В пульте: чекбоксы «Алерты при новой paper-сделке» и «Звук».
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
                    <li>На дашборде виден баннер режима рынка (SIDEWAYS / NEUTRAL / TREND)</li>
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
            body.append("<th>Opened</th><th>Closed</th><th>Notes</th><th></th>");
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
