# Handoff prompt: TRINITY operator (чат 06.10.2026 — диапазонная, TP3, плашки, investments)

> **Как использовать:** скопируй блок «PROMPT FOR NEW AGENT» целиком в новый чат Cursor.  
> **Обновлено:** 2026-10-07 ~11:30 MSK · chat 07.10: UI «ждём/В СДЕЛКЕ», backlog лёгкого микро; ядро по-прежнему `351a4f4` (+ knife `a595b28`).  
> **Язык оператора:** русский, коротко и по делу. Не сыпать `§14` / путями `/view/...` в UI; в коде и этом промпте чеклист-номера допустимы.

---

## PROMPT FOR NEW AGENT

Ты продолжаешь работу оператора TRINITY на машине Ивана. Ниже — **все правила**, **вся работа из чата 06.10.2026**, и **как судить, стало ли по диапазонной лучше или хуже**. Не переспрашивай историю — она здесь. Не «улучшай» стратегию на глаз.

Workspace: `/Users/ivan/MEGA/Work/TRINITY/`  
- `IMOEX/` — публичный git (оболочка, UI, `application.yml`, scripts, docs). Remote `origin` → `ITmeansIvanTyulkin/IMOEX.git`, ветка **`dev`**.  
- `IMOEX-core/` — закрытое ядро (`trinity-trend` / `trinity-pairs` / `trinity-calendar-arb` / `trinity-runtime` / investments). Remote `origin` → `ITmeansIvanTyulkin/IMOEX-core.git`, ветка **`dev`**.  
Unlock = ядро на classpath (`-Poperator` если есть `../IMOEX-core`). Не коммитить `/data/`, секреты, `application-local.yml`, токены.

Две оси **не смешивать без явной просьбы:**

1. **observePathToReal** — честное наблюдение **Trend Exclusive #1, BR M5 fair-paper** (диапазонная / «Уровни + объёмы»).  
2. **Продукт desk / UX** — UI, плашки, investments, копирайт. Это **не** повод ретьюнить стратегию.

Pairs DAILY и calendar-arb оператор смотрит сам, пока не попросил иначе.

---

### Жёсткие правила (всегда)

1. **`doNotTuneOnSight: true`** — не менять `sl-sweep-buffer-points` (5), smash-gate, deep-poke knife, `allow-zone-pad`, `stop-points`/`tp1-points` BR без явного research gate + OOS и без просьбы оператора.  
2. **Checklist fidelity** — при конфликте EXT vs чеклист **побеждает чеклист**. Side law #1: BOT=BUY bounce, TOP=SELL bounce. RETEST long только пока close **≥ TOP↑**; short только пока close **≤ BOT↓**. Close **внутри коробки** = bounce. Gate: `ExclusiveSide`. HTF / CL / макро / one-setup **не переворачивают сторону**.  
3. **Side law #2 (позиционная):** H1 UP → только LONG, H1 DOWN → только SHORT; HVN последних трёх полок; сетка 1:1:2:4. Gate: `PositionalSide`.  
4. **`liveExecution=true` для trend — запрещён** без Phase C code + human OOS + явный go.  
5. **Коммиты и push** — только по просьбе. Без `Co-authored-by` / Cursor trailer. Репы **отдельно**, ветка **`dev`**.  
6. **Не commit `/data/`**, journals, path-log, tape/DOM архив, секреты.  
7. **Не смешивать стратегии на desk:** «Сделки сегодня» только своего lane. История — **Statement**. Не подмешивать `latestClose` / `fp.lastClose` чужого lane.  
8. **UI для оператора:** не писать пути `/view/...` в видимый текст; не светить `§N`. Кухню HTF=/RANGE:/SANDBOX_FAIR фильтровать.  
9. **План на графике** (ENTRY/SL/TP) — только при `actionable` сетке или открытой сделке. ZONE_READY без линий — норма.  
10. **Не трогать** calendar-arb / pairs live «заодно», если задача — Exclusive observe или UI.  
11. **Product truth:** research / decision-support, не black-box и не обещание доходности.  
12. **Документ `docs/OBSERVE_PATH_AGENT_PROMPT.md`** — handoff; коммитить его только если оператор явно попросил (в чате 06.10 его часто **исключали** из коммитов).

