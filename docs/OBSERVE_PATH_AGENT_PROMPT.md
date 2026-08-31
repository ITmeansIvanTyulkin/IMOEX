# Handoff prompt: Trend Exclusive observePathToReal

> **Как использовать:** скопируй блок «PROMPT FOR NEW AGENT» целиком в новый чат Cursor.  
> **Обновлено:** 2026-08-31 ~19:00 MSK · ветка `dev` в IMOEX + IMOEX-core запушена.

---

## PROMPT FOR NEW AGENT

Ты продолжаешь работу оператора TRINITY по **активной цели observePathToReal** — наблюдение Trend Exclusive fair-paper после гейтов 2026-08-24 на пути к реальной торговле FORTS. Это **не** задача «улучшить desk» или «подкрутить стратегию». Главное — **честное наблюдение**, вечерний journal, стабильный OOS, и только потом малый live SL.

### Активная цель (не закрыта)

```
Наблюдать Trend Exclusive fair-paper после гейтов 2026-08-24 по пути к реалу:
(1) 2–3+ торговых дня FORMING_BAR + вечерний journal (SWEEP/THROUGH/skip/smash/knife) без новых крутилок;
(2) стабильный paper/OOS expectancy;
(3) только потом FORTS SL в стакане малым размером;
(4) scale при совпадении с journal.

Жёстко: doNotTuneOnSight — не крутить pad/knife/smash/buffer «на глаз».
```

**Критерий «цель достигнута»** — все четыре пункта выполнены и верифицированы: live FORTS SL 1 лот работает, journal совпадает с paper, оператор явно дал go. **Сейчас Phase C заблокирован** — см. ниже.

---

### Результаты наблюдения на 2026-08-31 ~19:00 MSK

| Метрика | Значение |
|--------|----------|
| **Phase A** | **4/3** полных дня: 25, 26, 27, 28.08 (baseline 24.08 не считается) |
| **OOS 25–28** | 12 сделок, **+1 445 ₽**, labeled SL **100%** (0 UNLABELED после гейтов) |
| **Expectancy** | ~**120 ₽/trade**, win rate 58%, avg win ~317 ₽, avg loss ~194 ₽ |
| **Gate hits (smash/buffer/knife/dayShelf)** | **0** за все полные дни |
| **FORMING_BAR** | включён, SL/TP по ходу бара (как у брокера) |
| **liveExecution** | **false** (paper only) |
| **Phase C** | **deferred** — human OOS review pending; sandbox PostStop невозможен |
| **8080** | UP, Exclusive-only (`levels-profile-br-m5`, parallel=false) |

**PnL по полным дням (Exclusive M5):**
- 25.08: +502 ₽ (3 trades: SWEEP, THROUGH, TP2)
- 26.08: +70 ₽ (3 trades: SWEEP×3, incl. BE_STOP)
- 27.08: +224 ₽ (1 trade: SELL TP2)
- 28.08: +648 ₽ (5 trades: THROUGH×2, GAP, TP×2)

**31.08 (expiry day, не полный observe day):**
- 0 закрытий сегодня
- **Overnight open:** BUY RETEST **BRU6** @ 89.33, qty 2, SL 89.17625, TP1 89.53 (entry 10:50)
- Экспирация BRU6 — новые сетапы блокированы; SL/TP открытой позиции продолжают работать
- gateHits=0

**Последние коммиты (dev):**
- IMOEX-core `6b7474e` — экспирация, маркеры desk, fair-paper, ChecklistStructure fix
- IMOEX `4b9c86e` — timeline desk, expiry в новостях, observe-скрипты

**Локально не закоммичено:** `scripts/trend_observe_evening.py` — fix вечернего EOD seal для дней вне target + overnight в snapshot.

---

### Фазы пути paper → real

| Фаза | Статус | Что делать |
|------|--------|------------|
| **A** | ✅ done (4 дня) | FORMING_BAR + evening journal, gateHits=0, labeled SL |
| **B** | ✅ metrics ready | OOS expectancy положительная; **human OOS review обязателен** |
| **C** | ⛔ blocked | FORTS SL 1 лот в стакане — нужен код single-leg + маржа + operator go |
| **D** | ⏳ future | Scale только если live fills/SL = fair-paper journal |

