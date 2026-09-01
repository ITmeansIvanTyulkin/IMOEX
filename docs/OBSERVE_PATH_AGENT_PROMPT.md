# Handoff prompt: TRINITY operator (Exclusive observe + desk/playbook)

> **Как использовать:** скопируй блок «PROMPT FOR NEW AGENT» целиком в новый чат Cursor.  
> **Обновлено:** 2026-09-01 ~19:20 MSK · `dev` запушен: IMOEX `7fe9a8a`, IMOEX-core `e2fdb9a`.  
> **Язык оператора:** русский, коротко и по делу. Не сыпать `§14` / путями `/view/...` в UI; в коде и этом промпте чеклист-номера допустимы.

---

## PROMPT FOR NEW AGENT

Ты продолжаешь работу оператора TRINITY на машине Ивана.

Workspace: `/Users/ivan/MEGA/Work/TRINITY/`  
- `IMOEX/` — публичный git (оболочка, UI, `application.yml`, scripts, docs). Remote `origin` → `ITmeansIvanTyulkin/IMOEX.git`, ветка **`dev`**.  
- `IMOEX-core/` — закрытое ядро (`trinity-trend` / `trinity-pairs` / `trinity-calendar-arb` / `trinity-runtime`). Remote `origin` → `ITmeansIvanTyulkin/IMOEX-core.git`, ветка **`dev`**.  
Unlock = ядро на classpath. Не коммитить `/data/`, секреты, `application-local.yml`, токены.

Две оси работы **не смешивать без явной просьбы оператора:**

1. **observePathToReal** (главная торговая цель) — честное наблюдение **Trend Exclusive #1, BR M5 fair-paper**, без крутилок pad/knife/smash/buffer.  
2. **Продукт desk / UX / разделение плейбуков** — только если оператор просит UI, копирайт, график, изоляцию сделок. Это **не** повод ретьюнить стратегию.

Pairs DAILY и calendar-arb оператор смотрит сам, пока не попросил иначе.

### Активная цель observePathToReal (не закрыта)

```
Наблюдать Trend Exclusive fair-paper после гейтов 2026-08-24 по пути к реалу:
(1) 2–3+ торговых дня FORMING_BAR + вечерний journal (SWEEP/THROUGH/skip/smash/knife) без новых крутилок;
(2) стабильный paper/OOS expectancy;
(3) только потом FORTS SL в стакане малым размером;
(4) scale при совпадении с journal.

Жёстко: doNotTuneOnSight — не крутить pad/knife/smash/buffer «на глаз».
```

**Критерий «цель достигнута»:** live FORTS SL 1 лот работает, journal совпадает с paper, оператор явно дал go. **Phase C заблокирован кодом + human OOS.** Не объявляй цель выполненной.

### Состояние на 2026-09-01 EOD (~18:09 MSK)

| Метрика | Значение |
|--------|----------|
| **Phase A** | **done** 4 полных дня 25–28.08 (baseline 24.08 не считается). 01.09 = extra paper day 6 |
| **OOS cumulative (path-log)** | **14** сделок, **+2 180 ₽**, expectancy **~156 ₽/trade**, WR **64%**, labeled SL 100% |
| **01.09** | 2 TP: overnight **BRU6** BUY RETEST TP2 **+434 ₽** @10:15 (89.33→89.64, 2/4); intraday **BRV6** BUY RETEST TP2 **+301 ₽** @16:40. gateHits=0 |
| **Front month** | **BRV6** (roll после экспирации BRU6 31.08). Last trade BRV6 **2026-10-01** |
| **FORMING_BAR** | вкл. SL/TP по ходу бара (как брокер) |
| **liveExecution** | **false** |
| **playbook (yml + ui-settings)** | `levels-profile-br-m5`, **`parallel-playbooks: false`** (positional H1 hvnHills OOM — **не restore `both`**) |
| **Phase C** | deferred — human OOS + нет single-leg FORTS PostStop |
| **8080** | операторский desk `http://127.0.0.1:8080/view/trend-signal` |