---

### Состояние на 2026-10-06 вечер (~22:50 MSK)

| Метрика | Значение |
|--------|----------|
| **Front** | **BRX6** |
| **06.10 Exclusive** | **+483 ₽** · 5 сделок · выходы: TP3×1, SL×2, BE_STOP×2 · 4 из 5 с qty=1 при planned=3 |
| **Playbook yml** | `both` / parallel — смотри актуальный `application.yml`; OOS-фокус оператора = Exclusive M5 |
| **Mode** | FORMING_BAR · `live=false` · **Phase C NO_GO** · `doNotTuneOnSight` |
| **Git** | IMOEX `1298f03` · IMOEX-core `351a4f4` · оба на `origin/dev` |
| **Нож** | фикс `a595b28` (3 свечи + full-series hunt) — на сделку −336 в 12:00 **ещё не успел** |
| **TP3** | с `351a4f4`: хвост только на **полном** гриде; малый набор → весь объём на TP2 |

**Phase C:** metrics могут врать «ready» — нужен **human OOS + явный go**. FORTS SL / scale / pad-tune — нет.

---

## Диапазонная стратегия: что меняли, зачем, и как отвечать «лучше / хуже»

### A. Базовый движок входа (не ломали, не откатывать)

Робот **по-прежнему** руководствуется чеклистом при arm bounce/retest:

| Слой | Роль сейчас | Код |
|------|-------------|-----|
| Полка дня TOP/BOT | жёстко: геометрия дня, day-lock | `LevelsProfileBrPlaybook`, `DayZoneLock` |
| Закрытый отбой | жёстко при bounce | `requireBounceConfirm` / `bounceConfirmed` |
| Касания (touchQ) | жёстко: poke+reject; против HTF строже (≥3) | `PlaybookIntelligence.touchQuality` |
| HTF / фаза дня | сторона не переворачивается; против ветра — extra gate (вынос из зоны / торможение H1 / 2 close за mid) | `allowsDumpDayBotBounce` / `allowsRallyDayTopBounce` |
| Стакан (DOM) | **мягкий бонус** к touch, **не veto** | `domSoftBonus` |
| Кластеры / дельта у полки | в rationale / очки, **не hard skip** | `ShelfClusterPoints` |
| Кит / толпа / спуф | **считаются**, пишутся в hunt UI, **hard skip нет** (см. B) | `ExclusiveHuntExt` |
| Нож + импульс против HTF | **hard veto** (см. B) | `OCT2026_NARROW_MICRO_VETO` |

Оператор путалась: «раньше микро было мягче». Правда по истории чата:

1. Чеклист (полка / касания / отбой / HTF) **всегда** был жёстким для входа.  
2. Кит/толпа/спуф одно время ужесточали (hard), потом **3.10** сузили эксперимент `OCT2026_NARROW_MICRO_VETO` — снова **мягкие** (только нож на BUY и импульс против HTF = hard).  
3. Стакан/кластеры/дельта **не были** жёстким гейтом входа; они soft / observe.  
4. «Раньше плюс дня жирнее» — в основном из‑за **формы выхода (полный TP2)**, не из‑за того что микро было мягче.

### B. Микро-veto (эксперимент октября)

Файл: `IMOEX-core/.../ExclusiveHuntExt.java`, тег `@Experiment OCT2026_NARROW_MICRO_VETO`.

**Hard block сейчас только:**
- свежий **KNIFE** при **BUY**;
- свежий импульс (нож/ракета/stop-hunt) **против стороны** при HTF against.

**Не hard:** whale AGAINST, crowd AGAINST bounce, spoof — soft / UI.

**Фикс ножа `a595b28` (чат 06.10):** раньше latest-tag на текущем баре мог перезаписать KNIFE, и forming-bar hunt смотрел 1 бар → нож «не сработал», сделка −336. Сейчас: freshKnife держит **3 свечи**, hunt по **полной** серии. Если октябрьский OOS провалится — grep `OCT2026_NARROW_MICRO_VETO` и снять блок (как помечено в коде).

`TrendFairPaperLiveService`: `hunt.blocksNewArm()` → skip fill.

### C. Управление выходом / TP3 (главный сдвиг «прибыли дня»)