**Blocker Phase C (код):**
- `TrendExecutionBridge` → только journal (SANDBOX_JOURNAL / LIVE_GATED)
- `BrokerClient` / `TInvestBrokerClient` — pairs-oriented, нет single-leg FORTS PostStopOrder
- T-Invest **sandbox** → `SandboxModeViolationException` на PostStopOrder (probe 27.08)

**Operator decision (27.08):** «go → затем стой, пока на бумаге» — OOS go отложен, продолжаем paper.

---

### Жёсткие правила агента

1. **`doNotTuneOnSight: true`** — не менять `sl-sweep-buffer-points`, smash-gate, deep-poke knife, pad без явного research gate и без OOS.
2. **Checklist fidelity** — при конфликте EXT vs §8/§14 побеждает чеклист. Side law: BOT=BUY bounce, TOP=SELL bounce; §8 retest только вне коробки.
3. **Не включать `liveExecution=true`** без явного запроса оператора после Phase C code + human OOS.
4. **Не restore `playbook: both`** пока positional H1 OOM не починен (hvnHills).
5. **Не commit `/data/`** — gitignored (journals, path-log, state).
6. **Коммиты** — только по запросу оператора; без Co-authored-by.
7. **Scope агента observe:** только Exclusive #1 (BR M5). Pairs DAILY и calendar-arb — оператор смотрит сам.

---

### Гейты зашиты 2026-08-24 (не трогать без gate)

1. SL за day TOP/BOT (day shelf geometry)
2. Smash-gate на §8 (вынос через дневную полку)
3. `sl-sweep-buffer-points: 5` — за полкой +5 pt; иначе skip если > speculative stop (20 pt)
4. Dump/melt knife на §8 только при deep-poke/smash (чистый break+hold не режем)

**Journal wire:** `slObserveNote` → notes + `lastClose.slKind` (SWEEP / THROUGH / GAP).  
**Skip-гейты** в robot journal редко — desk poll: `trend-gate-observe.jsonl`.

**Когда можно крутить (не сейчас):** много SWEEP+мелкий reclaim → buffer↑; много skip при спокойном §8 → buffer↓; dump режет в нож → deep-poke↑.

---

### Правило экспирации контракта (с 31.08)

- **Last trade day FORTS:** новые сетапы блокируются (range M5 + positional H1)
- **Open positions:** SL/TP management продолжается
- **Fail-closed:** если ISS не знает last trade date для concrete secid (BRU6) — block new setups
- **Overnight across roll:** SL/TP по **secid открытой позиции**, не front month (`barsForLiveClockExact`)
- **UX:** `situation.contractExpiry`, events, timeline circles D-7…Exp

Код: `TrendContractExpiry.java`, `TrendChartMarkers.java`, `TrendDeskService`, desk JS.

---

### Ops runbook

**Workspace:**
```
/Users/ivan/MEGA/Work/TRINITY/
├── IMOEX/          — публичный git (shell, UI, scripts, application.yml)
└── IMOEX-core/     — private git (trinity-trend, trinity-pairs, trinity-calendar-arb)
```

**Запуск (если 8080 down):**
```bash
cd /Users/ivan/MEGA/Work/TRINITY/IMOEX
mvn -Poperator -pl trinity-app -am spring-boot:run -DskipTests \
  -Dspring-boot.run.jvmArguments='-Xms256m -Xmx1536m'
```

**Утро после sleep/Mac off (~09:55 MSK):**
```bash
bash IMOEX/scripts/trend_observe_resume.sh YYYY-MM-DD
```

**Вечер (~18:02 MSK, после закрытия сессии):**
```bash
cd IMOEX && python3 scripts/trend_observe_evening.py [YYYY-MM-DD]
python3 scripts/trend_observe_expectancy.py
```

**Desk poll (skip-гейты, опционально в сессию):**
```bash
python3 scripts/trend_observe_desk_poll.py
```

**Health / desk:**
```bash
curl -sf http://127.0.0.1:8080/actuator/health
curl -s 'http://127.0.0.1:8080/api/trend/desk?instrument=BRU6' | jq '.situation.posture,.situation.contractExpiry,.fairPaper'
```

**Desk UI:** `http://127.0.0.1:8080/trend-signal-desk` (hard refresh `?v=20260831-expiry1`)

---

### Конфиг сейчас (`application.yml` + `data/trend-ui-settings.json`)

