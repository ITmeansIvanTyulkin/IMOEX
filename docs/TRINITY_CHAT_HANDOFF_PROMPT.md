# TRINITY — handoff-промпт для нового агента

> Скопируй блок **«ПРОМПТ»** целиком в новый чат.  
> Узкие промпты (не заменяют этот):  
> - `docs/DECISION_FRAME_AGENT_PROMPT.md` — продукт DF  
> - `docs/OBSERVE_PATH_AGENT_PROMPT.md` — observe/тюнинг Exclusive  
> - `docs/DECISION_FRAME_CONTRACT.md` — контракт кадра (DF-0…DF-6)  
> - `docs/CALENDAR_ARB_AGENT_PROMPT.md` — calendar arb  
> Этот файл — **сквозной контекст** (Exclusive + soft lessons + gap/BOUNCE + desk UX + Invest parity).

Репозитории: `IMOEX-core` (логика) + `IMOEX` (app/деск). Ветка: **`dev`**.

### Состояние git на handoff (2026-10-26 ~15:20 MSK)

| Repo | Working tree | vs `origin/dev` | Ключевые локальные SHA (могут быть ещё не запушены) |
|------|--------------|-----------------|------------------------------------------------------|
| **IMOEX-core** | clean | **ahead 3** | `5e56490` BOUNCE≠against-fill gap · `1a90c0d` SUCCESS+CAUTION soft · `69ab115` auto-append WIN+LOSS lessons |
| **IMOEX** | clean | **ahead 1** | `9734ec7` chart zoom: не травить scale без userScaleIntent |

На handoff **незакоммиченных правок нет**. Если оператор просит «закоммить всё» — сначала `git status` обоих; если ahead без push — спрашивать отдельно про `git push` (не пушить без явной просьбы).

---

## ПРОМПТ

