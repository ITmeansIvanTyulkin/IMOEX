# Positional GAP_FILL — weekend review (evidence only)

**As of:** 2026-09-25 EOD MSK  
**Mode:** research / decision-support · **не крутить** micro/ATR/size без go  
**Scope:** сравнить единственный live GAP_FILL close недели с BR skip 24–25.09

---

## 1. Что есть в выборке

| Дата | Семья | Решение | Итог |
|------|--------|---------|------|
| 24.09 | BR | `GAP_FILL` SKIP — large gap vs ATR | не входили |
| 25.09 | BR | `GAP_FILL` SKIP — medium gap needs hard micro confirm | не входили |
| 25.09 | **NG** | ARM → FILL → **SL THROUGH** | **−420 ₽** |

Живых закрытых GAP_FILL за неделю: **1**. «Вчера красиво / сегодня плохо» на одной семье **не подтверждается** данными — вчера BR skip, сегодня убыток на NG.

Journal id: `sandbox-fair-positional-volume-h1-NGV6-2026-09-25T09-00`  
Corpus: ARM+FILL есть; **CLOSE в corpus на момент review не найден** (есть journal CLOSE) — дыра для обогащения.

---

## 2. Сделка NGV6 25.09 (разбор)

### Геометрия

| | |
|--|--|
| Prior close (24.09 23:00) | **3.322** |
| Open day (25.09 06:00) | **3.280** |
| Overnight gap | **DOWN ~42 pts** (point 0.001) |
| Side | BUY (в заполнение вниз) |
| Entry (fair) | **3.26015** · 09:00 bar / ARM stamp ~10:00 |
| SL / exit | **3.25015** · CLOSE 13:00 · SL=THROUGH |
| TP1 / TP2 | 3.29265 / 3.32515 (не трогали) |
| Qty | 6/9 grid |

H1 path (desk):

- 09:00 — low **3.252** (уже укол под вход), close 3.262  
- 10:00 — отскок до 3.284, close 3.272  
- 11–12 — пила 3.25x  
- 13:00 — low **3.245**, close 3.251 → стоп пробит закрытием

### Лента (MSK, tape-2026-09-25-NGV6)

| Окно | qty | buy | sell | delta |
|------|-----|-----|------|-------|
| 08–10 | 14 406 | 6 829 | 7 577 | **−748** |
| 09–10 (час входа) | 13 915 | 6 688 | 7 227 | **−539** |
| 10–12 | 42 569 | 22 179 | 20 390 | **+1 789** |
| 12–13 (к стопу) | 13 978 | 5 836 | 8 142 | **−2 306** |

На входе агрессор **не** в нашу сторону (sell≥buy). Короткий buy-перевес 10–12 не удержал; к 12–13 снова сильный sell → THROUGH.

### Стакан (DOM top5, медианный снимок часа)

| Час MSK | bidTop5 | askTop5 | imb bid/(bid+ask) |
|---------|---------|---------|-------------------|
| 09 | 1093 | 771 | 0.59 |
| 10 | 908 | 1269 | 0.42 |
| 12 | 888 | 1298 | 0.41 |
| 13 | 815 | 1303 | 0.38 |

После 10:00 ask-сторона толще — давление против BUY fill.

### Footprint (desk, часовые кластеры)

- **09:00:** смешанный проход 3.288→3.252; крупные sell-кластеры на 3.280 / 3.275 / 3.270 (delta −398 / −379 / −430).  
- **10:00:** локальный buy у 3.283 / 3.271, но низ диапазона снова с sell.  
- **12–13:** у зоны стопа (~3.25) доминирует sell (13:00 @ 3.250 delta **−1183**).

**Вывод по кейсу:** вход формально в gap-fill DOWN, но **микроструктура на входе и у стопа была против** (лента + DOM + FP). Это не «ошибка полки», а слабый confirm при уже разрешённом ARM.

---

## 3. BR 24–25 — почему не брали (для контраста)

| День | Overnight gap | Gate в corpus |
|------|---------------|---------------|
| 24.09 | DOWN **−131** pts (103.41→102.10) | `large gap vs ATR — skip fill` |
| 25.09 | DOWN **−129** pts (106.99→105.70) | сначала `medium gap needs hard micro confirm`, затем bounce sleeps (gap still open) |

BR 24.09 — крупный гэп, стратегия **сознательно** не fill.  
BR 25.09 — сопоставимый overnight, micro не прошёл; bounce ждал заполнения (prior 106.99 ещё далеко).

Сравнивать «успех закрытия гэпа на графике» с NG-убытком нельзя 1:1: **разные инструменты, разные гейты, у BR не было FILL**.

---

## 4. Гипотезы на потом (не внедрять сейчас)

Только после **≥5–10** размеченных GAP_FILL closes + явный go:

1. **Confirm gate:** не ARM, если в окне arm лента delta против fill (как NG 09: sell-heavy).  
2. **DOM soft veto:** askTop5≫bidTop5 на стороне против fill.  
3. **Corpus enrichment:** на ARM/FILL/CLOSE писать tape-delta / DOM imb / FP summary в payload (сейчас почти только rationale/sl/tp; CLOSE иногда только в journal).  
4. Не трогать ATR large-skip и squeeze без отдельного OOS — BR skip 24.09 выглядит защитным, не багом.

---

## 5. Next week ops

- Exclusive: observe + corpus, Phase C NO_GO, doNotTuneOnSight.  
- Positional GAP_FILL: копим closes; этот файл = baseline evidence.  
- При следующем GAP_FILL CLOSE — дописать строку в §1 таблицы и приложить tape/DOM окна по тому же шаблону.

**Артефакты (в git):**  
- `docs/trend-gap-fill-weekend-review.md` (этот файл)  
- `docs/trend-gap-fill-weekend-review.json` (машинный снимок)  

Локальная копия также лежит в `data/` (gitignore) для операторских скриптов.