**Авторитетный лог (не в git):** `IMOEX/data/trend-observe-path-log.json`  
**UI settings (не в git):** `IMOEX/data/trend-ui-settings.json` → instrumentId **BRV6**, live=false.

**Завтра (2026-09-02):** `bash IMOEX/scripts/trend_observe_resume.sh 2026-09-02` ~09:55; evening `python3 scripts/trend_observe_evening.py` ~18:02. Не live, не крутить гейты.

---

### Жёсткие правила (всегда)

1. **`doNotTuneOnSight: true`** — не менять `sl-sweep-buffer-points` (5), smash-gate, deep-poke knife, `allow-zone-pad`, `stop-points`/`tp1-points` BR без явного research gate + OOS и без просьбы оператора.  
2. **Checklist fidelity** — при конфликте EXT vs §8/§14 **побеждает чеклист**. Side law #1: BOT=BUY bounce, TOP=SELL bounce. §8 RETEST long только пока close **≥ TOP↑**; §8 short только пока close **≤ BOT↓**. Close **внутри коробки** = bounce, не «зависший» retest. Gate: `ExclusiveSide`. HTF / CL / макро / one-setup **не переворачивают сторону**.  
3. **Side law #2 (позиционная):** H1 UP → только LONG, H1 DOWN → только SHORT; вход в промежуточный HVN последних трёх полок; сетка 1:1:2:4. Exclusive M5 session/gap/TOP-BOT **не отменяет** валидную позиционную пирамиду. Gate: `PositionalSide`.  
4. **`liveExecution=true` для trend — запрещён** без Phase C code + human OOS + явный go оператора. Smoke FAIL не поднимать live автоматически.  
5. **Не restore `playbook: both` / `parallel-playbooks: true`**, пока positional hvnHills OOM не починен и оператор не сказал. Desk-вкладки «позиционная» всё равно существуют — это UI, не второй armed робот.  
6. **Не commit `/data/`**, journals, path-log, tape/DOM архив, секреты.  
7. **Коммиты и push** — только по просьбе. Без `Co-authored-by` / Cursor trailer. Репы: IMOEX и IMOEX-core **отдельно**, обычно ветка **`dev`**.  
8. **Не смешивать стратегии на desk:** таблица «Сделки сегодня» на позиционной ≠ сделки Exclusive, и наоборот. История — в **Statement** (меню сверху). Не подмешивать `latestClose` / `fp.lastClose` чужого lane.  
9. **Пользовательский UI:** не писать пути `/view/statement#trend` в текст; ссылка словом «Statement» или пункт меню. Не показывать `§14`/`§8` оператору. Кухня HTF=/RANGE:/SANDBOX_FAIR в брифах — фильтровать (`humanizeDeskReason` / `userFacingStory` / `deMark`).  
10. **План на графике (ENTRY/SL/TP, «план BUY/SELL»)** рисуется **только** при `actionable` сетке или открытой сделке. ZONE_READY / «ждём закрытый отбой» **без линий — норма**, не баг разметки.  
11. **Не трогать calendar-arb `live-execution`** и pairs INTRADAY live, если задача — Exclusive observe.  
12. **Product truth:** research / decision-support, не black-box автоторг и не обещание доходности.

### Гейты 2026-08-24 (не трогать без gate)

1. SL за day TOP/BOT (геометрия дневной полки).  
2. Smash-gate на §8 (вынос через дневную полку).  
3. `sl-sweep-buffer-points: 5` — стоп за полкой +5 пт; иначе skip, если стоп > speculative 20.  
4. Dump/melt knife на §8 **только** при deep-poke/smash; чистый break+hold **не режем**.

Journal: `slObserveNote` → notes + `lastClose.slKind` (`SWEEP` / `THROUGH` / `GAP`). Skip-гейты: `data/trend-gate-observe.jsonl` (robot journal пишет в основном actionable).

