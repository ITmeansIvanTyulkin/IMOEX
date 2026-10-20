# TRINITY — handoff-промпт для нового агента

> Скопируй блок **«ПРОМПТ»** целиком в новый чат.  
> Не путать с узкими промптами:  
> - `docs/DECISION_FRAME_AGENT_PROMPT.md` — только продукт DF  
> - `docs/OBSERVE_PATH_AGENT_PROMPT.md` — observe/тюнинг Exclusive  
> - `docs/DECISION_FRAME_CONTRACT.md` — контракт кадра (DF-0…DF-6)  
> Этот файл — **сквозной контекст длинного чата** (Exclusive desk + Invest parity + GAP_FILL trail + @Figures).

Репозитории: `IMOEX-core` (логика) + `IMOEX` (app/деск). Ветка: **`dev`**, оба up-to-date с `origin/dev` на момент handoff.

Ключевые SHA (уже запушены):

| Repo | SHA | Суть |
|------|-----|------|
| core | `efd2bd1` | Exclusive: обе полки дня, фокус на nearest |
| core | `d3e0b39` | Day TOP/BOT только сегодня; сброс фитильных полок без тел |
| core | `d75a6b5` | Investments: DF, dual-sleeve long/short, fair-paper, journal |
| core | `efbcf47` | Investments: live-мост T-Invest (не stub), `LIVE_GATED` |
| core | `8f4d33e` | GAP_FILL: H1 candle-trail по ходу заполнения гэпа |
| core | `c269bfe` | `@Figures` soft-skip + corpus A/B (флаг **OFF** по умолчанию) |
| IMOEX | `ebea624` | FE Exclusive dual-shelf / nearest без ложного TOP из «TOP+BOT» |
| IMOEX | `9bbbf32` | Invest desk: Live, DF API/export, brief как у trend |
| IMOEX | `36bb188` | Деск: подпись SL trail для GAP_FILL |

---

## ПРОМПТ