```yaml
imoex.trend:
  auto-execution: true
  live-execution: false
  broker-sim-max: true
  playbook: levels-profile-br-m5
  parallel-playbooks: false
  sl-sweep-buffer-points: 5
```

`data/trend-ui-settings.json`: instrumentId **BRU6**, playbook levels-profile-br-m5, live=false.

---

### Authoritative state files (local, не в git)

| Файл | Назначение |
|------|------------|
| `data/trend-observe-path-log.json` | **главный log observe path** — дни, EOD, phaseA/B/C |
| `data/trend-fair-paper-state.json` | open positions, lastProcessedBar |
| `data/trend-paper-journal.json` | SANDBOX_FAIR trades |
| `data/trend-gate-observe.jsonl` | desk poll skip hits |
| `data/trend-robot-journal.json` | engine actionable only |

---

### Ключевой код

| Область | Путь (IMOEX-core unless noted) |
|---------|-------------------------------|
| Playbook #1 | `trinity-trend/.../LevelsProfileBrPlaybook.java` |
| Engine filters | `TrendRobotEngine.java`, `ChecklistCompliance.java` |
| Fair paper live | `trinity-runtime/.../TrendFairPaperLiveService.java` |
| Desk API | `TrendDeskService.java` |
| Execution bridge | `TrendExecutionBridge.java` (journal only) |
| Contract expiry | `TrendContractExpiry.java` |
| Chart markers | `TrendChartMarkers.java` |
| Desk UI | IMOEX `static/js/trend-signal-desk.js`, `operator.css` |
| Observe scripts | IMOEX `scripts/trend_observe_*.py`, `trend_observe_resume.sh` |
| Roadmap rule | IMOEX `.cursor/rules/trinity-roadmap.mdc` |
| Checklist rule | IMOEX `.cursor/rules/checklist-fidelity.mdc` |

---

### Ближайшие действия (2026-09-01)

1. **~09:55** — `bash IMOEX/scripts/trend_observe_resume.sh 2026-09-01`
2. Проверить исход **overnight BRU6** (SL / TP1 / GAP / BE)
3. После flat — front month **BRV6**; новые сетапы на BRV6
4. **~18:02** — evening seal; optional day 6 paper (Phase C still deferred)
5. **Не** включать live FORTS, **не** крутить pad/knife
6. Ждать **human OOS go/no-go** от оператора перед Phase C coding

---

### Что НЕ делать

- ❌ Tuning buffer/knife/smash по одному дню или «на глаз»
- ❌ `liveExecution=true` или боевой PostStop без Phase C + operator go
- ❌ Restore parallel playbooks (positional OOM)
- ❌ Commit secrets / `/data/`
- ❌ Смешивать pairs INTRADAY live с observe Exclusive
- ❌ Объявлять цель выполненной до live FORTS SL + journal alignment

---

### TRINITY roadmap (контекст, не текущая задача)

1. **Pairs DAILY** — live paper (оператор)
2. **Trend #1/#2** — sandbox/fair-paper (твой scope = #1 observe)
3. **Calendar arb** — sandbox
4. **Volume desk / clusters / DOM walls** — deferred mid-Sep 2026+
5. **Delivery Instance vs Core** — autumn–winter 2026
6. **Load test ≥1000 users** — before public Instance

Product truth: research / decision-support, not black-box autotrade.

---

### Кандидаты на потом (только log, не implement)

См. `observeCandidatesLater` в path-log: cooldown после SL, mid-shelf HTF DOWN SWEEP, positional OOM, qty=1 TP1 policy. Trail runner после TP1 — **DONE 28.08**.

---

**Первый шаг нового агента:** прочитай `data/trend-observe-path-log.json`, проверь 8080 + overnight position, продолжи observe без tuning.

---

## Краткая версия (если лимит контекста)

```
Цель: observePathToReal — Exclusive BR M5 fair-paper → human OOS → FORTS SL 1 lot → scale.
Phase A done (4 days 25–28, +1445₽, labeled SL 100%, gateHits=0). Phase C blocked (no single-leg PostStop, sandbox can't stop).
31.08: expiry BRU6, overnight BUY RETEST @89.33×2 SL89.18 TP89.53. live=false, FORMING_BAR.
Завтра: trend_observe_resume.sh 2026-09-01. Evening ~18:02 trend_observe_evening.py.
doNotTuneOnSight. Log: data/trend-observe-path-log.json. Repos: IMOEX + IMOEX-core dev.
```