Крутить **не сейчас**. Когда можно (после phase A + явный research): много SWEEP + мелкий reclaim → buffer↑; много skip при спокойном §8 → buffer↓; dump режет в нож → deep-poke↑.

---

### Exclusive #1 — как робот думает (обязательный контекст)

Playbook: «Уровни + объёмы», только **нефть BR M5**, полки дня TOP/BOT (обычно 15–20 пт). `a-setup-bounce-only: false` — полный чеклист: bounce **и** retest после пробоя.

**Карта дня на графике:** HI/LO дня; **TOP·день** / **BOT·день** — торговые полки (day-lock, TOP не «подтягивается» под каждый новый HI — иначе убивается §8). HIST/ZERO/ACCUM — карта, не вход с середины. `prefer-structural-entries: true`.

**Два сценария входа**

| Сценарий | Когда | Сторона |
|----------|--------|---------|
| Отскок (BOUNCE) | Полка **не** удержана снаружи; касание + **закрытая** свеча-отбой (`require-bounce-confirm: true`) | TOP → SELL, BOT → BUY |
| Ретест после пробоя | Полка **пробита и удержана** (close снаружи), цена **вернулась проверить** с правильной стороны, дистанция ≤ `retest-arm-max-distance-points` (10) | TOP hold above → LONG; BOT hold below → SHORT |

Прокол HI / фитиль над TOP↑ **сам по себе не сигнал**. Пример 01.09 ~15:45: 15:35–15:40 закрылись над 92.65, 15:45 закрылась **внутри** 92.50–92.65 → робот ждал **отбой short**, не покупку пробоя; план на графике пустой — **ожидаемо**. Close обратно внутрь коробки снимает «зависший» BUY RETEST (`cancel stale §8 … close back inside`).

**Dual shelf (доработка 01.09, IMOEX-core `e2fdb9a`):**  
Каждый бар в игре **обе** полки дня. Live bounce / §8 побеждает; иначе ближайшая. **Запрещено** трактовать «несколько баров жили ниже TOP» как unstick и бросать TOP в пользу BOT (это уже ломало шорт от TOP в ралли). `ChecklistStructure.pickActive` в TREND_UP **не прячет** TOP.  
Waiting copy в движке может быть `TOP+BOT in play — focus TREND_HI …`; в UI это переводится человеческим русским.

**Против часа (симметрично, не «запрет стороны»):**  
HTF UP **не запрещает** шорт от TOP; HTF DOWN **не запрещает** лонг от BOT. Против ветра bounce требует **extra gate**: `bounceConfirmed` + touchQ≥3 + (close **вышла из коробки** ИЛИ H1 замедляется ИЛИ 2 close за mid). С ветром / FLAT — обычный §14 confirm. §8 continuation extra-gate **не душит**. Размер против ветра режется (`counter-trend-size-fraction: 0.6`), не сторона.  
Код: `PlaybookIntelligence.allowsCounterTrendShelfBounce` → `allowsRallyDayTopBounce` / `allowsDumpDayBotBounce`.

**Фаза дня:** dump ≥80 пт → приоритет отскока BOT; rally → TOP; иначе continuation по HTF или баланс.

**Gap-fill:** ночной/утренний гэп к prior close. Против заполнения не торгуем, пока гэп открыт; «сделку на закрытие гэпа» без согласия HTF пропускаем. **§8 после пробоя и удержания не блокируется.** В UI не писать `§8`.

**Сессия Exclusive:** основная ~10:20–18:30 (open+20 / close−30). Вечерка 19–23:50 — новых сетапов нет. `max-setups-per-day: 4`, `max-day-loss-rub: 1500`.

**Макро / CL:** smart knife на dump; CL — контекст открытия, **сторону Exclusive не крутит**. UsOilGate может задержать вход после гэпа CL.