```
Ты продолжаешь работу по TRINITY (MOEX / FORTS / TQBR operator desk + robots).
Рабочая директория: /Users/ivan/MEGA/Work/TRINITY
Два репо: IMOEX-core (Java playbooks/runtime) и IMOEX (Spring app + HTML/JS desks).
Ветка: dev. Коммить/пуш только по явной просьбе; оба репо, если трогал оба.
Язык ответов: русский, коротко, без воды. Desk для знающего оператора — не витрина для новичков.

Предыдущий чат (эстафета): soft lessons / gap BOUNCE / desk zoom / диагностика «ждём SELL» vs IN_TRADE /
разбор +628 BUY при soft-полках на графике. Transcript id (Cursor): 8bb53351-fe3b-4af6-898d-bfb5a3e8f1a7
(agent-transcripts в .cursor/projects/... — цитировать как [title](uuid без .jsonl)).

════════════════════════════════════════════════════════
0. ПРОДУКТОВАЯ ПОЗИЦИЯ (не ломать)
════════════════════════════════════════════════════════
• Позиционирование: «операторский робот с памятью решений и железом правил», НЕ «ИИ анализирует рынок».
• Платить будут за: закрытый контур (robot+исполнение+журнал+paper/live), доказуемый track record,
  экономию внимания, доверие к риску.
• Track record = цепочка «кадр → сделка → исход» месяцами. Код есть — наполнять paper/auto без дыр.
• Paper и live — разные книги (SANDBOX_FAIR / LIVE_*). Statement hub: paper · live · research.
  Не чистить statement задним числом; не смешивать книги в metrics.
• Не смешивать в разборе дня Exclusive (BOUNCE/RETEST M5) с positional / GAP_FILL H1.
• Arb (calendar) слабее по UX кадров — не тащить «заодно».

════════════════════════════════════════════════════════
1. РЕПО / МОДУЛИ / ДЕСКИ
════════════════════════════════════════════════════════
IMOEX-core:
  trinity-trend/     — Exclusive, BRM, positional, FairPaperSimulator, DecisionFrame*,
                       ExclusiveSoftStackMemory, SoftLessonBookAppender, SessionGapFill,
                       ExclusiveHuntExt, ExclusiveSide, DayZoneLock, LevelsProfileBrPlaybook
  trinity-investments/ — Investments* + InvestmentsSoftLessonMemory
  trinity-runtime/   — TrendFairPaperLiveService, TrendDeskService, *PaperJournal*,
                       SoftLessonBookAppender hooks on close, ExecutionBridge*
IMOEX:
  trinity-app/.../trend-signal-desk.{html,js}  — Exclusive / BRM / positional
  trinity-app/.../investments-desk.*
  trinity-app/.../static/js/trinity-decision-frame.js
  docs/DECISION_FRAME_CONTRACT.md
  research-corpus/exclusive-m5/events-YYYY-MM-DD.jsonl
  research-corpus/positional-h1/
  data/ — НЕ в git; .megaignore режет налог версий MEGA
    trend-paper-journal.json, trend-fair-paper-state.json (pending НЕ персистится — только open/lastClose),
    trend-paper-voided-archive.json, positional-h1-lessons.json, investments-lessons.json,
    trend-desk-poll-*.log, trend-jvm-watch-*.log

Playbooks (не путать id):
  levels-profile-br-m5  — Exclusive range BR M5 (BOUNCE/RETEST)
  retail-brm-m5         — BRM (отдельный corpus; часто corpus-shadow)
  positional-volume-h1  — positional H1 + GAP_FILL
  investments           — TQBR (lane=invest, D1)

Mutex M5: parked sleeve → sibling пишет corpus-shadow (без arms/fills/journal). Не ломать.

Запуск: обычно `mvn -pl trinity-app -am spring-boot:run` из IMOEX, profile=dev, порт 8080.
Auth desk API: Basic из application-local.yml / IMOEX_AUTH_*; scripts/trend_observe_auth.py → desk_request.

════════════════════════════════════════════════════════
2. DECISION FRAME (DF)
════════════════════════════════════════════════════════
Контракт: IMOEX/docs/DECISION_FRAME_CONTRACT.md (DF-0…DF-6).

• Кадр = summary + robot{ENTER|WAIT|SKIP} + details[] + marketSnap; lane: range|brm|positional|invest.
• Опрос деска без нового триггера кадр не плодит.
• UI: грамотный русский; без §, /view/, сырых checklist id.
• ENTER + лимитки в фильтрах = WAIT («ждёт»), пока нет позиции (inTrade / lane open).
• API: GET /api/trend/decision-frames[/export], GET /api/investments/decision-frames[/export].
• DF/fair-paper sync: corpus PLAN_* пишется из fair-paper evaluate; не путать desk poll NO_TRADE
  (hunt veto на опросе) с фактическим pending/open в TrendFairPaperLiveService.

════════════════════════════════════════════════════════
3. EXCLUSIVE — ПОЛКИ / SIDE / HUNT / SOFT vs HARD
════════════════════════════════════════════════════════
Day TOP/BOT (DayZoneLock):
• Мониторим ОБЕ полки; фокус = nearest к цене. Rationale: «TOP+BOT monitor — nearest …».
• Day lock = профиль ЗА СЕГОДНЯ. Soft/PRIOR не замораживаются как tradable lock
  (LevelsProfileBrPlaybook.lockableShelf / isSoftSource — SOFT → null).
• Desk может РИСОВАТЬ soft-зоны «TOP/BOT·ждём объём» (geometricSoftZone / DESK§5+SOFT) —
  это ОРИЕНТИР, не вход. FE: isSoftZone → подпись «·ждём объём» / «ещё не полка».
• Arm только с валидной полкой (TAPE/BARS/…+LIVE), validForEntry=true.
  Пример 26.10 +628 BUY: вход по TREND_LO BARS§6+LIVE [113.96–114.15], не по soft на скрине после.
  После филла часто «day BOT shelf cleared» → на графике снова soft «ждём объём» — это нормально.

Side laws (ExclusiveSide — не ломать):
• BOT = BUY bounce; TOP = SELL bounce.
• RETEST long только close ≥ TOP↑; short только close ≤ BOT↓; close в коробке = bounce.
• HTF / CL / макро / ONE_SETUP НЕ переворачивают сторону.

ONE_SETUP lock (TrendRobotEngine):
• При actionable ARMED evaluate ставит SetupLock → дальше withHoldRationale → state WORKING_ORDERS.
• Unlock ≥ unlockDistancePoints (default 40) от mid или new day / smash-through against.
• Fair-paper: pending в памяти; на диск pending НЕ пишется (LanePersisted = lastProcessed+open+lastClose).
• Если hunt.blocksNewArm() на SKIP ARM — сейчас clearSetupLock НЕ всегда вызывается в той же ветке
  (известный риск «фантомного ждём SELL»): engine WORKING_ORDERS + actionable plan, а lane.pending=null
  и open=null. Desk posture WAITING_FILL может рисовать «ждём SELL» без реальных лимитов.
  При фиксе: SKIP ARM → eng.clearSetupLock(); при fairPaperOn workingOrders считать по lanePending,
  не по голому eng WORKING_ORDERS. НЕ чинить «заодно» без воспроизведения — сначала проверить live snapshot.

Fill rules (FairPaperSimulator):
• Limit fill когда bar.low…high торгует уровень сетки.
• §11/§16 smash-through against (BOUNCE/RETEST, tp1Fraction>0) → tryOpen возвращает null / unlock.
• SELL smash: open < range.low OR (open < mid && close > high). Не путать «цена ниже entry avg»
  с «уже в сделке» — нужен pending + касание сетки без smash.

Hunt (ExclusiveHuntExt):
• Soft size×0.5 (кит/спуф/импульс) vs hard blocksNewArm.
• OCT2026_NARROW_MICRO_VETO — узкий эксперимент (ракета в ленте и т.п.); не расширять без go.
• Пример 23.10: 15:10 ARMED_BOUNCE SELL → 15:15 rocket veto → позже soft кит → 15:25 FILL
  SELL×2 @114.74 (сетка near/mid/far; far 114.85 мог не набрать) → BE_STOP +150.5 @15:35.

════════════════════════════════════════════════════════
4. SESSION GAP / BOUNCE (критичные коммиты этой эстафеты)
════════════════════════════════════════════════════════
Проблема оператора: утренний гэп «against fill» / HTF gap-skip резал качественные дни и BOT BUY.
Сделано (core):
• `5e56490` — Exclusive BOUNCE от полки НЕ режется against-fill по утреннему гэпу
  (BOUNCE проходит как RETEST full pass в SessionGapFill / TrendRobotEngine gap gate).
• `24f92c3` (раньше) — BOUNCE не режет gap HTF-skip; process-уроки в soft memory.
Не ослаблять GAP_FILL H1 гейты «заодно» с Exclusive M5 BOUNCE — разные миры.

Positional GAP_FILL (напоминание, не откатывать):
• После TP1 — НЕ exact BE; structural SL + candle-trail (FairPaperSimulator GAP_FILL_*).
• Book against / micro confirm до 10:00 — держать.

════════════════════════════════════════════════════════
5. SOFT LESSONS / ПАМЯТЬ СТРАТЕГИЙ (эстафета)
════════════════════════════════════════════════════════
Цель: у каждой стратегии своя книга уроков; SUCCESS + CAUTION; auto на каждый close.

Код:
• SoftLessonBookAppender (+ Test) — общий append WIN+LOSS в strategy lesson archives.
• ExclusiveSoftStackMemory — SUCCESS + тонкий LOSS + HTF flag; preferArm / process / LOSS.
• LevelsProfileBrPlaybook / PositionalVolumeH1Playbook / InvestmentsSoftLessonMemory+Playbook.
• Хуки close: TrendFairPaperLiveService + InvestmentsPaperJournalService → auto-append.
• Коммиты: `1a90c0d` SUCCESS+CAUTION all strategies; `69ab115` каждый close → soft-книга.

Данные (локально, не git):
• Exclusive уроки часто в trend-paper-voided-archive / notes LESSON + softMatch в enterContext.
• positional-h1-lessons.json, investments-lessons.json.
• При разборе сделки смотри contextAtEnter (delta/DOM/htf/softMatch/softProcess).

Уроки, которые уже клали руками в чате (не дублировать без нужды):
• 22.10 process Exclusive BOUNCE≠gapfill HTF.
• 23.10 missed BOT preferArm (gap against до фикса BOUNCE pass).
• 23.10 TOP SELL −189 LOSS (THROUGH; counter-trend / DOM / context).
• Auto теперь должен сам дописывать последующие WIN/LOSS.

════════════════════════════════════════════════════════
6. DESK UX (эстафета + правила)
════════════════════════════════════════════════════════
• `9734ec7` — chart zoom: не восстанавливать poisoned scale на refresh без userScaleIntent
  (оператор жаловался на «улетающий» зум после обновления range desk).
• Оверлеи входа: «В СДЕЛКЕ SIDE×qty» если tradeOpen.avg/qty; иначе «ждём SIDE» если plan.actionable.
  Источник open: fairPaper.lanes[playbookId].open (parallel=true → top-level pending часто null).
• resolveChartTradeOpen / deskOpenTrade / fairPaperLaneOpen — при IN_TRADE брать lane open.
• Soft zone paint ≠ tradable. Не учить оператора «soft = можно входить».
• Cache-bust desk JS query при FE-правках.
• Не синкать JSONL в MEGA.

════════════════════════════════════════════════════════
7. INVESTMENTS / STATEMENT / FIGURES (держать)
════════════════════════════════════════════════════════
• Invest DF/paper/live parity с trend — не ронять при новых фичах.
• Statement: books paper·live·research.
• @Figures: код есть, DEFAULT OFF, A/B нейтральный — не включать/не сносить без запроса.
  Не тащить @Figures в Exclusive/BRM.

════════════════════════════════════════════════════════
8. СВЕЖИЙ TRACK (BRX6 Exclusive paper, для якоря)
════════════════════════════════════════════════════════
• 20.10 BUY RETEST −306 SL (LESSON §8)
• 22.10 positional GAP_FILL SELL −481 SL THROUGH (desync DF vs closed H1 — в notes)
• 23.10 SELL BOUNCE −189 SL THROUGH @13:45–13:50
• 23.10 SELL BOUNCE +150.5 BE_STOP @15:25–15:35 (после rocket veto днём; частичный grid)
• 26.10 BUY BOUNCE +628 TP2 @11:50–11:55 по BARS§6+LIVE BOT (не soft «ждём объём» на пост-скрине)

Live check: python scripts + trend_observe_auth.desk_request(DESK_URL) → data.fairPaper.lanes,
  data.situation.{posture,inTrade,waitingFill}, data.plan.

════════════════════════════════════════════════════════
9. ИСПОЛНЕНИЕ / FAIR-PAPER
════════════════════════════════════════════════════════
• liveExecution=true → fair-paper loop не торгует paper.
• fillMode FORMING_BAR — SL/TP по ходу бара; Exclusive fill тоже может на forming.
• Parallel lanes: Exclusive + positional auto; BRM часто shadow.
• Journal append-only; voided ids фильтровать (TrendPaperJournal / FE).

════════════════════════════════════════════════════════
10. ТЕСТЫ / КАК ПРОВЕРЯТЬ
════════════════════════════════════════════════════════
• SoftLessonBookAppenderTest; ExclusiveSoftStackMemory*; SessionGapFillTest (BOUNCE against→allow);
  FairPaperSimulatorTest; LevelsProfileBrPlaybookTest (smash/soft); OneSetupPerZoneTest.
• Corpus: research-corpus/exclusive-m5/events-YYYY-MM-DD.jsonl (PLAN_PROPOSED/REJECTED/ARM/FILL).
• Не ждать ISS в интерактиве для истории — corpus/journal.

════════════════════════════════════════════════════════
11. РАБОЧИЙ СТИЛЬ
════════════════════════════════════════════════════════
• Коротко; вердикт первым предложением. Русский.
• «сделай сам» → в основном агенте, без ожидания субагента.
• Коммит: стиль существующих сообщений (русский, «почему»); HEREDOC; оба репо если надо.
• Не пушить без явной просьбы. Не --force на main/master. Не --no-verify.
• Эксперименты — флаг/тег с tear-out. Не «улучшать» геометрию полок на глаз против ExclusiveSide/
  ChecklistCompliance / OBSERVE_PATH_AGENT_PROMPT.md.
• При «почему не вошёл / почему ждём» — ВСЕГДА сверить: lane.open, lane.pending, engineState,
  plan.state, hunt blocks vs soft, ONE_SETUP lock, corpus barTime того же штампа.

════════════════════════════════════════════════════════
12. ОТКРЫТЫЕ ХВОСТЫ (не обязательный backlog — по запросу оператора)
════════════════════════════════════════════════════════
• Фантом WAITING_FILL / «ждём SELL» при WORKING_ORDERS без pending (см. §3) — воспроизвести и починить.
• Pending не персистится → после рестарта JVM orphan lock возможен; reconcile lock↔pending.
• После SKIP ARM из-за rocket — убедиться что clearSetupLock + desk не врёт «лимитки выставлены».
• Push origin/dev для ahead commits (core×3, IMOEX×1) — только если оператор скажет.
• Наблюдать live: BOUNCE больше не режется утренним against-gap; soft-книги копятся на close.

Начни с: git status -sb в IMOEX-core и IMOEX; при задаче — читай контракт/файл, не пересказывай промпт.
Не пересказывай этот промпт обратно — работай по нему.
```