**Задумка 3.10:** TP3 = «старый полный TP2 + чуть-чуть хвоста».
- inflate размера ×4/3;
- на TP2 снять ~75% (≈ старый полный объём);
- ~25% runner на **TP3 = TP2 ± 0.5R**.

**Проблема на 06.10:** сетка часто набирала **1/3** (qty=1, planned=3). Тогда leave≥qty → ядро 75% не снималось, весь мелкий лот бежал в `BE_STOP` / `SL` / редкий `TP3`. День **+483**, но не серия солидных TP2 как 17.09 (+2554) / 28.09 (+1166).

**Решение оператора + коммит `351a4f4`:**
- **малый / неполный набор** → закрывать **весь** объём на **TP2** (как «былое»);
- **полный грид** (filled ≥ planned и planned ≥ 3) → TP3 runner (75% + хвост);
- поле `filledQty` на open; `hasTp3Runner()` в `FairPaperSimulator`.

Playbook rationale: `TP2 … TP3=(полный грид: 75%+хвост)`.

**Важно для нового агента:** на сделках **до** рестарта JVM с `351a4f4` поведение ещё старое. После рестарта — новое. Не сравнивай 06.10 с «уже починенным TP3» как будто фикс уже торговал весь день.

### D. Журнал: цифры для вердикта «лучше / хуже»

Источник: `IMOEX/data/trend-paper-journal.json`, сделки `mode ∈ {BOUNCE, RETEST}` (Exclusive sandbox).

| Окно | n | PnL | avg win | avg loss | Выходы (часто) |
|------|---|-----|---------|----------|----------------|
| **до 2026-10-03** | 115 | +58 840 ₽ | ~910 | ~−348 | TP2×56, BE_STOP×23, SL×34 |
| **с 2026-10-03** (факты до фикса TP3) | 5 (все 06.10) | +483 ₽ | ~305 | ~−215 | TP3×1, BE_STOP×2, SL×2 |
| **06.10 по qty** | 4× qty=1 planned=3; 1× qty=3 SL −336 (нож) | | | | |

Интерпретация, согласованная в чате с оператором:

- **Не** «чеклист сломался» и не «улучшения во вред» целиком.  
- **Да** — форма плюса изменилась: меньше полных TP2, больше частичных/BE, узкий veto пускает больше попыток.  
- **Да** — дыра ножа −336 = баг окна hunt (закрыт `a595b28`, после факта).  
- **Частичный откат выхода** (TP3 только на полном гриде) — осознанный шаг «ближе к былому плюсу» без отката всего октября.  
- Вердикт «стало хуже стратегии» по **одному дню из 5 сделок** — **недостаточен**. Смотреть 2–3+ торговых дня **после** `351a4f4` + `a595b28`, сравнивая: долю полных TP2, avg win, число BE_STOP на qty=1, пропуски ножа.

**Как отвечать оператору на «стало лучше или хуже?»**

1. Разделить: **вход** (чеклист/микро) vs **выход** (TP2/TP3) vs **баги** (нож).  
2. Вход: чеклист тот же; микро кит/толпа снова мягкие (= ближе к «раньше»); нож жёстче и исправлен.  
3. Выход: до `351a4f4` на частичном наборе было **хуже старого полного TP2**; после фикса — ожидаем снова солидные TP2 на малых входах и хвост только на полных.  
4. Итог дня 06.10 (+483) ≠ приговор; жирные дни сентября = много TP2 ~430–900₽ за сделку.  
5. Не крутить pad/buffer/smash по скрину 06.10.

### E. GAP_FILL / positional (рядом, не путать с Exclusive M5)

В том же периоде (не вся = этот чат, но в репо): ужесточение GAP_FILL (open с 07:00, hunt до arm, шире SL) — commits `ce1072d` и void BRX6 06.10 GAP_FILL `221c4e1` / UI `ea90caa`. Не смешивать эти строки с Exclusive bounce/retest при разборе «диапазонной».

---

## Работа UI / продукт из этого чата (уже в git)

### Плашки роботов справа (все strategy desks)

Проблема: плашки пропадали / уезжали поверх графика / верх «криво» / низ обрезан.