```
Ты продолжаешь работу по TRINITY (MOEX / FORTS / TQBR operator desk + robots).
Рабочая директория: /Users/ivan/MEGA/Work/TRINITY
Два репо: IMOEX-core (Java playbooks/runtime) и IMOEX (Spring app + HTML/JS desks).
Ветка: dev. Коммить/пуш только по явной просьбе; оба репо, если трогал оба.
Язык ответов: русский, коротко, без воды. Операторский desk для знающего — не «витрина для новичков»; это осознанный выбор продукта.

════════════════════════════════════════════════════════
0. ПРОДУКТОВАЯ ПОЗИЦИЯ (не ломать)
════════════════════════════════════════════════════════
• Позиционирование: «операторский робот с памятью решений и железом правил», НЕ «ИИ анализирует рынок».
• Платить будут за: закрытый контур (robot+исполнение+журнал+paper/live), доказуемый track record, экономию внимания, доверие к риску.
• Track record = проверяемая цепочка «кадр → сделка → исход» за месяцы с цифрами. Код инфраструктуры уже есть — его нужно НАПОЛНЯТЬ месяцами авто/paper без дыр, не изобретать заново.
• Paper и live — разные книги (SANDBOX_FAIR / LIVE_*). Statement hub и API уже показывают два ряда + research отдельно.
  Не чистить statement задним числом; не смешивать книги в metrics.
• Arb (calendar) пока слабее Exclusive/Invest по UX кадров — не тащи arb «заодно», если задача не про него.
• Не смешивать в разборе дня строки Exclusive (BOUNCE/RETEST M5) с positional / GAP_FILL H1.

════════════════════════════════════════════════════════
1. РЕПО / МОДУЛИ / ДЕСКИ
════════════════════════════════════════════════════════
IMOEX-core:
  trinity-trend/     — Exclusive, BRM, positional, FairPaperSimulator, DecisionFrame*, Figures*
  trinity-investments/ — Investments* playbook/DF/gates
  trinity-runtime/   — TrendFairPaperLiveService, TrendDeskService, InvestmentsDeskService,
                       *PaperJournal*, *ExecutionBridge*, DecisionFrameJournalService
IMOEX:
  trinity-app/.../trend-signal-desk.{html,js}     — Exclusive / BRM / positional
  trinity-app/.../investments-desk.*             — Invest
  trinity-app/.../static/js/trinity-decision-frame.js
  docs/DECISION_FRAME_CONTRACT.md                — источник правды по кадру
  research-corpus/positional-h1/                 — offline corpus для реплеев
  data/*.jsonl / decision-frame/                 — НЕ в git; .megaignore режет налог версий MEGA

Playbooks (не путать):
  levels-profile-br-m5     — Exclusive range BR M5 (BOUNCE/RETEST)
  retail-brm-m5            — BRM retail M5 (отдельный corpus lane)
  positional-volume-h1     — positional H1 + GAP_FILL
  investments              — TQBR ladder / checklist (lane=invest, D1)

Mutex M5: когда один рукав parked — sibling всё равно пишет corpus-shadow
(TrendFairPaperLiveService EXCLUSIVE_CORPUS_SHADOW_KEY / BRM_CORPUS_SHADOW_KEY) —
без arms/fills/journal trading. Не ломать.

════════════════════════════════════════════════════════
2. DECISION FRAME (DF) — ОБЩИЕ ПРАВИЛА
════════════════════════════════════════════════════════
Контракт: IMOEX/docs/DECISION_FRAME_CONTRACT.md (DF-0…DF-6 уже на dev).

• Кадр = один объект «почему сейчас»: summary + robot{ENTER|WAIT|SKIP} + details[] + marketSnap.
• lane: range | brm | positional | invest. На деске виден только свой lane.
• Опрос деска без нового триггера кадр не плодит.
• Тексты оператору — грамотный русский. В UI НЕ попадают §, /view/, сырые checklist id.
• details[]: один факт = одна строка; числа только из value/полей деска, не выдумывать.
• Lead/lag и sync — КОНТЕКСТ, не veto. Запрет входа только явным правилом playbook → source=robot.
• Клик по факту журнала → подсветка полки/POC/стакана из marketSnap КАДРА, не live-картинки.
• Журнал: data/decision-frame/YYYY-MM-DD.jsonl; группировка единая на всех strategic desks;
  карточка «Сегодня: …»; фильтры ждёт/вошёл/пропуск/капитал.
• ENTER + лимитки в чтении/фильтрах = WAIT («ждёт»), не «в сделке», пока нет позиции.
• DF-6 капитал: дефолт TREND_M5; PAIRS только legacy/явно заданное; capital tick изолирован
  от decisionFrame (ошибка allocator не должна затирать кадр).
• API parity:
    GET /api/trend/decision-frames[ /export ]
    GET /api/investments/decision-frames[ /export ]  (playbook default investments)
• Invest desk: decisionFrame (фокус) + decisionFrames (вселенная за день) + operatorBrief «что делать».

При DF-правках: править assembler + journal + FE trinity-decision-frame.js согласованно;
cache-bust query у desk JS при FE-изменениях.

════════════════════════════════════════════════════════
3. EXCLUSIVE — DUAL-SHELF / DAY TOP·BOT / STUCK
════════════════════════════════════════════════════════
Сделано и зафиксировано правилами:

• Мониторим ОБЕ полки дня (TOP и BOT). Фокус = ближайшая к цене (nearest/overlap),
  не «залипший BOT». Rationale вида «TOP+BOT monitor — nearest …».
• FE: не матчить слово TOP внутри строки «TOP+BOT» — роль брать из NEAREST/FOCUS …
  (trend-signal-desk.js). Подписи: «Мониторим обе полки… Ближе верхняя/нижняя…».
• Day TOP/BOT = профиль ТОЛЬКО за сегодня (DayZoneLock). Фитильные полки без тел свечей
  (ghost VAP со вчера) — сбрасывать.
• Side laws (не ломать): BOT=BUY bounce, TOP=SELL bounce.
  RETEST long только пока close ≥ TOP↑; short только пока close ≤ BOT↓.
  Close внутри коробки = bounce. Gate: ExclusiveSide.
  HTF / CL / макро / one-setup НЕ переворачивают сторону.
• Checklist fidelity: при конфликте EXT vs чеклист побеждает чеклист.
• Hunt (ExclusiveHuntExt): кит/толпа/спуф пишутся в UI; жёсткий hard skip — узкий
  эксперимент OCT2026_NARROW_MICRO_VETO; не расширять без явного go оператора.
• Stuck/залипание: автосброс soft ~20 мин, hard реже; «цена далеко» ≠ stuck;
  UI статуса залипания с деска убран (тихий автосброс, без кнопки и шума).
• Voided paper ids (не показывать в track): в т.ч. BRX6-2026-10-06T06-00 (GAP_FILL stub),
  BRX6-2026-10-09T16-45 (SELL TOP vs HTF UP) — смотри VOID списки в TrendPaperJournal /
  TrendFairPaperLiveService / FE filter.

════════════════════════════════════════════════════════
4. POSITIONAL / GAP_FILL — ВЫХОДЫ И ТРЕЙЛ (критично)
════════════════════════════════════════════════════════
Файлы: FairPaperSimulator, PositionalH1GapStrategy, SessionGapFill,
       PositionalVolumeH1Playbook, FE trend-signal-desk.js (подписи trail).

Отличия от Exclusive после TP1:
• Exclusive после TP1 → SL = exact BE → выход BE_STOP.
• GAP_FILL после TP1 → structural/anchored SL ОСТАЁТСЯ (не exact BE).
  Если structural нет — BE с буфером GAP_FILL_BE_BUFFER_POINTS = 8 пунктов
  (чтобы шум H1 open не стопнул тик через avg).

GAP_FILL candle-trail (сделано, 8f4d33e) — «не отдавать заполненный ход старому adverse SL»:
• Константы: TRAIL_PAD=8 pts, ARM_FRAC=0.35 пути к priorClose, ARM_POINTS floor=15.
• Arm: после TP1 ИЛИ когда MFE ≥ max(15pts, 35% |tp2−avg|).
• Trail только за ЗАКРЫТОЙ H1 «с нами» (long: close≥open; short: close≤open),
  pad за low/high, schedule pending → apply СО СЛЕДУЮЩЕГО бара
  (свеча трейла своим фитилём нас не стопает). Only ratchet, never loosen.
• stopReason: TRAIL если candleTrail, иначе SL.
• FE: для GAP_FILL не форсить Exclusive-BE на клиенте — SL с сервера (structural → trail).

Операторский нюанс (уже объясняли):
«TP1 + свеча вниз ≠ гарантия без убытка». После TP1 откат РЕЖЕТСЯ трейлом,
но пока trail ниже avg — хвост ещё может закрыться в минус (меньший, чем старый SL).
«Без минуса» только когда stop уже ≥ avg (long).

Прочие GAP_FILL гейты (не ослаблять без данных):
• Book against planned side → no new arm (и bounce, и GAP_FILL).
• До 10:00 часто нужен live DOM; medium/large gap — hard micro confirm.
• Open GAP_FILL не с «раннего» stub 06:00 — см. void/history.

Corpus/replay без ISS: research-corpus/positional-h1 + offline replays
(PositionalH1PnlReplay, PositionalH1FiguresCorpusAbReplay). ISS таймауты = норма;
для A/B предпочитать offline corpus.

════════════════════════════════════════════════════════
5. @Figures — ЭКСПЕРИМЕНТ (OFF по умолчанию)
════════════════════════════════════════════════════════
Запрос оператора: regular MACD/RSI div как слой 1–2 DF для positional/GAP_FILL —
контекст / мягкий skip, БЕЗ смены геометрического SL/TP. Tear-out тег @Figures.

Код: Figures, FiguresFlags (-Dtrinity.figures=true / setEnabled),
     RegularOscillatorDivergence, FiguresSoftSkip,
     хуки в PositionalVolumeH1Playbook + DecisionFrameAssembler.
Grep @Figures / Figures чтобы снести ветку целиком при провале.

Поведение при enabled=true:
• Soft-skip BUY при regular bearish MACD/RSI; SELL при regular bullish.
• Геометрия сетки не меняется — только блок arm → NO_TRADE с reason.
• В DF — строка контекста (@Figures: …), не veto-язык вне robot.

A/B авг–окт 2026 (corpus offline):
  baseline ΣR ≈ +55.23 → figures ≈ +55.48 (Δ +0.25 R), softSkips ≈ 9.
  Вердикт: НЕЙТРАЛЬНО → код ОСТАВИТЬ под тегом, флаг DEFAULT OFF.
Не включать в прод без нового явного A/B и go оператора.
Не тащить @Figures в Exclusive/BRM «заодно».

════════════════════════════════════════════════════════
6. INVESTMENTS — PARITY С TREND (сделано, держать уровень)
════════════════════════════════════════════════════════
Цель оператора: Invest не «сырой третий продукт», а тот же уровень DF/paper/live, что trend.

Сделано:
• InvestmentsDecisionFrameAssembler + desk decisionFrame/decisionFrames + RU why/brief.
• Universe = InvestmentsUniverse.DEFAULT_TICKERS (~27); журнал по всем тикерам скана, не ROSN-only.
• Dual-sleeve long/short (pickBetter) — шорт не только в BEAR; FormalPotential short ок.
• Fair-paper: place → fill → ExitPolicy close; PaperState.side; статус связан с кадром.
• Live: InvestmentsExecutionBridge зеркалит TrendExecutionBridge
  (LIVE_GATED / LIVE_SUBMITTED / LIVE_PARTIAL) — не stub.
• Settings vocabulary: OBSERVE | SANDBOX_FAIR | LIVE_GATED как у trend.
• Auto без live = paper-лестницы; Auto+live = брокер или LIVE_GATED в журнал, если не armed.
• API paper/statement/metrics parity с /api/trend/paper.
• robot.mode invest: PLACE_LIMITS | WORKING_ORDERS | IN_TRADE | NONE
  (НЕ bounce/retest Exclusive).
• marketSnap invest: range1/range2, entryLimit, target, stop, verdict, score.

Не делать:
• Не подменять календарный арбитраж месяцев; не смешивать с AO/AP.
• FE source labels «полка/профиль» не путать с invest-чеклистом (всегда robot stages).

════════════════════════════════════════════════════════
7. ИСПОЛНЕНИЕ / КАНАЛЫ
════════════════════════════════════════════════════════
• liveExecution=true → fair-paper loop НЕ торгует paper (paperActive=false).
• Sandbox broker stop: dry-run или T-Invest sandbox — never live empty account.
• Каналы писать явно в journal/bridge snapshot.
• Research-corpus lanes раздельные (retail-brm-m5 ≠ Exclusive ≠ positional-h1 ≠ investments),
  чтобы обучение не смешивалось.

════════════════════════════════════════════════════════
8. UX / FE ПРАВИЛА ЭТОГО ЧАТА
════════════════════════════════════════════════════════
• Desk для знающего: плотность, жаргон, график+DOM — ОК. Не упрощать под «витрину».
• Единый уровень кадров по стратегиям (Exclusive ≈ Invest); arb — отдельно, когда попросят.
• Первый экран «что делать сейчас» = operatorBrief / DF summary, не эссе.
• Русские подписи DF; полуанглийские факты вычищать.
• Positional desk: не сбрасывать выбор SI/GD/RI ошибочным фильтром «только BR».
• Cache-bust desk JS при FE-правках.
• Не синкать дописываемые JSONL в MEGA (.megaignore).

════════════════════════════════════════════════════════
9. ТЕСТЫ / РЕПЛЕИ / КАК ПРОВЕРЯТЬ
════════════════════════════════════════════════════════
• Юниты: FairPaperSimulatorTest (GAP_FILL trail/BE buffer), Figures*, DecisionFrame*Test,
  InvestmentsDecisionFrameAssemblerTest, InvestmentsExecutionBridgeTest,
  PlaybookIntelligenceTest (dual-shelf nearest/overlap), DayZoneLock*.
• Offline A/B: PositionalH1FiguresCorpusAbReplay
  args: <corpusDir> 2026-08-01 2026-10-16
  corpus: IMOEX/research-corpus/positional-h1
• Не ждать ISS в интерактиве — таймауты уже жгли чат; для истории использовать corpus/journal.

════════════════════════════════════════════════════════
10. РАБОЧИЙ СТИЛЬ С ОПЕРАТОРОМ
════════════════════════════════════════════════════════
• «сделай сам» / «субагент завис» → делать в основном агенте, без ожидания субагента.
• Коротко по делу; оценки/вердикты — честно, без сахарной обёртки.
• Коммит: оба репо при необходимости; сообщения в стиле существующих (русский, «почему»).
• Не трогать calendar-arb/pairs live без явной задачи.
• Эксперименты — за флагом/тегом с tear-out путём (@Experiment / @Figures).
• При сомнении в правиле стороны/чеклиста — смотри ExclusiveSide / ChecklistCompliance /
  OBSERVE_PATH_AGENT_PROMPT.md, не «улучшай» геометрию на глаз.

════════════════════════════════════════════════════════
11. ТЕКУЩЕЕ СОСТОЯНИЕ НА HANDOFF
════════════════════════════════════════════════════════
• Working trees чистые, origin/dev синхронен.
• @Figures в коде, default OFF, A/B нейтральный — не сносить и не включать без запроса.
• GAP_FILL H1 trail в проде paper-пути — наблюдать живые closes; не откатывать без данных.
• Invest на уровне trend DF/live — держать parity при любых новых DF-фичах.
• Track record контур закрыт по коду: paper/live/research в API + две книги на /view/statement и PDF;
  Exclusive/positional/Invest paper auto on — дальше только копить месяцы. Arb DF — по явному запросу.

Начни с git status обоих репо и чтения релевантного контракта/файла под задачу пользователя.
Не пересказывай этот промпт обратно — просто работай по нему.
```

---

## Быстрые якоря в коде

| Тема | Где смотреть |
|------|----------------|
| DF контракт | `IMOEX/docs/DECISION_FRAME_CONTRACT.md` |
| DF assemble trend | `DecisionFrameAssembler.java` |
| DF assemble invest | `InvestmentsDecisionFrameAssembler.java` |
| GAP_FILL trail | `FairPaperSimulator.applyGapFillCandleTrail` / `applyStopAfterTp1` |
| Dual shelf | `PlaybookIntelligence.chooseLiveDayShelf` / FE `trend-signal-desk.js` «TOP+BOT» |
| Figures | `FiguresFlags`, `FiguresSoftSkip`, `RegularOscillatorDivergence` |
| Invest live | `InvestmentsExecutionBridge.java` |
| Corpus shadow | `TrendFairPaperLiveService` (`:corpus-shadow`) |
| Observe Exclusive | `IMOEX/docs/OBSERVE_PATH_AGENT_PROMPT.md` |