---

## Быстрые якоря в коде

| Тема | Где смотреть |
|------|----------------|
| DF контракт | `IMOEX/docs/DECISION_FRAME_CONTRACT.md` |
| Soft lessons append | `SoftLessonBookAppender.java`, hooks в `TrendFairPaperLiveService` / `InvestmentsPaperJournalService` |
| Exclusive soft memory | `ExclusiveSoftStackMemory.java` |
| Gap vs BOUNCE | `SessionGapFill.java`, `TrendRobotEngine.applyFilters` gapAgainstFillBlock |
| Soft vs hard shelf | `LevelsProfileBrPlaybook.isSoftSource` / `lockableShelf` / `geometricSoftZone` |
| ONE_SETUP / WORKING | `TrendRobotEngine.withHoldRationale`, `SetupLock` |
| Fill / smash | `FairPaperSimulator.tryOpen` / `fillSmashThroughAgainst` |
| Hunt micro-veto | `ExclusiveHuntExt`, OCT2026_NARROW_MICRO_VETO |
| Desk overlays | `trend-signal-desk.js` — `applyOverlays`, `isSoftZone`, `resolveChartTradeOpen` |
| Chart zoom poison | `userScaleIntent` в `trend-signal-desk.js` (коммит `9734ec7`) |
| Observe Exclusive | `IMOEX/docs/OBSERVE_PATH_AGENT_PROMPT.md` |
| Auth desk poll | `IMOEX/scripts/trend_observe_auth.py` |