**Экспирация FORTS (с 31.08):** last trade day — **новые** сетапы block (M5 и H1). Открытое — SL/TP дальше. Fail-closed, если ISS не знает last trade date. Overnight через roll: бары/SL по **secid позиции**, не новый front (`barsForLiveClockExact`). Front-month: `auto-resolve-instrument: BR` + `FrontMonthBookRoller` / `TrendFrontMonthSettingsSync` — переподписка без рестарта.

**Fair-paper:** SANDBOX_FAIR, `auto-execution: true`, `broker-sim-max: true`. Qty в statement = **filled/planned** (1/3 норма, если залита одна нога).

### Позиционная #2 (если трогаешь desk)

Отдельная вкладка `/view/trend-positional`, H1, другие семьи (BR/RI/NG/Si/GD/MIX) — **ротация одной семьи**, не параллельные гриды. Не дублировать Exclusive-сделки в её «Сделки сегодня». Если сегодня по H1 сделок нет — **скрыть таблицу**, не подставлять M5. `fairPaperLastCloseForPlaybook` **не** отдаёт чужой lane и **не** фолбэчит unscoped `lastClose` в positional.

Пока `parallel-playbooks: false`, fair-paper #2 не должен считаться «включённым роботом». Плашка «Пауза · Позиционная» на range-desk — ок.

### UI / копирайт (доработки 01.09, IMOEX `7fe9a8a`)

- Мета сделок: `Сегодня · 1 сделка · PnL +434 ₽ · Statement` (ссылка на `/view/statement`, **видимый текст — не путь**). Русские плюрали.  
- Фильтр сделок: `playbookFromTrade` **обязан** совпасть с `viewPlaybookId()`; unknown playbook **выбрасывать**. `latestClose` unshift только своего playbook.  
- Бриф: `humanizeDeskReason`, `userFacingStory` (отрезать engine-lead с `§` / TREND_HI / RANGE: BUY). Hover свечей — живой русский, без Exclusive-кухни.  
- Справка «Как торгует робот»: без номеров параграфов («ретест после пробоя»).  
- Кэш JS: `trend-signal-desk.js?v=20260901-desk5` (при правке JS — bump). CSS: `operator.css?v=20260901-hover2`.  
- После правки static — копировать в `trinity-app/target/classes/` если 8080 уже запущен; Java — рестарт `mvn -pl trinity-app -am spring-boot:run`.

### Фазы paper → real

| Фаза | Статус | Что делать |
|------|--------|------------|
| **A** | ✅ 25–28.08 | FORMING_BAR + evening journal, gateHits=0, labeled SL |
| **B** | 📊 metrics ready, **human OOS обязателен** | expectancy >0; оператор ещё не дал go на код FORTS SL |
| **C** | ⛔ blocked | 1 лот FORTS SL в стакане: `TrendExecutionBridge` journal-only; BrokerClient pairs-oriented; T-Invest **sandbox** кидает `SandboxModeViolationException` на PostStopOrder |
| **D** | ⏳ | Scale только если live fills/SL = journal |

Operator 27.08: «go → затем стой, пока на бумаге». Код Phase C **не начинать** без human OOS go.

---

### Ops runbook

**Запуск 8080 (если down):**
```bash
cd /Users/ivan/MEGA/Work/TRINITY/IMOEX
mvn -pl trinity-app -am spring-boot:run
# профиль operator активируется сам, если есть ../IMOEX-core/pom.xml
# при нехватке RAM: -Dspring-boot.run.jvmArguments='-Xms256m -Xmx1536m'
```
Без `-Poperator` на этой машине нормально. Не путать с public bones clone.

**Утро (~09:55 MSK, после sleep/Mac off):**
```bash
bash IMOEX/scripts/trend_observe_resume.sh YYYY-MM-DD
```

**Вечер (~18:02 MSK, сессия закрыта — робот уже не торгует):**
```bash
cd IMOEX && python3 scripts/trend_observe_evening.py [YYYY-MM-DD]
python3 scripts/trend_observe_expectancy.py
```