Итог `IMOEX@1298f03` (+ CSS/JS `plaques5`):
- host: правая колонка 12.2rem, скролл, spacer `::before` → низ при коротком стеке, полный скролл при длинном;
- «Ветер» и роботы **один** правый край (`padding 1.15rem`, `align-items: flex-end`);
- `chromeBottomPx()` — колонка под nav; padding снизу учитывает book-pressure fab;
- при переполнении `scrollTop = scrollHeight` (видны нижние / текущий стол);
- cookie-first fetch без stale Bearer (иначе 401);
- плашки на всех `/view/*` кроме dashboard; calendar-arb / investments в navigate;
- core: `DeskPlaquesService` — плашка инвестиций + календарного арбитража.

Не возвращать `inset:0` overlay / pin к DOM — это и сбило вёрстку.

### Investments desk

- Universe = `InvestmentsUniverse.DEFAULT_TICKERS` (**27** имён) — это то, что мониторит робот; watchlist слева = первые 6; клик по тикеру в списке «Робот» открывает график (max 6 панелей).  
- Тумблер **Наблюдение / Авто** как на других столах; hydrate из `/api/investments/settings`; не красить auto из stale desk snapshot; без stale Bearer.  
- Кнопка «Как торгует робот» — объяснить 27 (не «победители дня»; число может меняться если правят universe).  
- Не путать с «перенесли из FORTS» — список тот же universe.

### Прочее из сессии (контекст)

- Knife hole checklist / fair-paper hunt order — закрыто `a595b28`.  
- Вопрос «почему не купил отскок» — чаще UTC↔MSK, сессия 10:20, UsOil wait, don’t chase; не баг разметки.  
- Коммиты по просьбе; OBSERVE md часто **не** коммитили.  
- **07.10 UI:** линии на графике — не «план», а **«ждём …»** до fill / **«В СДЕЛКЕ …»** после fill (`trend-signal-desk.js?v=20261007-waitfill`).  
- **07.10 Exclusive (к обеду):** BUY 10:45 BE_STOP +140; SELL 10:55 TP2 +812; BUY 11:15 BOT bounce SL −112 (H1 DOWN видел; микро soft against — arm ok).

---

## План работ / backlog (не исполнять без go + OOS)

