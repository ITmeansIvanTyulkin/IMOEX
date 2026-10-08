# Decision Frame — контракт кадра (DF-0)

> Статус: **внедрено на `dev`** — DTO + сборщик + `decisionFrame` в desk API + UI сводка/плашка (DF-2).  
> Задача: [DF-0](https://app.clickup.com/t/869fegd5k). Эпик: [Decision Frame USP](https://app.clickup.com/t/869fegd2j).  
> Handoff: `docs/DECISION_FRAME_AGENT_PROMPT.md`. Тексты свечи: `docs/IMPULSE_CANDLE_TOOLTIP_PROMPT.md`.  
> Код: `IMOEX-core/.../DecisionFrame.java`, `DecisionFrameAssembler.java`; деск → `body.decisionFrame`.  
> Тест-эталон формы: `DecisionFrameAssemblerTest`.

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

Снято с `GET /api/trend/desk?instrument=BRX6&playbook=levels-profile-br-m5` на localhost (`dev`), barCount≈3k, источник баров `disk-archive+broker-tail+tape`, chartBar `2026-10-08T20:05`.  
Фикстура теста: `IMOEX-core/trinity-trend/src/test/resources/decision-frame/brx6-2026-10-08-live.json` (`DecisionFrameLiveCaptureTest`).

Вечерняя сессия: окно входов закрыто → робот **пропускает** (`MODE` / `SKIP`). Слои 1+4 заполнены; `sessionNorm` и `leadLag` отсутствуют.

```json
{
  "frameId": "BRX6|levels-profile-br-m5|2026-10-08T20:05:00|MODE|1",
  "asOf": "2026-10-08T20:05:00",
  "instrument": "BRX6",
  "playbookId": "levels-profile-br-m5",
  "lane": "range",
  "timeframe": "M5",
  "barTime": "2026-10-08T20:05:00",
  "trigger": "MODE",
  "summary": "Дельта footprint -5013. Стакан: аски толще бидов в ближних 5 уровнях (bid 861 / ask 1281). Робот пропускает: окно входов закрыто правилом сессии.",
  "robot": {
    "status": "SKIP",
    "side": "NONE",
    "mode": "NONE",
    "checklistIds": ["EXT_SESSION_EDGE"]
  },
  "details": [
    { "source": "delta", "layer": 1, "text": "Дельта footprint -5013.", "value": -5013 },
    { "source": "dom", "layer": 1, "text": "Стакан: аски толще бидов в ближних 5 уровнях (bid 861 / ask 1281).", "value": -420.0 },
    { "source": "shelf", "layer": 1, "text": "Полка BOT 102.16–102.35.", "value": 102.255 },
    { "source": "poc", "layer": 1, "text": "POC профиля около 104.", "value": 104.0 },
    { "source": "footprint", "layer": 1, "text": "Импульс Импульс вниз · -21 пт (LIQUIDITY).", "value": -21 },
    { "source": "robot", "layer": 4, "text": "Робот пропускает: окно входов закрыто правилом сессии." }
  ]
}
```

---

## DoD DF-0 + vertical slice 1+4

- [x] Схема `summary` + `details[]` + триггеры + словарь робота
- [x] Связь с тултипом: тултип не равен кадру
- [x] Код на `dev`: `decisionFrame` в ответе `/api/trend/desk`
- [x] UI сводка + плашка details live (`trend-signal-desk`, DF-2 минимум)
- [x] Юнит-эталон формы (`DecisionFrameAssemblerTest`)
- [x] Живой эталон BRX6 в доке + `DecisionFrameLiveCaptureTest`
- [x] Слои 1+4 только; слой 2/3 не выдумываются