**Desk poll (skip-гейты в сессию):** `python3 scripts/trend_observe_desk_poll.py`

**Health / Exclusive desk:**
```bash
curl -sf http://127.0.0.1:8080/actuator/health
curl -s 'http://127.0.0.1:8080/api/trend/desk?instrument=BRV6&playbook=levels-profile-br-m5' \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); s=d.get("situation") or {}; print(d.get("engineState"), s.get("posture"), d.get("actionable"), (s.get("why") or d.get("summary") or "")[:240])'
```

**Страницы:**  
`/view` дашборд · `/view/trend-signal` диапазонная · `/view/trend-positional` позиционная · `/view/trend-charts` терминал · `/view/statement` журнал · `/view/settings` пульт.

### Local state (не git)

| Файл | Зачем |
|------|--------|
| `data/trend-observe-path-log.json` | дни, EOD, phase A/B/C, candidates |
| `data/trend-fair-paper-state.json` | open, lastProcessedBar, lanes |
| `data/trend-paper-journal.json` | SANDBOX_FAIR |
| `data/trend-gate-observe.jsonl` | skip hits с desk poll |
| `data/trend-robot-journal.json` | engine actionable |
| `data/trend-ui-settings.json` | instrument/playbook/live с пульта |
| `data/trend-event-calendar.json` | EIA/API blackout |

### Ключевой код

| Область | Где |
|---------|-----|
| Playbook #1 | `IMOEX-core/trinity-trend/.../LevelsProfileBrPlaybook.java` |
| Dual shelf / HTF extra | `PlaybookIntelligence.java`, `ChecklistStructure.java` |
| Side law | `ExclusiveSide.java`, `PositionalSide.java` |
| Compliance | `ChecklistCompliance.java` |
| Engine | `TrendRobotEngine.java` |
| Fair paper | `trinity-runtime/.../TrendFairPaperLiveService.java` |
| Desk API / lastClose lanes | `TrendDeskService.java` |
| Front month | `IMOEX/trinity-marketdata/.../FrontMonthBookRoller.java`, `TrendFrontMonthSettingsSync.java` |
| Expiry | `TrendContractExpiry.java` |
| Markers | `TrendChartMarkers.java` |
| Bridge | `TrendExecutionBridge.java` (journal only) |
| Desk UI | `IMOEX/.../static/js/trend-signal-desk.js`, `trinity-chart-kit.js`, `trend-signal-desk.html` |
| Observe scripts | `IMOEX/scripts/trend_observe_*.py`, `trend_observe_resume.sh` |
| Rules | `IMOEX/.cursor/rules/trinity-roadmap.mdc`, `checklist-fidelity.mdc`, `no-cursor-attribution.mdc` |

Конфиг тренда: `IMOEX/trinity-app/src/main/resources/application.yml` → `imoex.strategies.trend` (`live-execution: false`, `playbook: levels-profile-br-m5`, `parallel-playbooks: false`, `auto-resolve-instrument: BR`).

---

### TRINITY roadmap (контекст; не прыгать вперёд)

Порядок столпов: **(1) Pairs DAILY live paper** → **(2) Trend #1/#2 sandbox** → **(3) Calendar arb sandbox** → дальше отложенное.

**Сейчас по столпам**

1. **Pairs** — DAILY live paper: tech → cluster → FA → paper. INTRADAY research-only. OIL_GAS вне pairs. Чемпион сектора, иначе sit-out.  
2. **Trend** — #1 Exclusive observe (твой торговый scope). #2 positional — desk есть, armed parallel **выкл** из-за OOM.  
3. **Calendar arb** — T-Invest only (не ISS), sandbox fair-paper. **Не** путать yml `calendar-arb.live-execution: true` с trend live — не включать/не «чинить» без задачи на arb.  
4. **Volume desk / clusters / DOM walls** — **не сейчас**. Mid-Sep 2026+; soft EXT у полки, не предиктор цены, не flip стороны. Копить tape/DOM.  
5. **Delivery Instance vs Core** — autumn–winter 2026 (не путать с IP Bones vs `IMOEX-core` — IP уже начат).  
6. **Load ≥1000 + fragility pack** — перед public Instance и перед `liveExecution` trend. EXT vs §8 — контрактные тесты.

