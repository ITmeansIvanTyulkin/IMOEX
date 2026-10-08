# Decision Frame — контракт кадра (DF-0)

> Статус: **DF-0 + DF-2 + DF-3 DoD на `dev`** — контракт, desk API, UI live, журнал кадров (JSONL).  
> Задачи: [DF-0](https://app.clickup.com/t/869fegd5k) · [DF-2](https://app.clickup.com/t/869fegda5) · [DF-3](https://app.clickup.com/t/869fegdb2).  
> Эпик: [Decision Frame USP](https://app.clickup.com/t/869fegd2j).  
> Handoff: `docs/DECISION_FRAME_AGENT_PROMPT.md`. Тексты свечи: `docs/IMPULSE_CANDLE_TOOLTIP_PROMPT.md`.  
> Код: `IMOEX-core/.../DecisionFrame.java`, `DecisionFrameAssembler.java`, `DecisionFrameJournalService.java`; деск → `body.decisionFrame` + `body.decisionFrames`.  
> Тест-эталон формы: `DecisionFrameAssemblerTest`; журнал: `DecisionFrameJournalServiceTest`.

Первый срез — Exclusive / BR, playbook `levels-profile-br-m5`, слои **1 (картина)** и **4 (решение)**. Слои 2 и 3 в кадре можно опустить. Пока их нет, сводка **не говорит** про норму часа и про RI/BRM.

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
| `lane` | `range` \| `brm` \| `positional`. На деске виден только свой lane |
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

---

## Робот

Видимый словарь — три слова. В JSON статус латиницей.

| `robot.status` | На экране | Когда |
|----------------|-----------|--------|
| `ENTER` | входит | `ARM` с сеткой, `WORKING_ORDERS`, только что вошёл |
| `WAIT` | ждёт | зона есть, чеклист говорит ждать бар/касание; подтверждения нет |
| `SKIP` | пропускает | `NO_TRADE`, блок сессии, нет структуры |

`side`: `BUY` \| `SELL` \| `NONE`.  
`mode`: `BOUNCE` \| `RETEST` \| `NONE` — как у Exclusive, без переименования.  
`checklistIds`: внутренние id из `ChecklistCompliance` (`S8_RETEST_BREAK_HOLD`, `S11_WAIT_LIMITS`, …). В текст оператора **не** попадают `§`, `/view/` и сырые id. В `details` с `source=robot` — фраза по-русски («ждёт возврата к лимиткам, без пробоя сетки на лету»).

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
| `sessionNorm` | 2 | норма часа/клиринга — **нет в первом срезе** |
| `leadLag` | 3 | BR–BRM–RI одной строкой — **нет в первом срезе** |
| `robot` | 4 | план playbook: статус, сторона, mode, пункты чеклиста |

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

## DoD DF-3 (закрыто на `dev`)

- [x] Persist JSONL день / инструмент / playbook / kind
- [x] Фильтры API + desk fragment
- [x] UI список + detail-пакет + экспорт
- [x] За сессию paper можно отфильтровать «ждёт» и «вошёл» с полными фактами
