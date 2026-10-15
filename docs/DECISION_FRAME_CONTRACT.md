# Decision Frame — контракт кадра (DF-0)

> Статус: **DF-0…DF-6 на `dev`** — слои 1–4 + sync + allocator frames.  
> Задачи: [DF-0](https://app.clickup.com/t/869fegd5k) … [DF-6](https://app.clickup.com/t/869fegddy).  
> Эпик: [Decision Frame USP](https://app.clickup.com/t/869fegd2j).  
> Код: `SessionNormCompare`, `LeadLagCompare`, `SyncDivergenceCompare`, `AllocatorDecisionCompare`; деск → `decisionFrame` + `syncContext` + journal.  
> Тесты: assembler / sessionNorm / leadLag / sync / `AllocatorDecisionCompareTest`.

Exclusive / BR: слои **1–4** + DF-5 ribbon. Positional: 1+2+4 (sync lead/lag на range lane). DF-6: кадры на переключение рукавов капитала.

Новое поле деска: `decisionFrame`. Существующие `summary` (строка) и `situation.why` не трогаем — это текущий деск, не кадр.

---

## Кадр

```json
{
  "frameId": "BRV6|levels-profile-br-m5|2026-10-08T14:35|WAIT|1",
  "asOf": "2026-10-08T14:35:00",
  "instrument": "BRV6",
  "playbookId": "levels-profile-br-m5",
  "lane": "range",
  "timeframe": "M5",
  "barTime": "2026-10-08T14:35:00",
  "trigger": "WAIT",
  "summary": "…",
  "robot": {
    "status": "WAIT",
    "side": "NONE",
    "mode": "NONE",
    "checklistIds": ["S11_WAIT_LIMITS"]
  },
  "details": []
}
```

| Поле | Смысл |
|------|--------|
| `frameId` | `{instrument}\|{playbookId}\|{barTime}\|{trigger}\|{seq}` |
| `asOf` / `barTime` | локальное время MSK, без суффикса зоны в строке (как бары деска) |
| `lane` | `range` \| `brm` \| `positional` \| `invest`. На деске виден только свой lane |
| `trigger` | см. ниже |
| `summary` | 2–4 предложения, читается за ~5 секунд |
| `robot` | статус решения + внутренние id пунктов чеклиста |
| `details[]` | факты, из которых собрана сводка |

Один кадр на событие. Опрос деска без нового триггера кадр не плодит. Открытая плашка показывает **последний** кадр и подменяется, когда приходит следующий.

Журнал (DF-3) — очередь этих объектов. В контракте достаточно, что кадр самодостаточен для записи.

---

## Триггеры

| `trigger` | Когда |
|-----------|--------|
| `IMPULSE` | закрытый бар с паттерном импульса (`ImpulseBarExplain`, свежий KNIFE/ROCKET и соседние паттерны тултипа) |
| `SHELF_TOUCH` | цена коснулась дневной полки |
| `ARM` | план стал actionable, сетка ещё не в работе |
| `WAIT` | ждём подтверждение или касание сетки, вход не отменён |
| `SKIP` | входа нет: нет полки, сессия, чеклист не собран |
| `ENTER` | лимитки в работе или переход в позицию |
| `MODE` | смена режима: сессия открылась/закрылась, смена стороны/mode, смена lane |
| `ALLOC` | DF-6: переключение рукава капитала (веса / Exclusive↔BRM / mini-book) |

---

## Робот

Видимый словарь — три слова. В JSON статус латиницей.

| `robot.status` | На экране | Когда |
|----------------|-----------|--------|
| `ENTER` | входит | `ARM` с сеткой, `WORKING_ORDERS`, только что вошёл |
| `WAIT` | ждёт | зона есть, чеклист говорит ждать бар/касание; подтверждения нет |
| `SKIP` | пропускает | `NO_TRADE`, блок сессии, нет структуры |

`side`: `BUY` \| `SELL` \| `NONE`.  
`mode`: Exclusive — `BOUNCE` \| `RETEST` \| `NONE`; investments — `PLACE_LIMITS` \| `WORKING_ORDERS` \| `IN_TRADE` \| `NONE`.  
`checklistIds`: Exclusive — id из `ChecklistCompliance`; investments — id стадий pipeline (`regime`, `fund`, `zones`, …). В текст оператора **не** попадают `§`, `/view/` и сырые id. В `details` с `source=robot` — фраза по-русски.

`qty` вида `1/3` — исполнено/план сетки, не ошибка объёма.

Lead/lag, когда появится, **не** запрещает вход сам по себе. Запрет только если это явное правило playbook, и тогда это `source=robot`, не `leadLag`.

---

## `details[]`

```json
{
  "source": "delta",
  "layer": 1,
  "text": "Дельта закрытого бара −200.",
  "value": -200
}
```

`source` (закрытый список):

| source | Слой | Откуда брать число |
|--------|------|--------------------|
| `delta` | 1 | лента / footprint бара, не текст модели |
| `dom` | 1 | стакан деска (`book`) |
| `shelf` | 1 | дневная полка Exclusive |
| `poc` | 1 | POC профиля |
| `cluster` | 1 | кластер у полки |
| `footprint` | 1 | footprint деска |
| `sessionNorm` | 2 | фаза дня + дневной ход + объём/ход бара vs медиана того же clock-hour (`SessionNormCompare`); soft-hint после клиринга |
| `leadLag` | 3 | BR–Si–RI: короткая строка в summary + цифры/смысл в details (`LeadLagCompare`); **не veto** |
| `sync` | 3 | DF-5: топ-аномалии из ленты расхождений → details кадра (журнал) |
| `robot` | 4 | план playbook: статус, сторона, mode, пункты чеклиста |
| `allocator` | 4 | DF-6: переключение рукавов капитала (Exclusive / BRM / positional) — «Капитал сюда, потому что…» |

`layer`: `1` \| `2` \| `3` \| `4`.  
`text`: один факт, одна строка. Без эссе и без пяти пересказов одного события.  
`value`: число или диапазон, если есть. Нет числа — поля нет. Не дописывать «примерно».

В сводку попадает только то, что есть в `details`. Если факта нет, предложение не пишем.

---

## Сводка vs тултип

Тултип свечи (`impulseLesson`) — микро-почему одного бара. Кадр — решение целиком: картина + что делает робот.

Сводка: что на картине → что это значит для входа → робот входит / ждёт / пропускает и почему. 2–4 предложения. Детали — список фактов, не второй тултип.

Тексты формулирует код из полей. Модель может помочь с фразой, числа только из `value` / полей деска.

---

## Эталон: живой BRX6 (Exclusive), 2026-10-08

Снято с `GET /api/trend/desk?instrument=BRX6&playbook=levels-profile-br-m5` на localhost (`dev`).  
Фикстура: `IMOEX-core/.../decision-frame/brx6-2026-10-08-live.json` (`DecisionFrameLiveCaptureTest`).

Вечер: окно входов закрыто → `MODE` / `SKIP`. Слои 1–4 заполнены фактами (без выдумок).

```json
{
  "frameId": "BRX6|levels-profile-br-m5|2026-10-08T20:25:00|MODE|1",
  "trigger": "MODE",
  "summary": "Дельта footprint …. Стакан: …. Норма сессии: окно новых входов закрыто …. Робот пропускает: ….",
  "robot": { "status": "SKIP", "checklistIds": ["EXT_SESSION_EDGE"] },
  "details": [
    { "source": "delta", "layer": 1 },
    { "source": "dom", "layer": 1 },
    { "source": "shelf", "layer": 1 },
    { "source": "poc", "layer": 1 },
    { "source": "footprint", "layer": 1, "text": "Импульс вверх · 28 пт …", "value": 28 },
    { "source": "sessionNorm", "layer": 2 },
    { "source": "leadLag", "layer": 3, "text": "Связка за день: BR …, BRM …, RI …" },
    { "source": "robot", "layer": 4 }
  ]
}
```

Полный JSON — в фикстуре теста (числа меняются от бара к бару).

---

## DF-2 — UI

Компонент: `trinity-app/.../static/js/trinity-decision-frame.js` → `TrinityDecisionFrame.create({ root })`.  
Mount: trend-desk (range/BRM/positional), calendar-arb, investments — плашка видна только если API отдал `decisionFrame`.

### Investments (`lane=invest`)

- `playbookId`: `investments`, `timeframe`: `D1`.
- Assembler: `InvestmentsDecisionFrameAssembler` (сигнал + режим IMOEX + fair-paper pending/open).
- Desk: `GET /api/investments/desk?ticker=&refresh=` → `decisionFrame` (фокус), `decisionFrames` (вся вселенная за день), `operatorBrief`.
- Журнал: тот же `DecisionFrameJournalService` (`playbookId=investments`) — **один кадр на каждый тикер скана**, не только фокус плашки.
- `marketSnap`: range1/range2, entryLimit, target, stop, verdict, score.
- `robot.mode`: `PLACE_LIMITS` \| `WORKING_ORDERS` \| `IN_TRADE` \| `NONE` (не bounce/retest Exclusive).
- Источники details: стадии чеклиста → `robot` / `cluster` / `shelf` / `poc`; сводка и why — по-русски.

| Состояние | Когда |
|-----------|--------|
| `idle` | кадр отрисован |
| `updating` | первый load / тихий refresh без мигания текста |
| `stale` | ошибка/таймаут desk или нет кадра / давно без обновления |

Поведение: клик ↔ факты live; под chart; при наличии кадра блок «Сейчас на рынке» сжимается до короткого companion (без дубля why). Тултип свечи = микро-why.

## DF-3 — Журнал кадров

Persist: `data/decision-frame/YYYY-MM-DD.jsonl` (не в git). Запись при смене decision-moment (`instrument|playbook|barTime|trigger|robot.status`); dedupe устойчив к смене playbook и рестарту JVM (ключи из файла дня). Опрос деска без нового триггера не плодит строки.

| API | Назначение |
|-----|------------|
| desk `decisionFrames` | фрагмент дня для текущего instrument/playbook |
| `GET /api/trend/decision-frames` | фильтры `day`, `instrument` (family как paper: BR↔BRX6), `playbook` (`both`/`all` = все), `kind` (`wait`\|`enter`\|`skip`; `why`→`wait`), `limit` |
| `GET /api/trend/decision-frames/export` | JSON того же фильтра (UI качает через `fetch` + auth) |

UI: блок «Журнал кадров» на trend-desk — фильтры ждёт/вошёл/пропуск (пустой фильтр → явное «нет кадров»), раскрытие того же `details[]`, кнопка экспорт.

## DoD DF-0 + DF-2 (закрыто)

- [x] Контракт + desk API `decisionFrame`
- [x] Слои 1–4 из фактов (`sessionNorm` / `leadLag` только при данных)
- [x] UI сводка + плашка + idle/updating/stale без flicker
- [x] `TrinityDecisionFrame` на trend / arb / investments
- [x] Нет дубля «Импульс Импульс»; пункты импульса — абсолютные
- [x] Нет дубля essay под кадром на trend-desk
- [x] Живой эталон BRX6 + юнит-тесты

## DoD DF-1 (закрыто на `dev`)

- [x] Слой 1 — картина из desk facts (delta/DOM/shelf/POC/cluster/footprint)
- [x] Слой 2 — `SessionNormCompare`: фаза · дневной ход · объём/ход vs медиана того же часа · hint после клиринга
- [x] Слой 4 — решение робота (чеклист / enter·wait·skip), без LLM
- [x] `situation.sessionNorm` + `details[].source=sessionNorm` в сводке при наличии фактов
- [x] Unit: `SessionNormCompareTest`, `DecisionFrameAssemblerTest`

## DoD DF-3 (закрыто на `dev`)

- [x] Persist JSONL день / инструмент / playbook / kind
- [x] Фильтры API + desk fragment
- [x] UI список + detail-пакет (+ API export; кнопка UI убрана по запросу)
- [x] За сессию paper можно отфильтровать «ждёт» и «вошёл» с полными фактами

## DoD DF-4 (закрыто на `dev`)

- [x] `LeadLagCompare`: день BR/Si/RI + час (опережает/отстаёт/против; BRM не peer)
- [x] Короткая строка в summary; полный факт с цифрами в details
- [x] Явно «контекст, не запрет входа» — без veto-языка
- [x] desk: `body.leadLag` / `situation.leadLag`
- [x] Unit: `LeadLagCompareTest`, assembler summary/details split

## DoD DF-5 (закрыто на `dev`)

- [x] `SyncDivergenceCompare` + desk `syncContext` (preset oil: BR/Si/RI; BRM не peer)
- [x] UI: toggle «Контекст» без reload (localStorage) · фильтр «только аномалии»
- [x] Клик по событию → seek графика к `at`
- [x] Топ-аномалии в `details[].source=sync` → журнал кадров
- [x] Sync-панель ~50–60%: sparkline peers (`peerSparks`) + лента событий
- [x] Preset peers: `oil` (BR·Si·RI) / `index-oil` (RI·BR·Si) — UI + `?syncPreset=`

## DoD DF-6 (вертикальный срез на `dev`)

- [x] `AllocatorDecisionCompare` + `Source.allocator` / `Trigger.ALLOC`
- [x] Кадр на toggle Exclusive / BRM / positional и mini-book defaults
- [x] Журнал: `kind=alloc`, фильтр «капитал» на деске
- [x] Summary: «Капитал сюда (…), потому что …» + details (mutex / equity / до→после)
- [x] Пользовательские веса рукавов (`CapitalWeightService` + UI «Капитал») + risk-impact ₽
- [x] Live-rebalance: nudge от `rangeRatio` / regime ADX / lead-lag (+ кадр ALLOC при сдвиге ≥3 п.п.)
- [x] Дефолт капитала: `TREND_M5` (не legacy PAIRS); pairs-share только если оператор явно задал
- [x] Live facts: calendar `edgeCoversCross` + |z| (peek + async warm, если cache пуст)
- [x] BALANCED &lt;300к → доминирующий рукав по конфигу (не всегда pairs; arb-aware)
- [x] Миграция legacy `capital-sleeve-weights.json` pairs-only → YAML TREND_M5
- [x] Live-nudge сидит нулевые рукава (в т.ч. M5); risk-impact = CapitalAllocator caps
- [x] Sleeve equity: 0% weight → legacy fallback (armed robot не на 0₽)
- [x] Lead/lag: flat (0) не считается «согласованы»
- [x] capitalWeights tick изолирован от decisionFrame try