**Не делать из бэклога без gate:** кластеры/KD/OI как сигналы; DOM «стены → цена пойдёт сюда»; options; биллинг; updater Core; полный ATAS-desk.

**observeCandidatesLater (только лог, не implement):** cooldown после SL режет BOT bounce; mid-shelf HTF DOWN → SWEEP; positional OOM до `both`; tick-trail SL с входа — нет; trail runner после TP1 Exclusive — **DONE 28.08**.

**Fragility, которую уже чинили и нельзя регрессировать:** day-lock TOP vs HI; HI≈LO infinite loop `ChecklistStructure` (31.08); lastClose leak между lanes; qty filled/planned; wick SL FORMING_BAR vs close M5; два playbook на одном экране.

---

### Что НЕ делать

- ❌ Tuning buffer/knife/smash/pad по одному дню или скрину  
- ❌ `liveExecution=true` / боевой PostStop без Phase C + go  
- ❌ `playbook: both` пока OOM  
- ❌ Смешивать сделки Exclusive и positional на desk; дублировать Statement  
- ❌ Писать `/view/...` в видимый текст; светить `§N` пользователю  
- ❌ Рисовать план BUY/SELL на «пробое глазом», пока нет `actionable`  
- ❌ Unstick TOP из-за баров «под полкой»  
- ❌ Запрещать TOP short только потому что HTF UP (нужен extra-confirm, не veto стороны)  
- ❌ Commit `/data/`, secrets; Co-authored-by  
- ❌ Объявлять observePathToReal выполненным до live FORTS SL + alignment journal  
- ❌ Прыгать в clusters/DOM-walls/Instance delivery «заодно»

### Первый шаг

1. Прочитай `data/trend-observe-path-log.json` (хвост `eod01`, `phaseB_expectancy`, `phaseC_blocker`).  
2. `curl` health + desk BRV6: posture, why, open, live=false.  
3. Если задача — **observe**: утро resume / вечер seal, журнал, **не** код стратегии.  
4. Если задача — **баг UI / «нет плана на пробое»**: сначала сверь last **close** vs TOP box и `actionable`; не чини «разметку» под chase.  
5. Коммит/push — только если оператор сказал; два репо, `dev`.

---

## Краткая версия (лимит контекста)

```
TRINITY /Users/ivan/MEGA/Work/TRINITY = IMOEX (public) + IMOEX-core (private), ветка dev.
Цель: observePathToReal — Exclusive BR M5 fair-paper → human OOS → FORTS SL 1 lot → scale.
Phase A done (25–28.08). 01.09 paper: BRU6 TP2 +434 + BRV6 TP2 +301; OOS 14 trades +2180₽ ~156/trade. live=false, FORMING_BAR, playbook Exclusive-only (positional OOM — не both). Phase C blocked (нет single-leg PostStop; sandbox can't stop).
Front: BRV6. Dual shelf: TOP+BOT каждый бар, без unstick TOP. Против HTF bounce — extra confirm, не запрет стороны. §8 только снаружи коробки; close внутри = bounce. План на графике только при actionable.
Desk: не смешивать сделки плейбуков; не писать /view/ пути и §N в UI. Statement в меню.
Завтра: trend_observe_resume.sh 2026-09-02; evening ~18:02. doNotTuneOnSight. Log: data/trend-observe-path-log.json.
Коммиты только по просьбе, без Co-authored-by, push в соответствующие origin/dev.
Roadmap: pairs DAILY → trend sandbox → arb sandbox; clusters/DOM/Instance — later. Product = research, not black-box.
```
