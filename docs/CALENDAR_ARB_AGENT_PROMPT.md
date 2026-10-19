# TRINITY — handoff-промпт: только Calendar Arb

> Скопируй блок **«ПРОМПТ»** целиком в новый чат.  
> Не путать со сквозным `docs/TRINITY_CHAT_HANDOFF_PROMPT.md` и узкими DF/Exclusive промптами.  
> Этот файл — **только арбитраж календаря FORTS** (near/next ± fly). Exclusive / Invest / BRM / positional **не трогать**, кроме чтения для сравнения эталона.

Репозитории: `IMOEX-core` + `IMOEX`. Ветка: **`dev`**.  
Коммит/пуш — только по явной просьбе; оба репо, если трогал оба.  
Язык ответов: русский, коротко, без воды. Операторский desk — не «витрина для новичков».

После работы агента: оператор **вернётся в чат с основным агентом** и вместе проверит результат. Не объявлять «готово к live» без этой проверки.

---

## ПРОМПТ

```
Ты занимаешься ИСКЛЮЧИТЕЛЬНО calendar-arb TRINITY (MOEX FORTS near/next ± fly).
Рабочая директория: /Users/ivan/MEGA/Work/TRINITY
Репо: IMOEX-core (trinity-calendar-arb + CalendarArb* в trinity-runtime)
      IMOEX (calendar-arb-desk.*, CalendarArbController, application.yml → imoex.strategies.calendar-arb)
Ветка: dev. Коммить/пуш только по явной просьбе.
Язык: русский, коротко. Не пересказывай этот промпт — работай по нему.

Не трогай без явной просьбы: Exclusive, BRM, positional, Investments, pairs live,
@Figures, GAP_FILL trail, capital allocator (кроме чтения peek arb facts).

════════════════════════════════════════════════════════
0. ПРОДУКТОВАЯ ПОЗИЦИЯ (критично — не ломать смысл)
════════════════════════════════════════════════════════
• Arb сейчас СЛАБЕЕ Exclusive/Invest по UX кадров и track record — это признано.
• Контур сигналов уже «взрослый» (много SKIP-фильтров). Проблема НЕ в «ещё одном
  фильтре на глаз», а в ИСТОЧНИКЕ EDGE и в незамкнутом контуре кадр→сделка→исход.
• Крупные игроки на календаре чаще зарабатывают на:
    (A) roll / liquidity (поток ролла, CS-книга, maker),
    (B) fair-value vs carry (ставка/storage/сезон),
  а НЕ на голом «|z|≥2 → fade к среднему» после 2–4 ног × slip.
• TRINITY сейчас ≈ z-mean-reversion + жёсткие стоп-фильтры. «Докрутить как Exclusive»
  (ещё чеклистов) НЕ сделает arb прибыльным само по себе.
• Paper и live — разные книги. Не чистить statement задним числом.
• Не обещать доходность. Research / operator desk.

════════════════════════════════════════════════════════
1. ЧТО УЖЕ ЕСТЬ (не изобретать заново)
════════════════════════════════════════════════════════
Playbook: CalendarArbPlaybook.evaluate
  • Вход: |z| ≥ entryZ (+ optional z-recede) → LONG_SPREAD / SHORT_SPREAD
  • Выход: EXIT_MEAN / EXIT_STOP / EXIT_TIME / EXIT_ROLL / EXIT_EVENT / EXIT_PARTIAL
  • Gates: session, bars, roll/FND, min DTE, event blackout, slope, stationary,
    half-life, thin vol, cost vs edge, GO cap, book, squeeze, NG seasonality,
    BR macro (EIA / CFTC COT / crack) → SKIP_*
  • Pick приоритет семей при нескольких сигналах: NG > GD > SI > RI > BR
    (НЕ max |z| по всем) — CalendarArbFamilyPolicy
  • Fly 1:-2:1 — paper ok; LIVE_SKIP_FLY (live fly нет)
  • Live: две market-ноги T-Invest; orphan → cancel/reverse; GO ≠ НКЦ (оценка)
  • Corpus: research-corpus/calendar-arb/ — огромный CALENDAR + OUTCOME
  • Fair-paper tick: CalendarArbFairPaperLiveService (~20s)

Эталон UX/track (смотреть, не копипастить слепо):
  Exclusive DF + trend-paper-journal + DecisionFrameAssembler
  Investments DF + investments-paper-journal + FILL/CLOSE corpus

════════════════════════════════════════════════════════
2. ДЫРЫ (зафиксированы разбором 2026-10-19)
════════════════════════════════════════════════════════
A. Track record
   • data/calendar-arb-paper-journal.json — ОТСУТСТВУЕТ / пусто при сканах
   • Corpus почти без CLOSE с pnlRub — OUTCOME ≠ statement PnL
   • Много hypothesized SKIP OUTCOME — не путать с исполненными сделками

B. Decision Frame
   • FE calendar-arb-desk ждёт decisionFrame / decisionFrames
   • CalendarArbDeskService кадры НЕ собирает / не отдаёт → плашка пустая
   • Нужен assembler + journal lane (как invest), RU texts, cache-bust desk JS

C. Edge / данные (прокси, не account PnL)
   • ENTER forward-OUTCOME: BR ≈ отрицательный mean R; GD/SI/RI лучше
   • Сейчас часто SKIP_MACRO на BR (crack pause) — auto «молчит» по нефти долго
   • Crack/EIA/COT — публичные прокси (Yahoo/FRED/CFTC), не биржа

D. Исполнение
   • Нет приоритета MOEX Calendar Spread (CS) / synthetic matching — legging risk
   • Market-only ноги; fly live stub; FND = LTD−lead (нет настоящего FND API)
   • maxOpenSpreads в settings по сути один слот в state

E. Ops
   • statusMessage Research иногда врёт («live выкл») при live-execution=true в yml
   • Не крутить live casually; sandbox/dry-run до явного go оператора

════════════════════════════════════════════════════════
3. ЦЕЛЬ РАБОТЫ ЭТОГО АГЕНТА
════════════════════════════════════════════════════════
Сделать arb достойным эталона Exclusive по КОНТУРУ (DF + paper/live книги +
честный track) И сузить/переопределить EDGE так, чтобы была шанс на прибыльность.

Разрешённые направления (в порядке приоритета):

ФАЗА 0 — правда по данным (обязательна первой)
  • Offline / OOS replay по семьям отдельно (SI, GD, RI, BR, NG):
    expectancy после costs, % SKIP_COST/REGIME, hold, winrate
  • Явно: какие семьи выкинуть / заморозить из auto
  • Не менять пороги «на глаз» без таблицы до/после

ФАЗА 1 — замкнуть контур как у Exclusive/Invest
  • Писать закрытия в calendar-arb-paper-journal (SANDBOX_FAIR / LIVE_*)
  • Corpus: CLOSE (и при необходимости ARM/FILL) с join к CALENDAR
  • Decision Frame: assembler + desk API + journal day file; brief «что делать»
  • Развести paper vs live в statement/metrics (два ряда)
  • Починить status/live copy, чтобы не врал оператору

ФАЗА 2 — источник edge (выбрать режим, не смешивать в одном авто без флага)
  Режим A — FV / cost gate (предпочтительный старт для TRINITY):
    вход только если |spread − fair| покрывает round-trip × k И z подтверждает;
    fair хотя бы простой (сезон NG / carry proxy / hist percentile vs raw z only)
  Режим B — roll-liquidity (отдельный флаг):
    окна до FND / объём CS; цель capture roll / maker, не «досидеть до z=0»
  НЕ расширять z-fade «чтобы чаще стреляло».

ФАЗА 3 — исполнение (после 0–1 и явного go)
  • Приоритет CS-инструмента / synthetic spread где возможно
  • Limits вместо голого market на обеих ногах где уместно
  • Fly live — только отдельная задача + тесты

════════════════════════════════════════════════════════
4. ЗАПРЕТЫ
════════════════════════════════════════════════════════
• Не «улучшать» entryZ/stopZ/time-stop без OOS таблицы по семьям
• Не тащить @Figures / Exclusive hunt / positional trail «заодно»
• Не включать/расширять live без явного go оператора
• Не чистить/переписывать старые journal задним числом
• Не смешивать arb строки с Exclusive/positional в одном разборе дня
• Не обещать «arb теперь прибыльный» без paper track месяцев
• Не трогать Exclusive/Invest/BRM код, кроме сравнения API/DF паттернов

════════════════════════════════════════════════════════
5. КЛЮЧЕВЫЕ ФАЙЛЫ
════════════════════════════════════════════════════════
IMOEX-core/trinity-calendar-arb/
  CalendarArbPlaybook, CalendarArbFamilyPolicy, CalendarArbFilters,
  CalendarArbRisk, CalendarArbCosts, CalendarSpreadQuality, CalendarRoll,
  CalendarArbFairPaper, CalendarArbCopy, CalendarGo, CalendarDv01, …
IMOEX-core/trinity-runtime/
  CalendarArbDeskService, CalendarArbFairPaperLiveService,
  CalendarArbLiveBrokerService, CalendarArbPaperJournalService,
  CalendarArbSettingsService, TrinityResearchCorpusService.recordCalendar*
IMOEX/
  trinity-app/.../calendar-arb-desk.{html,js}
  CalendarArbController
  application.yml → imoex.strategies.calendar-arb
  docs/DECISION_FRAME_CONTRACT.md — эталон кадра (lane для arb согласовать)
Данные (НЕ в git):
  IMOEX/data/calendar-arb-fair-paper-state.json
  IMOEX/data/calendar-arb-paper-journal.json  (сейчас дыра)
  IMOEX/research-corpus/calendar-arb/

════════════════════════════════════════════════════════
6. КАК ПРОВЕРЯТЬ
════════════════════════════════════════════════════════
• Юниты calendar-arb + runtime тесты затронутых сервисов
• Replay/OOS: таблица по семьям до и после изменения порогов/FV
• Desk: есть decisionFrame; «Сегодня» / statement показывают paper closes
• Corpus: после paper close появляется CLOSE в lane calendar-arb
• При FE-правках — cache-bust desk JS
• Не ждать ISS; котировки arb — T-Invest path

════════════════════════════════════════════════════════
7. СТИЛЬ С ОПЕРАТОРОМ
════════════════════════════════════════════════════════
• Коротко; вердикты честные (BR сейчас слабый по proxy R — не замазывать)
• «сделай сам» — в основном агенте, без ожидания зависших субагентов
• Эксперименты — за флагом с tear-out путём (@CalendarFv / @CalendarRollMode)
• Когда фаза 0 или 1 Готова к ревью — явно напиши: что сделано, что измерил,
  какие файлы/SHA, что осталось. Оператор проверит с другим агентом.

Начни с: git status обоих репо + чтение CalendarArbPlaybook + CalendarArbDeskService
+ наличие/отсутствие paper-journal. Не пересказывай промпт.
```

---

## Быстрые якоря

| Тема | Где |
|------|-----|
| Сигнал | `CalendarArbPlaybook.java` |
| Семьи / приоритет | `CalendarArbFamilyPolicy.java` |
| Desk API | `CalendarArbDeskService.java` |
| Paper tick | `CalendarArbFairPaperLiveService.java` |
| Live ноги | `CalendarArbLiveBrokerService.java` |
| Journal | `CalendarArbPaperJournalService.java` |
| Corpus | `TrinityResearchCorpusService.recordCalendar*` |
| FE | `calendar-arb-desk.js` / `.html` |
| Эталон DF | `docs/DECISION_FRAME_CONTRACT.md` |
| Сквозной контекст (не смешивать) | `docs/TRINITY_CHAT_HANDOFF_PROMPT.md` |

## Контекст решения (2026-10-19)

Разбор: arb ≠ «недоделанный Exclusive»; нужен FV/cost или roll-режим + DF/journal parity.  
Proxy OUTCOME: BR слабый; GD/SI/RI лучше. Journal пуст. DF API не эмитит кадры.