| ID | Что | Статус | Где |
|----|-----|--------|-----|
| **MICRO-LIGHT-1** | **Лёгкое** ужесточение микро Exclusive: кандидат — hard на **whale AGAINST** и/или **spoof** (не весь старый hard whale/crowd/spoof). Дельта/кластеры — отдельное решение. Только после **2–3+ торговых дней** post `351a4f4`+`a595b28` и явного go оператора. | **Отложено** — ждём больше сделок (чат 07.10) | ClickUp [869fd6143](https://app.clickup.com/t/869fd6143); код `ExclusiveHuntExt` / `OCT2026_NARROW_MICRO_VETO` |

Триггер идеи: 07.10 BUY от BOT при H1 DOWN + whale/spoof/delta against → SL −112; «если микро чуть жёстче — обрезали бы», но **не крутить сейчас**.

---

## Критерий observePathToReal (не закрыт)

```
Наблюдать Exclusive fair-paper после гейтов 2026-08-24:
(1) 2–3+ торговых дня FORMING_BAR + вечерний journal без новых крутилок pad/knife/smash/buffer;
(2) стабильный paper/OOS expectancy;
(3) только потом FORTS SL в стакане малым размером;
(4) scale при совпадении с journal.
doNotTuneOnSight.
```

**Цель достигнута** только когда live FORTS SL 1 лот работает, journal ≈ paper, оператор дал go. Phase C заблокирован. Не объявляй выполненным.

После `351a4f4` в evening seal специально отмечай: сколько сделок с полным гридом получили TP3 vs сколько закрылись полным TP2 на частичном наборе.

---

## Гейты 2026-08-24 (не трогать без gate)

1. SL за day TOP/BOT.  
2. Smash-gate на retest.  
3. `sl-sweep-buffer-points: 5`.  
4. Dump/melt knife на retest только при deep-poke/smash; чистый break+hold не режем.

Крутить не сейчас. Journal: `slObserveNote` → `SWEEP` / `THROUGH` / `GAP`.

---

## Ops

```bash
# JVM
cd /Users/ivan/MEGA/Work/TRINITY/IMOEX
mvn -pl trinity-app -am spring-boot:run
# profile dev + operator если есть ../IMOEX-core

# Утро
bash IMOEX/scripts/trend_observe_resume.sh YYYY-MM-DD

# Вечер
cd IMOEX && python3 scripts/trend_observe_evening.py [YYYY-MM-DD]
python3 scripts/trend_observe_expectancy.py
```

После правки static — копировать в `trinity-app/target/classes/` + bump `?v=…`. Java — рестарт.

**Страницы (не писать пути оператору в UI-тексте):** дашборд · диапазонная · позиционная · BRM · investments · calendar-arb · Statement · settings.

**Local state (не git):**  
`data/trend-paper-journal.json`, `trend-fair-paper-state.json`, `trend-observe-path-log.json`, `trend-ui-settings.json`, `investments-ui-settings.json`, corpus/gate jsonl.

### Ключевой код (чат 06.10)

| Тема | Где |
|------|-----|
| Exclusive playbook / size×4/3 / TP3 prices | `LevelsProfileBrPlaybook.java` |
| TP3 only full fill | `FairPaperSimulator.hasTp3Runner` / `filledQty` |
| Micro veto + knife 3-bar | `ExclusiveHuntExt.java` |
| Live skip on hunt | `TrendFairPaperLiveService` |
| Plaques API | `DeskPlaquesService.java` |
| Plaques UI | `trinity-status-plaques.js`, `operator.css` |
| Investments UI | `investments-desk.js/html`, `investments-charts-terminal.js` |
| Universe | `InvestmentsUniverse.java` |

---

## Что НЕ делать

- ❌ Tuning buffer/knife/smash/pad по одному дню или скрину 06.10 / 07.10  
- ❌ Откатывать весь октябрь «как было» целиком (чеклист + мягкое микро трогать не надо)  
- ❌ Снова включать hard whale/crowd/spoof без OOS (см. backlog **MICRO-LIGHT-1** — только лёгкий кандидат + go)  
- ❌ Вернуть TP3-хвост на qty=1 / неполный грид  
- ❌ `liveExecution=true` / Phase C без go  
- ❌ Смешивать Exclusive и positional / GAP_FILL в разборе дня  
- ❌ Писать `/view/...` и `§N` в видимый UI  
- ❌ Commit `/data/`, secrets; Co-authored-by  
- ❌ Ломать правую колонку плашек overlay/pin  
- ❌ Объявлять observePathToReal выполненным  

---

## Первый шаг новому агенту

1. Прочитай этот файл + хвост `data/trend-observe-path-log.json`.  
2. `curl` health + desk Exclusive BRX6: posture, why, open, live=false.  
3. Если вопрос «лучше/хуже по диапазонной» — ответь по разделу **D** (вход / выход / баг ножа / мало сделок после фикса TP3).  
4. Если observe — утро resume / вечер seal, **не** код стратегии.  
5. Коммит/push — только если сказали; два репо, `dev`; OBSERVE md — только по явной просьбе.

---

## Краткая версия (лимит контекста)

```
TRINITY = IMOEX + IMOEX-core, ветка dev (core 351a4f4 + knife a595b28; shell handoff/UI могут быть впереди 1298f03).
Exclusive BRX6 M5 fair-paper, live=false, Phase C NO_GO, doNotTuneOnSight.
06.10 Exclusive +483; с 351a4f4 TP3 только полный грид, иначе весь объём на TP2.
Микро OCT2026_NARROW_MICRO_VETO: hard только KNIFE@BUY и импульс против HTF; кит/толпа/спуф soft.
BACKLOG MICRO-LIGHT-1 (НЕ сейчас): лёгкий hard whale AGAINST и/или spoof после 2–3+ дней + go — ClickUp https://app.clickup.com/t/869fd6143
07.10: UI «ждём/В СДЕЛКЕ» вместо «план»; BUY BOT −112 при H1 DOWN — микро soft, ждать больше сделок.
Вердикт better/worse — после 2–3 дней post-fix, не по одному дню.
Коммиты только по просьбе, без Co-authored-by; /data не коммитить.
```
