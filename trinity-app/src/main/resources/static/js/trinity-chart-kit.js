/**
 * Shared chart tools: stretch VAP on candle range, trend lines, configurable MAs, layout persist.
 * Used by trend signal desk and /view/trend-charts terminal.
 */
(function (global) {
  "use strict";

  function esc(t) {
    return String(t == null ? "" : t)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }

  function authHeaders(extra) {
    const headers = Object.assign({ Accept: "application/json" }, extra || {});
    try {
      const token = localStorage.getItem("trinity.supabase.access_token");
      const user = (localStorage.getItem("trinity.supabase.user_email")
        || localStorage.getItem("imoex.ops.user") || "").trim();
      if (user) headers["X-Trinity-User"] = user;
      if (token) {
        headers.Authorization = "Bearer " + token;
        return headers;
      }
      const pass = localStorage.getItem("imoex.ops.pass") || "";
      if (user && pass && user.indexOf("@") < 0) {
        headers.Authorization = "Basic " + btoa(unescape(encodeURIComponent(user + ":" + pass)));
      }
    } catch (_) {}
    return headers;
  }

  function currentUserKey() {
    try {
      return (localStorage.getItem("trinity.supabase.user_email")
        || localStorage.getItem("imoex.ops.user") || "anonymous").trim() || "anonymous";
    } catch (_) {
      return "anonymous";
    }
  }

  async function layoutFetch(url, opts) {
    opts = opts || {};
    const headers = authHeaders(opts.headers || {});
    const base = { credentials: "include" };
    let res = await fetch(url, Object.assign({}, base, opts, { headers: headers }));
    if (res.status === 401 || res.status === 403) {
      const cookieHeaders = Object.assign({}, headers);
      delete cookieHeaders.Authorization;
      res = await fetch(url, Object.assign({}, base, opts, { headers: cookieHeaders }));
    }
    return res;
  }

  async function loadLayouts() {
    try {
      const res = await layoutFetch("/api/charts/layouts");
      if (res.ok) {
        const doc = await res.json();
        try { localStorage.setItem("trinity.chart.layouts.local", JSON.stringify(doc)); } catch (_) {}
        return doc;
      }
    } catch (_) {}
    try {
      const raw = localStorage.getItem("trinity.chart.layouts.local");
      if (raw) return JSON.parse(raw);
    } catch (_) {}
    throw new Error("HTTP layout unavailable");
  }

  async function saveLayouts(doc) {
    try { localStorage.setItem("trinity.chart.layouts.local", JSON.stringify(doc || {})); } catch (_) {}
    try {
      const res = await layoutFetch("/api/charts/layouts", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(doc || {})
      });
      if (res.ok) {
        const saved = await res.json();
        try { localStorage.setItem("trinity.chart.layouts.local", JSON.stringify(saved)); } catch (_) {}
        return saved;
      }
    } catch (_) {}
    return doc || {};
  }

  /** FORTS quote step — RI/Si must never use BR's 0.01 (that freezes the tab). */
  function pointSizeFor(secid) {
    const u = String(secid || "").toUpperCase();
    if (!u) return 0.01;
    if (u.indexOf("RI") === 0 || u.indexOf("RTS") === 0) return 10;
    if (u.indexOf("SI") === 0) return 1;
    if (u.indexOf("GD") === 0 || u.indexOf("GOLD") === 0) return 0.1;
    if (u.indexOf("MX") === 0 || u.indexOf("MIX") === 0) return 0.05;
    if (u.indexOf("NG") === 0) return 0.001;
    return 0.01;
  }

  /** If quotes are finer than the declared FORTS step, use the real tick. */
  function inferPointSize(bars, declared) {
    let ps = declared > 0 ? declared : 0.01;
    if (!bars || bars.length < 3) return ps;
    let minPos = Infinity;
    const from = Math.max(0, bars.length - 160);
    for (let i = from; i < bars.length; i++) {
      const b = bars[i];
      if (!b) continue;
      const xs = [b.open, b.high, b.low, b.close].map(Number).filter(isFinite);
      for (let j = 0; j < xs.length; j++) {
        for (let k = j + 1; k < xs.length; k++) {
          const d = Math.abs(xs[j] - xs[k]);
          if (d > 1e-12 && d < minPos) minPos = d;
        }
      }
    }
    if (!(minPos < Infinity) || minPos >= ps * 0.51) return ps;
    if (minPos <= 0.001) return 0.001;
    if (minPos <= 0.01) return 0.01;
    if (minPos <= 0.05) return 0.05;
    if (minPos <= 0.1) return 0.1;
    if (minPos <= 1) return 1;
    return minPos;
  }

  function thinLevels(levels, cap) {
    if (!levels || !levels.length || !(cap > 0) || levels.length <= cap) return levels || [];
    const n = levels.length;
    const raw = [];
    let maxVol = 0;
    for (let i = 0; i < cap; i++) {
      const from = Math.floor(i * n / cap);
      const to = Math.floor((i + 1) * n / cap);
      if (from >= to) continue;
      let best = levels[from];
      let sum = 0;
      for (let j = from; j < to; j++) {
        sum += levels[j].volume || 0;
        if ((levels[j].volume || 0) > (best.volume || 0)) best = levels[j];
      }
      raw.push({ price: best.price, volume: sum });
      if (sum > maxVol) maxVol = sum;
    }
    const denom = maxVol > 0 ? maxVol : 1;
    raw.forEach(function (l) { l.strength = l.volume / denom; });
    return raw;
  }

  /** Build VAP levels from OHLC bars (inclusive index range). */
  function vapFromBars(bars, fromIdx, toIdx, pointSize) {
    let pt = pointSize > 0 ? pointSize : 0.01;
    const a = Math.max(0, Math.min(fromIdx, toIdx));
    const b = Math.min(bars.length - 1, Math.max(fromIdx, toIdx));
    if (!(a <= b) || !bars.length) return [];
    let loAll = Infinity;
    let hiAll = -Infinity;
    for (let i = a; i <= b; i++) {
      const bar = bars[i];
      if (!bar) continue;
      const lo = Math.min(Number(bar.low), Number(bar.high));
      const hi = Math.max(Number(bar.low), Number(bar.high));
      if (!isFinite(lo) || !isFinite(hi)) continue;
      if (lo < loAll) loAll = lo;
      if (hi > hiAll) hiAll = hi;
    }
    if (!(hiAll >= loAll) || !isFinite(loAll)) return [];
    const span = hiAll - loAll;
    // Declared tick can be a FORTS step (RI=10) while this chart's quotes are
    // 89.xx — that collapsed the whole stretch into one purple line at 90.
    if (span > 0) {
      if (pt >= span || span / pt < 16) {
        pt = span / 48;
      }
    }
    const MAX_SPAN = 400;
    const spanBuckets = span > 0 ? Math.round(span / pt) + 1 : 1;
    if (spanBuckets > MAX_SPAN) {
      pt = span / MAX_SPAN;
      if (!(pt > 0)) pt = pointSize > 0 ? pointSize : 0.01;
    }
    let anyVol = false;
    for (let i = a; i <= b; i++) {
      if (Number(bars[i] && bars[i].volume) > 0) { anyVol = true; break; }
    }
    const map = Object.create(null);
    for (let i = a; i <= b; i++) {
      const bar = bars[i];
      if (!bar) continue;
      const lo = Math.min(Number(bar.low), Number(bar.high));
      const hi = Math.max(Number(bar.low), Number(bar.high));
      const volRaw = Number(bar.volume);
      const vol = volRaw > 0 ? volRaw : (anyVol ? 0 : 1);
      if (!(vol > 0) || !(hi >= lo) || !isFinite(lo) || !isFinite(hi)) continue;
      const i0 = Math.round(lo / pt);
      const i1 = Math.round(hi / pt);
      const n = Math.max(1, i1 - i0 + 1);
      const share = vol / n;
      for (let k = i0; k <= i1; k++) {
        const key = String(k);
        map[key] = (map[key] || 0) + share;
      }
    }
    const levels = Object.keys(map).map(function (k) {
      return { price: Number(k) * pt, volume: map[k] };
    }).sort(function (x, y) { return x.price - y.price; });
    const max = levels.reduce(function (m, l) { return Math.max(m, l.volume); }, 0) || 1;
    levels.forEach(function (l) { l.strength = l.volume / max; });
    return thinLevels(levels, 96);
  }

  /** Merge high-volume nodes into purple band zones (ATAS-like). */
  function hvnBands(levels, minStrength) {
    const thr = minStrength == null ? 0.55 : minStrength;
    const peaks = (levels || []).filter(function (l) { return (l.strength || 0) >= thr; });
    if (!peaks.length) return [];
    const bands = [];
    let cur = null;
    peaks.forEach(function (p) {
      if (!cur) {
        cur = { low: p.price, high: p.price, volume: p.volume };
        return;
      }
      const gap = p.price - cur.high;
      const step = Math.max(1e-9, (levels[1] && levels[0]) ? Math.abs(levels[1].price - levels[0].price) : 0.01);
      if (gap <= step * 3) {
        cur.high = p.price;
        cur.volume += p.volume;
      } else {
        bands.push(cur);
        cur = { low: p.price, high: p.price, volume: p.volume };
      }
    });
    if (cur) bands.push(cur);
    return bands;
  }

  function sma(closes, period) {
    const out = [];
    let sum = 0;
    for (let i = 0; i < closes.length; i++) {
      sum += closes[i];
      if (i >= period) sum -= closes[i - period];
      if (i >= period - 1) out.push(sum / period);
      else out.push(null);
    }
    return out;
  }

  function ema(closes, period) {
    const out = [];
    const k = 2 / (period + 1);
    let prev = null;
    for (let i = 0; i < closes.length; i++) {
      if (prev == null) {
        if (i < period - 1) { out.push(null); continue; }
        let s = 0;
        for (let j = i - period + 1; j <= i; j++) s += closes[j];
        prev = s / period;
        out.push(prev);
      } else {
        prev = closes[i] * k + prev * (1 - k);
        out.push(prev);
      }
    }
    return out;
  }

  /**
   * Relayout HTML overlays while the user scales time or price.
   * Native LWC lines move immediately; overlays must be recomputed in the same frames.
   */
  function bindScaleOverlayFollow(host, opts) {
    opts = opts || {};
    if (!host || host._trinityOverlayFollowBound) return;
    host._trinityOverlayFollowBound = true;
    const onLayout = typeof opts.onLayout === "function" ? opts.onLayout : function () {};
    const freeze = opts.freezePrice !== false;
    let gesture = false;
    let until = 0;
    let raf = 0;
    function freezeNow() {
      if (!freeze) return;
      try {
        if (opts.series && typeof opts.series.priceScale === "function") {
          opts.series.priceScale().applyOptions({ autoScale: false });
        }
      } catch (_) {}
      try {
        if (opts.chart && typeof opts.chart.priceScale === "function") {
          opts.chart.priceScale("right").applyOptions({ autoScale: false });
        }
      } catch (_) {}
    }
    function kick(ms) {
      until = Math.max(until, Date.now() + (ms || 80));
      if (raf) return;
      function tick() {
        freezeNow();
        try { onLayout(); } catch (_) {}
        if (gesture || Date.now() < until) {
          raf = requestAnimationFrame(tick);
        } else {
          raf = 0;
          freezeNow();
          try { onLayout(); } catch (_) {}
        }
      }
      raf = requestAnimationFrame(tick);
    }
    host.addEventListener("wheel", function () { kick(480); }, { passive: true, capture: true });
    host.addEventListener("mousedown", function () { gesture = true; kick(0); });
    host.addEventListener("touchstart", function () { gesture = true; kick(0); }, { passive: true });
    window.addEventListener("mousemove", function (ev) {
      if (gesture || (ev && ev.buttons)) kick(0);
    });
    window.addEventListener("touchmove", function () {
      if (gesture) kick(0);
    }, { passive: true });
    function endGesture() {
      if (!gesture) return;
      gesture = false;
      kick(480);
    }
    window.addEventListener("mouseup", endGesture);
    window.addEventListener("touchend", endGesture, { passive: true });
    try {
      function hookPrice(ps) {
        if (ps && typeof ps.subscribeVisiblePriceRangeChange === "function") {
          ps.subscribeVisiblePriceRangeChange(function () { kick(80); });
        }
      }
      if (opts.series && typeof opts.series.priceScale === "function") hookPrice(opts.series.priceScale());
      if (opts.chart && typeof opts.chart.priceScale === "function") hookPrice(opts.chart.priceScale("right"));
    } catch (_) {}
  }

  function snapshotTimeScale(chart) {
    if (!chart) return null;
    try {
      const opt = chart.timeScale().options();
      return {
        barSpacing: opt && opt.barSpacing,
        logical: chart.timeScale().getVisibleLogicalRange()
      };
    } catch (_) {
      return null;
    }
  }

  function applyTimeScaleSnap(chart, snap) {
    if (!chart || !snap) return;
    if (snap.barSpacing > 0) {
      try { chart.timeScale().applyOptions({ barSpacing: snap.barSpacing }); } catch (_) {}
    }
    if (snap.logical) {
      try { chart.timeScale().setVisibleLogicalRange(snap.logical); } catch (_) {}
    }
  }

  var barDbPromise = null;
  function barDb() {
    if (barDbPromise) return barDbPromise;
    barDbPromise = new Promise(function (resolve, reject) {
      if (typeof indexedDB === "undefined") {
        resolve(null);
        return;
      }
      const req = indexedDB.open("trinity-candle-archive", 1);
      req.onupgradeneeded = function () {
        const db = req.result;
        if (!db.objectStoreNames.contains("bars")) {
          db.createObjectStore("bars");
        }
      };
      req.onsuccess = function () { resolve(req.result); };
      req.onerror = function () { resolve(null); };
    });
    return barDbPromise;
  }
  function barCacheKey(instrument, tf) {
    return String(instrument || "_").toUpperCase() + "|" + String(tf || "M5").toUpperCase();
  }
  async function barCacheGet(instrument, tf) {
    try {
      const db = await barDb();
      if (!db) return null;
      const key = barCacheKey(instrument, tf);
      return await new Promise(function (resolve) {
        const tx = db.transaction("bars", "readonly");
        const rq = tx.objectStore("bars").get(key);
        rq.onsuccess = function () {
          const row = rq.result;
          if (row && row.bars && row.bars.length) {
            resolve(row);
            return;
          }
          const last = tx.objectStore("bars").get("__last");
          last.onsuccess = function () { resolve(last.result || null); };
          last.onerror = function () { resolve(null); };
        };
        rq.onerror = function () { resolve(null); };
      });
    } catch (_) {
      return null;
    }
  }
  async function barCachePut(instrument, tf, payload) {
    try {
      const db = await barDb();
      if (!db || !payload || !payload.bars || !payload.bars.length) return;
      const row = Object.assign({
        instrument: instrument,
        tf: tf || "M5",
        savedAt: Date.now()
      }, payload);
      const key = barCacheKey(instrument, tf);
      await new Promise(function (resolve) {
        const tx = db.transaction("bars", "readwrite");
        tx.objectStore("bars").put(row, key);
        tx.objectStore("bars").put(row, "__last");
        tx.oncomplete = function () { resolve(); };
        tx.onerror = function () { resolve(); };
      });
    } catch (_) {}
  }

  function setSeriesDataKeepView(chart, series, data) {
    const snap = snapshotTimeScale(chart);
    try { series.setData(data); } catch (_) { return; }
    applyTimeScaleSnap(chart, snap);
    requestAnimationFrame(function () { applyTimeScaleSnap(chart, snap); });
  }

  function priceDecimals(pointSize) {
    if (!(pointSize > 0)) return 2;
    if (pointSize >= 1) return 0;
    if (pointSize >= 0.1) return 1;
    if (pointSize >= 0.01) return 2;
    return 4;
  }

  function fmtOhlcPx(v, pointSize) {
    const n = Number(v);
    if (!Number.isFinite(n)) return "—";
    let dec = priceDecimals(pointSize);
    const rounded = Number(n.toFixed(dec));
    if (Math.abs(n - rounded) > 1e-9) {
      if (dec < 2) dec = 2;
      else if (dec < 4) dec = 4;
    }
    return n.toFixed(dec);
  }

  function barTimeKey(t) {
    if (t == null) return "";
    if (typeof t === "number" && Number.isFinite(t)) return String(Math.round(t));
    const d = Date.parse(t);
    if (Number.isFinite(d)) return String(Math.round(d / 1000));
    return String(t);
  }

  function mskHourFromTime(t) {
    let ms = null;
    if (typeof t === "number" && Number.isFinite(t)) {
      ms = t > 1e12 ? t : t * 1000;
    } else {
      const p = Date.parse(t);
      if (Number.isFinite(p)) ms = p;
    }
    if (ms == null) return -1;
    return new Date(ms + 3 * 3600 * 1000).getUTCHours();
  }

  function mskMinuteFromTime(t) {
    let ms = null;
    if (typeof t === "number" && Number.isFinite(t)) {
      ms = t > 1e12 ? t : t * 1000;
    } else {
      const p = Date.parse(t);
      if (Number.isFinite(p)) ms = p;
    }
    if (ms == null) return -1;
    return new Date(ms + 3 * 3600 * 1000).getUTCMinutes();
  }

  /**
   * Tag huge candles: knife / spike / stop-hunt. Looks back so the plaque
   * still shows after the impulse bar has already closed.
   */
  function classifyImpulseSeries(bars, ctx) {
    const out = { latest: null, notes: {}, fresh: false };
    if (!bars || bars.length < 8) return out;
    const ps = (ctx && ctx.pointSize > 0) ? ctx.pointSize : 0.01;
    const st = (ctx && ctx.structure) || {};
    const openH = (ctx && ctx.sessionOpenHour != null) ? ctx.sessionOpenHour : 10;
    const ranges = [];
    const from = Math.max(0, bars.length - 68);
    for (let i = from; i < bars.length; i++) {
      const b = bars[i];
      if (!b) continue;
      const r = Number(b.high) - Number(b.low);
      if (r > 0) ranges.push(r);
    }
    if (!ranges.length) return out;
    const sorted = ranges.slice().sort(function (a, b) { return a - b; });
    const median = sorted[Math.floor(sorted.length / 2)] || 0;
    const vols = [];
    for (let i = from; i < bars.length; i++) {
      const v = Number(bars[i] && bars[i].volume);
      if (v > 0) vols.push(v);
    }
    vols.sort(function (a, b) { return a - b; });
    const medianVol = vols.length ? vols[Math.floor(vols.length / 2)] : 0;
    const getFp = ctx && typeof ctx.getFootprint === "function" ? ctx.getFootprint : null;
    const positional = !!(ctx && ctx.positional);
    const scanFrom = Math.max(0, bars.length - 48);
    let latest = null;
    let latestIdx = -1;
    for (let i = scanFrom; i < bars.length; i++) {
      const fp = getFp ? getFp(bars[i].time) : null;
      const tag = classifyImpulseBar(bars[i], median, ps, st, openH, medianVol, fp, positional);
      if (!tag) continue;
      const key = barTimeKey(bars[i].time);
      if (key) out.notes[key] = tag;
      latest = tag;
      latestIdx = i;
    }
    if (latest) {
      out.latest = latest;
      out.fresh = latestIdx >= bars.length - 3;
    }
    return out;
  }

  function footprintSkew(fp) {
    if (!fp || !fp.levels || !fp.levels.length) return null;
    let buy = 0, sell = 0;
    fp.levels.forEach(function (l) {
      buy += Number(l.buy) || 0;
      sell += Number(l.sell) || 0;
    });
    const tot = buy + sell;
    if (!(tot > 0)) return null;
    return { buy: buy, sell: sell, tot: tot, delta: buy - sell };
  }

  function impulseLesson(pattern, dump, extra) {
    extra = extra || {};
    const vol = extra.volMult;
    let volBit = "";
    if (vol >= 3) {
      volBit = " Объём примерно ×" + vol.toFixed(1) + " к обычной свече — сделок было много, не пустой скачок котировки.";
    } else if (vol > 0 && vol < 1.35) {
      volBit = " Объём почти как у обычной свечи, а ход большой — между ценами почти никого не было, цена проскочила пустоту. Это не «все знали новость».";
    } else if (vol >= 1.35) {
      volBit = " Объём выше среднего — ход подкреплён сделками.";
    }
    let tapeBit = "";
    const fp = extra.fp;
    if (fp) {
      if (fp.sell > fp.buy * 1.25) {
        tapeBit = " В ленте этой свечи больше продаж: продавали сразу «по любой цене», и очередь заявок на покупку съедалась уровень за уровнем.";
      } else if (fp.buy > fp.sell * 1.25) {
        tapeBit = " В ленте этой свечи больше покупок: покупали сразу «по любой цене», и очередь заявок на продажу съедалась уровень за уровнем.";
      } else {
        tapeBit = " В ленте покупки и продажи близки — скорее проскок пустых цен, чем одна сплошная толпа.";
      }
    }
    const positional = !!extra.positional;
    const sessionBit = extra.sessionOpen
      ? (positional
        ? " Первые минуты сессии: стакан тонкий, ход часто пустой — это не вход."
        : " Это первые минуты основной сессии 10:00 МСК: заявок ещё мало, ночной ход нефти выгружается в рынок. Не заголовок из ленты.")
      : "";
    let why;
    let wait;
    if (pattern === "STOP_HUNT" && extra.sweptLow) {
      why = "Сначала вынесли стопы под минимумом (продали туда, где почти не было покупателей), затем свеча закрылась выше — вынос не удержали."
        + volBit + tapeBit + sessionBit;
      wait = positional
        ? "Дёрнули вниз, часто чтобы потом идти вверх. Не шортить вынос. Позиционная не покупает от BOT Exclusive: сторона только с тренда H1, вход — в промежуточную полку объёма, не в середине выноса."
        : "Типично: дёрнули вниз, чтобы потом идти вверх. Не шортить вынос. Смотрим, удержит ли цена уровень над вынесенным лоем. Exclusive покупает только от BOT после закрытой свечи-отбоя — не в середине выноса.";
    } else if (pattern === "STOP_HUNT") {
      why = "Сначала вынесли стопы над максимумом (купили туда, где почти не было продавцов), затем свеча закрылась ниже — вынос хая не удержали."
        + volBit + tapeBit + sessionBit;
      wait = positional
        ? "Дёрнули вверх, часто чтобы потом идти вниз. Не ловить лонг на шипе. Шорт только если час вниз и цена в промежуточной полке — не от TOP Exclusive."
        : "Типично: дёрнули вверх, чтобы потом идти вниз. Не ловить лонг на шипе. Ждём, останется ли цена под вынесенным хаем. Exclusive шортит от TOP только после закрытого отбоя.";
    } else if (pattern === "KNIFE") {
      why = "Нож: продавали сразу по рынку. Заявки на покупку на каждом уровне исполнялись и исчезали — цена шла к следующей, более низкой. Закрытие у минимума: в этом баре покупатели так и не остановили падение."
        + volBit + tapeBit + sessionBit;
      wait = positional
        ? "Не ловить нож. Ждём остановку у полки объёма. Лонг только если час вверх и вход в промежуточную полку — не «дно» и не BOT Exclusive."
        : "Не ловить нож. Ждём остановку: сужение следующих свечей или касание полки/ZERO/BOT. Покупка у робота — только от зоны BOT после rejection, не «догонять дно».";
    } else if (pattern === "ROCKET") {
      why = "Импульс вверх: покупали сразу по рынку. Заявки на продажу на каждом уровне исполнялись и исчезали — цена шла к следующей, более высокой. Закрытие у максимума: продавцы ход не остановили."
        + volBit + tapeBit + sessionBit;
      wait = positional
        ? "Не догонять импульс. Шорт только если час вниз и зона входа — средняя полка объёма, не TOP дня Exclusive."
        : "Не догонять вверх. Для Exclusive шорт только от TOP после закрытого отбоя. Если нет зоны — ждём, не остановится ли ход на HI дня.";
    } else if (pattern === "SPIKE") {
      why = "Длинный фитиль и маленькое тело: цена пробежала пустые уровни и вернулась. Агрессия не закрепилась."
        + volBit + tapeBit + sessionBit;
      wait = positional
        ? "Шип сам по себе не вход. Ждём 1–2 закрытия. Позиционная торгует полку по тренду H1, не середину шипа и не TOP/BOT Exclusive."
        : "Шип сам по себе не вход. Ждём, с какой стороны закроются следующие 1–2 свечи. Ложный вынос часто возвращает цену в середину диапазона.";
    } else {
      why = (dump ? "Резкий ход вниз." : "Резкий ход вверх.") + volBit + tapeBit + sessionBit;
      wait = positional
        ? "Середину импульса не торгуем. Смотрим, где ход остановится относительно полок объёма и тренда на часе — без TOP/BOT/ZERO Exclusive."
        : "Не торговать середину импульса. Смотрим, где остановится относительно TOP/BOT/ZERO — и ждём реакцию, не прогноз заголовка.";
    }
    return {
      why: why.replace(/\s+/g, " ").trim(),
      wait: wait.replace(/\s+/g, " ").trim()
    };
  }

  function classifyImpulseBar(bar, medianRange, ps, st, openH, medianVol, fp, positional) {
    if (!bar) return null;
    const o = Number(bar.open), h = Number(bar.high), l = Number(bar.low), c = Number(bar.close);
    if (![o, h, l, c].every(Number.isFinite) || h < l) return null;
    const range = h - l;
    const pts = range / ps;
    const mult = medianRange > 1e-9 ? range / medianRange : pts;
    if (pts < 20 && mult < 2.8) return null;
    const body = Math.abs(c - o);
    const upper = h - Math.max(o, c);
    const lower = Math.min(o, c) - l;
    const dump = c < o;
    const bodyFrac = range > 0 ? body / range : 0;
    const hi = Number(st.lookbackHigh);
    const lo = Number(st.lookbackLow);
    const top = positional ? null : st.zoneTop;
    const bot = positional ? null : st.zoneBottom;
    const sweptHigh = (hi > 0 && h >= hi - ps)
      || (top && Number(top.low) > 0 && h >= Number(top.low) - ps);
    const sweptLow = (lo > 0 && l <= lo + ps)
      || (bot && Number(bot.high) > 0 && l <= Number(bot.high) + ps);
    const closeBackHigh = c < h - 0.45 * range;
    const closeBackLow = c > l + 0.45 * range;
    let pattern = "IMPULSE";
    let title = dump ? "Импульс вниз" : "Импульс вверх";
    if (sweptHigh && closeBackHigh && upper >= 0.35 * range) {
      pattern = "STOP_HUNT";
      title = "Сбор стопов сверху";
    } else if (sweptLow && closeBackLow && lower >= 0.35 * range) {
      pattern = "STOP_HUNT";
      title = "Сбор стопов снизу";
    } else if (bodyFrac >= 0.68) {
      pattern = dump ? "KNIFE" : "ROCKET";
      title = dump ? "Нож" : "Импульс вверх";
    } else if (Math.max(upper, lower) >= 0.5 * range && bodyFrac < 0.4) {
      pattern = "SPIKE";
      title = "Шип";
    }
    const hh = mskHourFromTime(bar.time);
    const mm = mskMinuteFromTime(bar.time);
    const sessionOpen = hh === openH && mm >= 0 && mm < 25;
    if (sessionOpen) {
      title += " · открытие сессии";
    }
    const vol = Number(bar.volume);
    const volMult = (medianVol > 0 && vol > 0) ? vol / medianVol : 0;
    const lesson = impulseLesson(pattern, dump, {
      volMult: volMult,
      fp: footprintSkew(fp),
      sessionOpen: sessionOpen,
      sweptHigh: sweptHigh,
      sweptLow: sweptLow,
      positional: !!positional
    });
    const signed = (dump ? "−" : "+") + Math.round(pts);
    const hover = lesson.why + " Что ждать: " + lesson.wait;
    return {
      pattern: pattern,
      title: title,
      hover: hover,
      why: lesson.why,
      wait: lesson.wait,
      direction: dump ? "DUMP" : "SPIKE",
      rangePoints: dump ? -Math.round(pts) : Math.round(pts),
      headline: title + " " + signed + "п",
      banner: lesson.why
    };
  }

  /**
   * TradingView-style OHLC: persistent top-left legend + optional note tip on hover.
   */
  function bindCandleOhlcTip(chart, series, hostEl, opts) {
    if (!chart || !series || !hostEl) {
      return { destroy: function () {}, setPointSize: function () {} };
    }
    let pointSize = (opts && opts.pointSize > 0) ? opts.pointSize : 0.01;
    const getBars = (opts && typeof opts.getBars === "function") ? opts.getBars : null;
    const barSecHint = (opts && opts.barSec > 0) ? opts.barSec : 3600;
    const maxSkew = Math.max(barSecHint * 2, 2 * 3600);
    const legendRoot = (opts && opts.legendEl) || null;
    const legendOhlc = legendRoot
      ? (legendRoot.querySelector(".signal-legend-ohlc") || legendRoot)
      : null;
    let impulseNotes = {};
    const tip = document.createElement("div");
    tip.className = "trinity-candle-ohlc-tip";
    tip.hidden = true;
    tip.setAttribute("role", "tooltip");
    hostEl.appendChild(tip);

    function asOhlcBar(bar) {
      if (!bar) return null;
      if (bar.open != null && bar.close != null) return bar;
      const v = bar.value != null ? Number(bar.value) : NaN;
      if (!isFinite(v)) return null;
      return { time: bar.time, open: v, high: v, low: v, close: v, value: v };
    }

    function lastBar() {
      if (!getBars) return null;
      const bars = getBars();
      return bars && bars.length ? asOhlcBar(bars[bars.length - 1]) : null;
    }

    function paintLegend(bar) {
      if (!legendOhlc || !bar || bar.open == null) return;
      const bull = Number(bar.close) >= Number(bar.open);
      const chg = Number(bar.close) - Number(bar.open);
      const sign = chg >= 0 ? "+" : "−";
      legendOhlc.classList.toggle("is-bull", bull);
      legendOhlc.classList.toggle("is-bear", !bull);
      legendOhlc.innerHTML = ""
        + "<i>O</i>" + fmtOhlcPx(bar.open, pointSize)
        + "<i>H</i>" + fmtOhlcPx(bar.high, pointSize)
        + "<i>L</i>" + fmtOhlcPx(bar.low, pointSize)
        + "<i>C</i><b>" + fmtOhlcPx(bar.close, pointSize) + "</b>"
        + '<em class="signal-legend-chg">' + sign + fmtOhlcPx(Math.abs(chg), pointSize) + "</em>";
    }

    function hide() {
      tip.hidden = true;
      paintLegend(lastBar());
    }

    function layoutTip(bar, x, y) {
      paintLegend(bar);
      const note = impulseNotes[barTimeKey(bar.time)]
        || impulseNotes[String(bar.time)]
        || null;
      const showFloatOhlc = !legendOhlc;
      const hasNote = !!(note && (note.why || note.hover));
      if (!showFloatOhlc && !hasNote) {
        tip.hidden = true;
        return;
      }
      const bull = Number(bar.close) >= Number(bar.open);
      tip.classList.toggle("is-bull", bull);
      tip.classList.toggle("is-bear", !bull);
      let html = "";
      if (showFloatOhlc) {
        html += ""
          + '<span class="trinity-ohlc-row"><b>O</b> ' + fmtOhlcPx(bar.open, pointSize) + "</span>"
          + '<span class="trinity-ohlc-row"><b>H</b> ' + fmtOhlcPx(bar.high, pointSize) + "</span>"
          + '<span class="trinity-ohlc-row"><b>L</b> ' + fmtOhlcPx(bar.low, pointSize) + "</span>"
          + '<span class="trinity-ohlc-row"><b>C</b> ' + fmtOhlcPx(bar.close, pointSize) + "</span>";
      }
      if (hasNote) {
        html += '<span class="trinity-ohlc-note">' + (note.why || note.hover) + "</span>";
        if (note.wait) {
          html += '<span class="trinity-ohlc-wait">Ждём: ' + note.wait + "</span>";
        }
      }
      tip.innerHTML = html;
      tip.hidden = false;
      const hostW = hostEl.clientWidth || 0;
      const hostH = hostEl.clientHeight || 0;
      const tipW = tip.offsetWidth || 72;
      const tipH = tip.offsetHeight || 68;
      let left = x + 12;
      let top = y - tipH * 0.55;
      if (left + tipW > hostW - 6) left = x - tipW - 12;
      if (top < 6) top = 6;
      if (top + tipH > hostH - 6) top = hostH - tipH - 6;
      tip.style.left = Math.round(left) + "px";
      tip.style.top = Math.round(top) + "px";
    }

    function barFromGetBars(time) {
      if (!getBars || time == null) return null;
      const bars = getBars();
      if (!bars || !bars.length) return null;
      let best = null;
      let bestD = Infinity;
      const want = Number(time);
      for (let i = 0; i < bars.length; i++) {
        const b = bars[i];
        if (!b) continue;
        const t = b.time != null ? b.time : b.t;
        if (t == null) continue;
        if (t === time || String(t) === String(time)) return b;
        const d = Math.abs(Number(t) - want);
        if (d < bestD) {
          bestD = d;
          best = b;
        }
      }
      return bestD <= maxSkew ? best : null;
    }

    const onCrosshair = function (param) {
      if (!param || !param.point || param.time == null) {
        hide();
        return;
      }
      let data = param.seriesData && typeof param.seriesData.get === "function"
        ? param.seriesData.get(series) : null;
      if (!data && param.seriesPrices && typeof param.seriesPrices.get === "function") {
        data = param.seriesPrices.get(series);
      }
      data = asOhlcBar(data) || asOhlcBar(barFromGetBars(param.time));
      if (!data || data.close == null) {
        hide();
        return;
      }
      let y = series.priceToCoordinate(data.close);
      if (y == null || !Number.isFinite(y)) y = param.point.y;
      if (data.time == null) data.time = param.time;
      layoutTip(data, param.point.x, y);
    };

    if (typeof chart.subscribeCrosshairMove === "function") {
      chart.subscribeCrosshairMove(onCrosshair);
    }
    paintLegend(lastBar());

    return {
      setPointSize: function (ps) {
        if (ps > 0) pointSize = ps;
        paintLegend(lastBar());
      },
      setImpulseNotes: function (notes) {
        impulseNotes = notes || {};
      },
      paintLast: function () {
        paintLegend(lastBar());
      },
      destroy: function () {
        hide();
        if (tip.parentNode) tip.parentNode.removeChild(tip);
      }
    };
  }

  /**
   * TradingView-like navigation on Lightweight Charts 3.8:
   * wheel zooms at cursor, Shift+wheel pans, Alt/axis wheel zooms price,
   * Shift+drag measures, double-click restores auto price.
   */
  function bindTradingViewNav(opts) {
    opts = opts || {};
    const chart = opts.chart;
    const series = opts.series;
    const host = opts.hostEl;
    if (!chart || !series || !host) {
      return { destroy: function () {}, syncGoLive: function () {} };
    }
    if (host._trinityTvNav) return host._trinityTvNav;
    const isDrawing = typeof opts.isDrawing === "function" ? opts.isDrawing : function () { return false; };
    const onTimeGesture = typeof opts.onTimeGesture === "function" ? opts.onTimeGesture : function () {};
    const onPriceLock = typeof opts.onPriceLock === "function" ? opts.onPriceLock : function () {};
    const onGoLive = typeof opts.onGoLive === "function" ? opts.onGoLive : function () {};
    const atRightEdge = typeof opts.atRightEdge === "function" ? opts.atRightEdge : function () { return true; };
    const onMeasureMode = typeof opts.onMeasureMode === "function" ? opts.onMeasureMode : function () {};
    const getPointSizeRaw = typeof opts.getPointSize === "function" ? opts.getPointSize : function () { return 0.01; };
    const getBarsNav = typeof opts.getBars === "function" ? opts.getBars : function () { return []; };
    function getPointSize() {
      return inferPointSize(getBarsNav() || [], getPointSizeRaw() || 0.01);
    }
    const barSec = opts.barSec > 0 ? opts.barSec : 300;
    const goLiveBtn = opts.goLiveBtn || null;
    const lockBtn = opts.lockBtn || null;
    if (goLiveBtn && goLiveBtn.parentNode !== host) host.appendChild(goLiveBtn);
    if (lockBtn && lockBtn.parentNode !== host) host.appendChild(lockBtn);

    try {
      chart.applyOptions({
        handleScroll: {
          mouseWheel: false,
          pressedMouseMove: true,
          horzTouchDrag: true,
          vertTouchDrag: false
        },
        handleScale: {
          axisPressedMouseMove: true,
          mouseWheel: false,
          pinch: true
        }
      });
    } catch (_) {}
    try {
      chart.applyOptions({ handleScale: { axisDoubleClickReset: true } });
    } catch (_) {}
    host.style.overscrollBehavior = "contain";

    const measure = document.createElement("div");
    measure.className = "trinity-measure-overlay";
    measure.hidden = true;
    measure.innerHTML = '<div class="trinity-measure-fill"></div>'
      + '<div class="trinity-measure-line"></div>'
      + '<div class="trinity-measure-dot is-a"></div>'
      + '<div class="trinity-measure-dot is-b"></div>'
      + '<div class="trinity-measure-box"></div>';
    host.appendChild(measure);
    const measureFill = measure.querySelector(".trinity-measure-fill");
    const measureLine = measure.querySelector(".trinity-measure-line");
    const measureDotA = measure.querySelector(".trinity-measure-dot.is-a");
    const measureDotB = measure.querySelector(".trinity-measure-dot.is-b");
    const measureBox = measure.querySelector(".trinity-measure-box");

    function metrics() {
      const w = host.clientWidth || 0;
      const h = host.clientHeight || 0;
      let priceW = 56;
      try {
        if (typeof chart.priceScale === "function") {
          priceW = chart.priceScale("right").width() || 56;
        }
      } catch (_) {}
      const timeH = 28;
      return {
        w: w,
        h: h,
        priceW: priceW,
        timeH: timeH,
        plotW: Math.max(1, w - priceW),
        plotH: Math.max(1, h - timeH)
      };
    }

    function localXY(ev) {
      const rect = host.getBoundingClientRect();
      return { x: ev.clientX - rect.left, y: ev.clientY - rect.top };
    }

    function zoomFactor(delta) {
      if (Math.abs(delta) >= 40) return delta < 0 ? 0.84 : 1.19;
      return Math.exp(Math.max(-90, Math.min(90, delta)) * 0.0042);
    }

    function zoomTimeAtX(x, factor) {
      const ts = chart.timeScale();
      let logical;
      try { logical = ts.getVisibleLogicalRange(); } catch (_) { return; }
      if (!logical) return;
      const m = metrics();
      const span = logical.to - logical.from;
      if (!(span > 0) || !(factor > 0)) return;
      const px = Math.max(0, Math.min(x, m.plotW));
      const anchor = logical.from + (px / m.plotW) * span;
      const newSpan = Math.max(6, Math.min(span * factor, 12000));
      const newFrom = anchor - (px / m.plotW) * newSpan;
      try {
        ts.setVisibleLogicalRange({ from: newFrom, to: newFrom + newSpan });
      } catch (_) {}
      onTimeGesture();
    }

    function panTimePx(dx) {
      const ts = chart.timeScale();
      let logical;
      try { logical = ts.getVisibleLogicalRange(); } catch (_) { return; }
      if (!logical) return;
      const m = metrics();
      const span = logical.to - logical.from;
      if (!(span > 0)) return;
      const d = (dx / m.plotW) * span;
      try {
        ts.setVisibleLogicalRange({ from: logical.from + d, to: logical.to + d });
      } catch (_) {}
      onTimeGesture();
    }

    function visiblePriceRange() {
      const m = metrics();
      let hi = null;
      let lo = null;
      try { hi = series.coordinateToPrice(0); } catch (_) {}
      try { lo = series.coordinateToPrice(m.plotH); } catch (_) {}
      if (hi == null || lo == null || !isFinite(hi) || !isFinite(lo)) return null;
      return { lo: Math.min(lo, hi), hi: Math.max(lo, hi) };
    }

    function zoomPriceAtY(y, factor) {
      const range = visiblePriceRange();
      if (!range) return;
      const m = metrics();
      const span = range.hi - range.lo;
      if (!(span > 0) || !(factor > 0)) return;
      const py = Math.max(0, Math.min(y, m.plotH));
      const anchor = range.hi - (py / m.plotH) * span;
      const newSpan = Math.max(span * factor, Math.abs(anchor) * 0.0004, 0.02);
      const newHi = anchor + (py / m.plotH) * newSpan;
      const newLo = newHi - newSpan;
      try {
        series.applyOptions({
          autoscaleInfoProvider: function () {
            return { priceRange: { minValue: newLo, maxValue: newHi } };
          }
        });
        series.priceScale().applyOptions({ autoScale: true });
      } catch (_) {}
      onPriceLock(true);
    }

    function syncGoLive() {
      if (!goLiveBtn) return;
      let hide = true;
      try { hide = !!atRightEdge(); } catch (_) {}
      goLiveBtn.hidden = hide;
    }

    let measureMode = false;
    let measureA = null;
    let measureB = null;
    let measureDragging = false;
    function priceAtY(y) {
      let p = null;
      try { p = series.coordinateToPrice(y); } catch (_) {}
      if (p != null && isFinite(p)) return p;
      const range = visiblePriceRange();
      const m = metrics();
      if (!range || !(m.plotH > 0)) return p;
      const t = Math.max(0, Math.min(1, y / m.plotH));
      return range.hi - t * (range.hi - range.lo);
    }

    function pointFromEv(ev) {
      const xy = localXY(ev);
      let t = null;
      try { t = chart.timeScale().coordinateToTime(xy.x); } catch (_) {}
      const p = priceAtY(xy.y);
      return { x: xy.x, y: xy.y, t: t, p: p };
    }

    function layoutMeasure(a, b) {
      if (!a || !b) {
        measure.hidden = true;
        return;
      }
      const x1 = Math.min(a.x, b.x);
      const y1 = Math.min(a.y, b.y);
      const x2 = Math.max(a.x, b.x);
      const y2 = Math.max(a.y, b.y);
      measure.hidden = false;
      measureFill.style.left = Math.round(x1) + "px";
      measureFill.style.top = Math.round(y1) + "px";
      measureFill.style.width = Math.max(1, Math.round(x2 - x1)) + "px";
      measureFill.style.height = Math.max(1, Math.round(y2 - y1)) + "px";
      const dx = b.x - a.x;
      const dy = b.y - a.y;
      const len = Math.hypot(dx, dy);
      const ang = Math.atan2(dy, dx) * (180 / Math.PI);
      measureLine.style.left = Math.round(a.x) + "px";
      measureLine.style.top = Math.round(a.y) + "px";
      measureLine.style.width = Math.max(1, Math.round(len)) + "px";
      measureLine.style.transform = "rotate(" + ang + "deg)";
      measureDotA.style.left = Math.round(a.x) + "px";
      measureDotA.style.top = Math.round(a.y) + "px";
      measureDotB.style.left = Math.round(b.x) + "px";
      measureDotB.style.top = Math.round(b.y) + "px";
      const pt = getPointSize() || 0.01;
      let bars = 0;
      if (a.t != null && b.t != null) {
        const dt = Math.abs(Number(b.t) - Number(a.t));
        bars = isFinite(dt) ? Math.max(1, Math.round(dt / barSec)) : 0;
      }
      let dPts = "";
      let dPct = "";
      if (a.p != null && b.p != null && isFinite(a.p) && isFinite(b.p)) {
        const dp = b.p - a.p;
        const sign = dp >= 0 ? "+" : "−";
        const ticks = Math.abs(dp) / (pt > 0 ? pt : 0.01);
        let ticksStr;
        if (ticks >= 9.5) ticksStr = ticks.toFixed(0);
        else if (ticks >= 0.05) ticksStr = ticks.toFixed(ticks >= 1 ? 1 : 2);
        else ticksStr = Math.abs(dp).toFixed(Math.max(2, priceDecimals(pt)));
        dPts = sign + ticksStr + " п";
        if (Math.abs(a.p) > 1e-9) {
          dPct = " · " + sign + (Math.abs(dp / a.p) * 100).toFixed(2) + "%";
        }
      }
      const mins = bars * (barSec / 60);
      let dur = "";
      if (mins >= 60) {
        const h = Math.floor(mins / 60);
        const m = Math.round(mins % 60);
        dur = " · " + h + "ч" + (m ? " " + m + "м" : "");
      } else if (mins > 0) {
        dur = " · " + Math.round(mins) + " мин";
      }
      measureBox.textContent = (bars ? bars + " св." + dur + " · " : "") + (dPts || "—") + dPct;
      const mx = (a.x + b.x) / 2;
      const my = Math.min(a.y, b.y) - 10;
      measureBox.style.left = Math.round(mx) + "px";
      measureBox.style.top = Math.round(Math.max(18, my)) + "px";
    }

    function clearMeasure() {
      measureA = null;
      measureB = null;
      measureDragging = false;
      measure.hidden = true;
    }

    function setMeasureMode(on) {
      measureMode = !!on;
      host.classList.toggle("is-measure-tool", measureMode);
      host.classList.toggle("is-drawing", measureMode || host.classList.contains("is-vap-tool")
        || host.classList.contains("is-trend-tool"));
      if (!measureMode) clearMeasure();
      onMeasureMode(measureMode);
    }

    function onWheel(ev) {
      if (!host.contains(ev.target) && ev.target !== host) return;
      const xy = localXY(ev);
      const m = metrics();
      const overPrice = xy.x >= m.plotW - 2;
      const overTime = xy.y >= m.h - m.timeH;
      const dx = ev.deltaX || 0;
      const dy = ev.deltaY || 0;
      ev.preventDefault();
      if (ev.shiftKey || overTime || Math.abs(dx) > Math.abs(dy) * 1.15) {
        panTimePx(Math.abs(dx) > Math.abs(dy) ? dx : dy);
        syncGoLive();
        return;
      }
      if (overPrice || ev.altKey) {
        zoomPriceAtY(xy.y, zoomFactor(dy));
        return;
      }
      zoomTimeAtX(xy.x, zoomFactor(dy));
      syncGoLive();
    }

    function onDblClick(ev) {
      if (measureMode) return;
      if (isDrawing()) return;
      if (ev.target && ev.target.closest && ev.target.closest(".signal-timeline-chip")) return;
      ev.preventDefault();
      clearMeasure();
      onPriceLock(false);
    }

    let priceAxisDown = false;
    function wantMeasure(ev) {
      return measureMode || (ev.shiftKey && !isDrawing());
    }
    function onPointerDown(ev) {
      if (ev.button !== 0) return;
      const xy = localXY(ev);
      const m = metrics();
      if (!measureMode && xy.x >= m.plotW - 2) {
        priceAxisDown = true;
        return;
      }
      if (!wantMeasure(ev)) return;
      if (measureA && measureB && !measureDragging) {
        measureA = pointFromEv(ev);
        measureB = measureA;
        measureDragging = true;
        layoutMeasure(measureA, measureB);
      } else if (measureA && !measureDragging) {
        measureB = pointFromEv(ev);
        layoutMeasure(measureA, measureB);
      } else {
        measureA = pointFromEv(ev);
        measureB = measureA;
        measureDragging = true;
        layoutMeasure(measureA, measureB);
      }
      try { host.setPointerCapture(ev.pointerId); } catch (_) {}
      ev.preventDefault();
      ev.stopPropagation();
    }
    function onPointerMove(ev) {
      if (measureDragging || (measureMode && measureA && !measureB)) {
        measureB = pointFromEv(ev);
        layoutMeasure(measureA, measureB);
        ev.preventDefault();
      }
    }
    function onPointerUp(ev) {
      if (priceAxisDown) {
        priceAxisDown = false;
        onPriceLock(true);
      }
      if (!measureDragging) return;
      const end = pointFromEv(ev);
      const moved = measureA
        ? Math.abs(end.x - measureA.x) + Math.abs(end.y - measureA.y)
        : 0;
      if (moved < 6 && measureMode) {
        measureDragging = false;
        measureB = null;
        layoutMeasure(measureA, measureA);
        return;
      }
      measureB = end;
      measureDragging = false;
      if (moved < 6 && !measureMode) {
        clearMeasure();
        return;
      }
      layoutMeasure(measureA, measureB);
    }

    function onKey(ev) {
      if (ev.key === "End") {
        onGoLive();
        syncGoLive();
        ev.preventDefault();
        return;
      }
      if (ev.key !== "Escape") return;
      if (measureMode) {
        setMeasureMode(false);
        ev.preventDefault();
        return;
      }
      if (!measure.hidden) clearMeasure();
    }

    function bindSlavePane(slaveEl, slaveChart) {
      if (!slaveEl || slaveEl._trinityTvSlave) return;
      slaveEl._trinityTvSlave = true;
      slaveEl.style.overscrollBehavior = "contain";
      slaveEl.addEventListener("wheel", function (ev) {
        ev.preventDefault();
        const rect = slaveEl.getBoundingClientRect();
        let priceW = 56;
        try {
          if (slaveChart && typeof slaveChart.priceScale === "function") {
            priceW = slaveChart.priceScale("right").width() || 56;
          }
        } catch (_) {}
        const slavePlotW = Math.max(1, (slaveEl.clientWidth || 1) - priceW);
        const localX = ev.clientX - rect.left;
        const m = metrics();
        const x = (localX / slavePlotW) * m.plotW;
        const dx = ev.deltaX || 0;
        const dy = ev.deltaY || 0;
        if (ev.shiftKey || Math.abs(dx) > Math.abs(dy) * 1.15) {
          panTimePx(Math.abs(dx) > Math.abs(dy) ? dx : dy);
        } else {
          zoomTimeAtX(x, zoomFactor(dy));
        }
        syncGoLive();
      }, { passive: false, capture: true });
    }

    try {
      chart.timeScale().subscribeVisibleLogicalRangeChange(function () { syncGoLive(); });
    } catch (_) {}

    host.addEventListener("wheel", onWheel, { passive: false, capture: true });
    host.addEventListener("dblclick", onDblClick);
    host.addEventListener("pointerdown", onPointerDown, true);
    host.addEventListener("pointermove", onPointerMove);
    host.addEventListener("pointerup", onPointerUp);
    host.addEventListener("pointercancel", onPointerUp);
    window.addEventListener("keydown", onKey);
    if (goLiveBtn) {
      goLiveBtn.addEventListener("click", function () { onGoLive(); syncGoLive(); });
    }
    if (lockBtn) {
      lockBtn.addEventListener("click", function () { onPriceLock(false); });
    }

    const api = {
      syncGoLive: syncGoLive,
      setMeasureMode: setMeasureMode,
      getMeasureMode: function () { return measureMode; },
      clearMeasure: clearMeasure,
      bindSlavePane: bindSlavePane,
      destroy: function () {
        host.removeEventListener("wheel", onWheel, { capture: true });
        host.removeEventListener("dblclick", onDblClick);
        host.removeEventListener("pointerdown", onPointerDown, true);
        host.removeEventListener("pointermove", onPointerMove);
        host.removeEventListener("pointerup", onPointerUp);
        host.removeEventListener("pointercancel", onPointerUp);
        window.removeEventListener("keydown", onKey);
        if (measure.parentNode) measure.parentNode.removeChild(measure);
        host._trinityTvNav = null;
      }
    };
    host._trinityTvNav = api;
    syncGoLive();
    return api;
  }

  function ensureNavHud(host, opts) {
    opts = opts || {};
    if (!host) return {};
    try {
      const cs = window.getComputedStyle(host);
      if (cs.position === "static") host.style.position = "relative";
    } catch (_) {
      host.style.position = "relative";
    }
    function findOrMake(sel, factory) {
      let el = host.querySelector(sel);
      if (!el) {
        el = factory();
        host.appendChild(el);
      }
      return el;
    }
    let legendEl = opts.legendEl || null;
    if (!legendEl && opts.legend !== false) {
      legendEl = findOrMake(".signal-chart-legend", function () {
        const d = document.createElement("div");
        d.className = "signal-chart-legend";
        d.innerHTML = '<span class="signal-legend-ohlc"></span>';
        return d;
      });
    }
    let lockBtn = opts.lockBtn || null;
    if (!lockBtn && opts.lock !== false) {
      lockBtn = findOrMake(".signal-chart-lock-btn", function () {
        const b = document.createElement("button");
        b.type = "button";
        b.className = "signal-chart-lock-btn";
        b.hidden = true;
        b.title = "Цена закреплена — двойной клик вернёт автомасштаб";
        b.setAttribute("aria-pressed", "false");
        b.setAttribute("aria-label", "Сбросить масштаб цены");
        b.textContent = "🔒";
        return b;
      });
    }
    let goLiveBtn = opts.goLiveBtn || null;
    if (!goLiveBtn && opts.goLive !== false) {
      goLiveBtn = findOrMake(".signal-chart-live-btn", function () {
        const b = document.createElement("button");
        b.type = "button";
        b.className = "signal-chart-live-btn";
        b.hidden = true;
        b.title = "К последней свече (End)";
        b.setAttribute("aria-label", "К последней свече");
        b.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12h11M12 6l8 6-8 6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
        return b;
      });
    }
    let measureBtn = opts.measureBtn || null;
    if (!measureBtn && opts.measure !== false) {
      measureBtn = findOrMake(".signal-chart-measure-btn", function () {
        const b = document.createElement("button");
        b.type = "button";
        b.className = "signal-chart-measure-btn";
        b.title = "Линейка: клик или Shift+тяни. Esc — сброс.";
        b.setAttribute("aria-pressed", "false");
        b.setAttribute("aria-label", "Линейка");
        b.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 20L20 4M7.5 16.5l1.2 1.2M10.5 13.5l1.2 1.2M13.5 10.5l1.2 1.2M16.5 7.5l1.2 1.2" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>';
        return b;
      });
      host.classList.add("trinity-has-measure");
    }
    return { legendEl: legendEl, lockBtn: lockBtn, goLiveBtn: goLiveBtn, measureBtn: measureBtn };
  }

  function defaultAtRightEdge(chart, series) {
    return function () {
      try {
        const logical = chart.timeScale().getVisibleLogicalRange();
        if (!logical) return true;
        if (series && typeof series.barsInLogicalRange === "function") {
          const info = series.barsInLogicalRange(logical);
          if (info && typeof info.barsAfter === "number") return info.barsAfter < 2.5;
        }
      } catch (_) {}
      return true;
    };
  }

  /**
   * One-call HUD + TradingView-like nav for any Lightweight Charts pane
   * (candles or line). Drawing desks should pass skipOhlcTip if attachTools
   * already owns the legend.
   */
  function attachFriendlyNav(opts) {
    opts = opts || {};
    const chart = opts.chart;
    const series = opts.series;
    const host = opts.hostEl;
    if (!chart || !series || !host) {
      return { destroy: function () {}, syncGoLive: function () {} };
    }
    if (host._trinityFriendlyNav) return host._trinityFriendlyNav;
    const hud = ensureNavHud(host, opts);
    const barSec = opts.barSec > 0 ? opts.barSec : 300;
    const getBars = typeof opts.getBars === "function" ? opts.getBars : null;
    const userOnMeasure = typeof opts.onMeasureMode === "function" ? opts.onMeasureMode : function () {};
    const userOnPriceLock = typeof opts.onPriceLock === "function" ? opts.onPriceLock : null;
    const userOnGoLive = typeof opts.onGoLive === "function" ? opts.onGoLive : null;
    let ohlcTip = null;
    if (opts.skipOhlcTip !== true) {
      ohlcTip = bindCandleOhlcTip(chart, series, host, {
        pointSize: opts.pointSize > 0 ? opts.pointSize : 0.01,
        getBars: getBars,
        legendEl: hud.legendEl,
        barSec: barSec
      });
    }
    const nav = bindTradingViewNav({
      chart: chart,
      series: series,
      hostEl: host,
      goLiveBtn: hud.goLiveBtn,
      lockBtn: hud.lockBtn,
      barSec: barSec,
      isDrawing: opts.isDrawing,
      atRightEdge: typeof opts.atRightEdge === "function"
        ? opts.atRightEdge
        : defaultAtRightEdge(chart, series),
      getPointSize: typeof opts.getPointSize === "function"
        ? opts.getPointSize
        : function () { return opts.pointSize > 0 ? opts.pointSize : 0.01; },
      getBars: getBars,
      onTimeGesture: opts.onTimeGesture,
      onMeasureMode: function (on) {
        if (hud.measureBtn) {
          hud.measureBtn.classList.toggle("is-on", !!on);
          hud.measureBtn.setAttribute("aria-pressed", on ? "true" : "false");
        }
        userOnMeasure(on);
      },
      onPriceLock: function (locked) {
        if (hud.lockBtn) {
          hud.lockBtn.hidden = !locked;
          hud.lockBtn.classList.toggle("is-on", !!locked);
          hud.lockBtn.setAttribute("aria-pressed", locked ? "true" : "false");
        }
        try {
          if (!locked) {
            series.applyOptions({ autoscaleInfoProvider: undefined });
            series.priceScale().applyOptions({ autoScale: true });
          } else {
            series.priceScale().applyOptions({ autoScale: false });
          }
        } catch (_) {}
        if (userOnPriceLock) userOnPriceLock(locked);
      },
      onGoLive: function () {
        if (userOnGoLive) {
          userOnGoLive();
        } else {
          try { chart.timeScale().scrollToRealTime(); } catch (_) {}
        }
      }
    });
    if (hud.measureBtn && nav && typeof nav.setMeasureMode === "function") {
      hud.measureBtn.addEventListener("click", function (ev) {
        ev.preventDefault();
        ev.stopPropagation();
        nav.setMeasureMode(!nav.getMeasureMode());
      });
    }
    const api = {
      nav: nav,
      hud: hud,
      ohlcTip: ohlcTip,
      syncGoLive: function () { return nav.syncGoLive(); },
      setMeasureMode: function (on) { return nav.setMeasureMode(on); },
      getMeasureMode: function () { return nav.getMeasureMode(); },
      clearMeasure: function () { return nav.clearMeasure(); },
      bindSlavePane: function (el, ch) { return nav.bindSlavePane(el, ch); },
      destroy: function () {
        if (ohlcTip && typeof ohlcTip.destroy === "function") ohlcTip.destroy();
        if (nav && typeof nav.destroy === "function") nav.destroy();
        host._trinityFriendlyNav = null;
      }
    };
    host._trinityFriendlyNav = api;
    return api;
  }

  /**
   * Attach drawing tools to a Lightweight Charts instance.
   * @returns controller with getState/setState/destroy
   */
  function attachTools(opts) {
    const chart = opts.chart;
    const series = opts.candleSeries;
    const host = opts.hostEl;
    const getBars = opts.getBars || function () { return []; };
    let pointSize = opts.pointSize > 0 ? opts.pointSize : 0.01;
    const onChange = opts.onChange || function () {};
    const LC = global.LightweightCharts;
    const ohlcTip = bindCandleOhlcTip(chart, series, host, {
      pointSize: pointSize,
      getBars: getBars,
      legendEl: opts.legendEl || null,
      barSec: opts.barSec > 0 ? opts.barSec : 300
    });

    function effectivePointSize() {
      return inferPointSize(getBars() || [], pointSize);
    }

    let mode = null; // vap | trend | ray | hline | vline | rect | fib | null
    let vapAnchor = null; // time of first candle while drawing
    let vapHover = null; // time under cursor while drawing
    let vapSelected = false; // range edges editable
    let trendAnchor = null; // {t,p} first point while drawing
    let trendHover = null; // {t,p} rubber-band end
    let stretchedLevels = [];
    let stretchedBands = [];
    let vapFrom = null;
    let vapTo = null;
    let trendLines = []; // {id,t1,p1,t2,p2,kind:'trend'|'ray'}
    let marks = []; // {id,kind:'hline'|'vline'|'rect'|'fib',t1,p1,t2,p2}
    let selectedTrendId = null;
    let selectedMarkId = null;
    let selectedMaId = null;
    let dragState = null; // {kind:'trend'|'vap'|'mark', ...}
    let lastPtrDownAt = 0;
    let lastPtrX = 0;
    let lastPtrY = 0;
    let mas = []; // {id,type,period,color,series}
    let showStretched = true;
    let magnetOn = false;
    let drawingsHidden = false;
    let drawingsLocked = false;
    let drawLayer = null;
    const FIB_LEVELS = [0, 0.236, 0.382, 0.5, 0.618, 0.786, 1];
    const TWO_POINT_MODES = { trend: 1, ray: 1, rect: 1, fib: 1 };

    function ensureOverlay(id, cls) {
      let ov = host.querySelector("#" + id);
      if (!ov) {
        ov = document.createElement("div");
        ov.id = id;
        ov.className = cls;
        host.appendChild(ov);
      }
      return ov;
    }

    let toolLayoutRaf = 0;
    function scheduleToolLayout() {
      if (toolLayoutRaf) return;
      toolLayoutRaf = requestAnimationFrame(function () {
        toolLayoutRaf = 0;
        layoutStretchedVapNow();
        layoutTrendLinesNow();
      });
    }
    function layoutStretchedVap() {
      scheduleToolLayout();
    }
    function layoutTrendLines() {
      scheduleToolLayout();
    }
    function layoutStretchedVapNow() {
      const ov = ensureOverlay(opts.overlayId || "chart-vap-stretch", "signal-profile-overlay chart-vap-stretch");
      const bandOv = ensureOverlay((opts.overlayId || "chart-vap-stretch") + "-bands", "chart-vap-bands");
      ov.innerHTML = "";
      bandOv.innerHTML = "";
      if (drawingsHidden || !showStretched || !series) return;
      stretchedBands.forEach(function (b) {
        const y1 = series.priceToCoordinate(b.high);
        const y2 = series.priceToCoordinate(b.low);
        if (y1 == null || y2 == null) return;
        const el = document.createElement("div");
        el.className = "chart-vap-band";
        el.style.top = Math.min(y1, y2) + "px";
        el.style.height = Math.max(0.5, Math.abs(y2 - y1)) + "px";
        el.title = Number(b.low).toFixed(4) + "–" + Number(b.high).toFixed(4);
        bandOv.appendChild(el);
      });
      const maxW = 90;
      stretchedLevels.slice(0, 128).forEach(function (lvl) {
        if (!(lvl.volume > 0)) return;
        const y = series.priceToCoordinate(lvl.price);
        if (y == null) return;
        const bar = document.createElement("div");
        bar.className = "signal-vap-bar is-stretch";
        bar.style.top = (y - 1) + "px";
        bar.style.width = Math.max(2, Math.round((lvl.strength || 0) * maxW)) + "px";
        bar.title = Number(lvl.price).toFixed(4) + " · vol " + Math.round(lvl.volume);
        ov.appendChild(bar);
      });
    }

    function timeKey(t) {
      return t == null ? "" : String(t);
    }

    function findBarIndex(bars, time) {
      if (!bars || !bars.length || time == null) return -1;
      let exact = -1;
      let best = -1;
      let bestD = Infinity;
      const want = Number(time);
      for (let i = 0; i < bars.length; i++) {
        const t = bars[i].time != null ? bars[i].time : bars[i].t;
        if (t === time || timeKey(t) === timeKey(time)) {
          exact = i;
          break;
        }
        const d = Math.abs(Number(t) - want);
        if (isFinite(d) && d < bestD) {
          bestD = d;
          best = i;
        }
      }
      if (exact >= 0) return exact;
      // Allow nearest bar within ~2 hours (covers H1 snap / timezone noise).
      return bestD <= 2 * 3600 ? best : -1;
    }

    function barTimeAt(bars, idx) {
      if (!bars || idx < 0 || idx >= bars.length) return null;
      const b = bars[idx];
      return b.time != null ? b.time : b.t;
    }

    function applyVapRange(fromTime, toTime, opts) {
      const bars = getBars() || [];
      let i0 = findBarIndex(bars, fromTime);
      let i1 = findBarIndex(bars, toTime);
      if (i0 < 0 || i1 < 0) return false;
      if (i0 > i1) {
        const tmp = i0; i0 = i1; i1 = tmp;
      }
      vapFrom = barTimeAt(bars, i0);
      vapTo = barTimeAt(bars, i1);
      stretchedLevels = vapFromBars(bars, i0, i1, effectivePointSize());
      stretchedBands = hvnBands(stretchedLevels, 0.55);
      showStretched = true;
      vapSelected = true;
      vapAnchor = null;
      vapHover = null;
      layoutStretchedVap();
      layoutTrendLines();
      if (!(opts && opts.silent)) onChange();
      return true;
    }

    function clearVap() {
      vapFrom = vapTo = null;
      stretchedLevels = [];
      stretchedBands = [];
      vapAnchor = null;
      vapHover = null;
      vapSelected = false;
      layoutStretchedVap();
      layoutTrendLines();
      onChange();
    }

    function vapRangeXs(fromTime, toTime) {
      if (fromTime == null || toTime == null || !chart) return null;
      let x1 = null;
      let x2 = null;
      try {
        x1 = chart.timeScale().timeToCoordinate(fromTime);
        x2 = chart.timeScale().timeToCoordinate(toTime);
      } catch (_) {}
      if (x1 == null || x2 == null || !isFinite(x1) || !isFinite(x2)) return null;
      // Approximate bar width so the highlight covers the candle bodies.
      let barW = 8;
      try {
        const spacing = chart.timeScale().options().barSpacing;
        if (spacing > 0) barW = spacing;
      } catch (_) {}
      const left = Math.min(x1, x2) - barW * 0.35;
      const right = Math.max(x1, x2) + barW * 0.55;
      return { left: left, right: right, x1: x1, x2: x2 };
    }

    function vapHandleHit(x, y) {
      if (!vapSelected || vapFrom == null || vapTo == null) return null;
      const xs = vapRangeXs(vapFrom, vapTo);
      if (!xs) return null;
      const h = host.clientHeight || 0;
      if (y < 0 || y > h) return null;
      if (Math.abs(x - xs.left) <= 8) return "from";
      if (Math.abs(x - xs.right) <= 8) return "to";
      return null;
    }

    function ensureDrawLayer() {
      if (drawLayer && drawLayer.parentNode) return drawLayer;
      drawLayer = host.querySelector(".chart-draw-layer");
      if (!drawLayer) {
        drawLayer = document.createElement("div");
        drawLayer.className = "chart-draw-layer";
        host.appendChild(drawLayer);
      }
      return drawLayer;
    }

    function xyFromPoint(t, p) {
      if (t == null || p == null || !series || !chart) return null;
      let x = null;
      try { x = chart.timeScale().timeToCoordinate(t); } catch (_) {}
      const y = series.priceToCoordinate(p);
      if (x == null || y == null || !isFinite(x) || !isFinite(y)) return null;
      return { x: x, y: y };
    }

    function pointFromClient(clientX, clientY) {
      if (!series || !chart || !host) return null;
      const rect = host.getBoundingClientRect();
      const x = clientX - rect.left;
      const y = clientY - rect.top;
      let t = null;
      try { t = chart.timeScale().coordinateToTime(x); } catch (_) {}
      if (t == null) t = timeAtX(x);
      let p = null;
      try { p = series.coordinateToPrice(y); } catch (_) {}
      return { t: t, p: p, x: x, y: y };
    }

    function levelHandleHit(mk, x, y) {
      if (!mk) return null;
      const sz = plotSize();
      if (mk.kind === "hline") {
        const yy = series.priceToCoordinate(mk.p1);
        if (yy == null) return null;
        const hx2 = Math.max(36, sz.w - 72);
        if (Math.hypot(x - 16, y - yy) <= 16) return "move";
        if (Math.hypot(x - hx2, y - yy) <= 16) return "move";
        if (Math.abs(y - yy) <= 12) return "move";
        return null;
      }
      if (mk.kind === "vline") {
        let xx = null;
        try { xx = chart.timeScale().timeToCoordinate(mk.t1); } catch (_) {}
        if (xx == null) return null;
        const hy2 = Math.max(36, sz.h - 36);
        if (Math.hypot(x - xx, y - 16) <= 16) return "move";
        if (Math.hypot(x - xx, y - hy2) <= 16) return "move";
        if (Math.abs(x - xx) <= 12) return "move";
        return null;
      }
      return null;
    }

    function pointFromChartParam(param) {
      if (!param || param.point == null || !series) return null;
      const p = series.coordinateToPrice(param.point.y);
      if (p == null || !isFinite(p)) return null;
      let t = param.time;
      if (t == null && chart) {
        try { t = chart.timeScale().coordinateToTime(param.point.x); } catch (_) {}
      }
      if (t == null) return null;
      return { t: t, p: p };
    }

    function findTrendNear(x, y, maxDist) {
      const lim = maxDist == null ? 8 : maxDist;
      let best = null;
      let bestD = lim;
      trendLines.forEach(function (tl) {
        const a = xyFromPoint(tl.t1, tl.p1);
        const b = xyFromPoint(tl.t2, tl.p2);
        if (!a || !b) return;
        const d = tl.kind === "ray"
          ? distToRay(x, y, a.x, a.y, b.x, b.y)
          : distToSegment(x, y, a.x, a.y, b.x, b.y);
        if (d <= bestD) {
          bestD = d;
          best = tl;
        }
      });
      return best;
    }

    function distToSegment(px, py, x1, y1, x2, y2) {
      const dx = x2 - x1;
      const dy = y2 - y1;
      const len2 = dx * dx + dy * dy;
      if (len2 < 1e-9) return Math.hypot(px - x1, py - y1);
      let t = ((px - x1) * dx + (py - y1) * dy) / len2;
      t = Math.max(0, Math.min(1, t));
      return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
    }

    function handleHit(tl, x, y) {
      const a = xyFromPoint(tl.t1, tl.p1);
      const b = xyFromPoint(tl.t2, tl.p2);
      if (a && Math.hypot(x - a.x, y - a.y) <= 10) return "a";
      if (b && Math.hypot(x - b.x, y - b.y) <= 10) return "b";
      return null;
    }

    function snapPt(pt) {
      if (!pt) return null;
      const t = snapTime(pt.t);
      let p = pt.p;
      if (magnetOn) {
        const bars = getBars() || [];
        const ix = findBarIndex(bars, t);
        if (ix >= 0) {
          const b = bars[ix];
          const cands = [b.open, b.high, b.low, b.close].map(Number).filter(isFinite);
          let best = p;
          let bestD = Infinity;
          for (let i = 0; i < cands.length; i++) {
            const d = Math.abs(cands[i] - p);
            if (d < bestD) {
              bestD = d;
              best = cands[i];
            }
          }
          p = best;
        }
      }
      return { t: t, p: p };
    }

    function plotSize() {
      return { w: host.clientWidth || 0, h: host.clientHeight || 0 };
    }

    function addMark(kind, t1, p1, t2, p2, opts) {
      const id = (opts && opts.id) || ("mk-" + Date.now() + "-" + Math.floor(Math.random() * 1e4));
      const mk = { id: id, kind: kind, t1: t1, p1: p1, t2: t2, p2: p2 };
      marks.push(mk);
      selectedMarkId = id;
      selectedTrendId = null;
      selectedMaId = null;
      vapSelected = false;
      applyMaHighlight();
      layoutTrendLines();
      if (!(opts && opts.silent)) onChange();
      return mk;
    }

    function removeMark(id) {
      const ix = marks.findIndex(function (x) { return x.id === id; });
      if (ix < 0) return;
      marks.splice(ix, 1);
      if (selectedMarkId === id) selectedMarkId = null;
      layoutTrendLines();
      onChange();
    }

    function clearMarks() {
      marks = [];
      selectedMarkId = null;
      layoutTrendLines();
      onChange();
    }

    function distToRay(px, py, x1, y1, x2, y2) {
      const dx = x2 - x1;
      const dy = y2 - y1;
      const len2 = dx * dx + dy * dy;
      if (len2 < 1e-9) return Math.hypot(px - x1, py - y1);
      let t = ((px - x1) * dx + (py - y1) * dy) / len2;
      t = Math.max(0, t);
      return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
    }

    function findMarkNear(x, y, maxDist) {
      const lim = maxDist == null ? 14 : maxDist;
      let best = null;
      let bestD = lim;
      const sz = plotSize();
      marks.forEach(function (mk) {
        let d = Infinity;
        if (mk.kind === "hline") {
          const y0 = series.priceToCoordinate(mk.p1);
          if (y0 != null) d = Math.abs(y - y0);
        } else if (mk.kind === "vline") {
          let x0 = null;
          try { x0 = chart.timeScale().timeToCoordinate(mk.t1); } catch (_) {}
          if (x0 != null) d = Math.abs(x - x0);
        } else if (mk.kind === "rect") {
          const a = xyFromPoint(mk.t1, mk.p1);
          const b = xyFromPoint(mk.t2, mk.p2);
          if (a && b) {
            const x0 = Math.min(a.x, b.x);
            const x1 = Math.max(a.x, b.x);
            const y0 = Math.min(a.y, b.y);
            const y1 = Math.max(a.y, b.y);
            const dx = x < x0 ? x0 - x : x > x1 ? x - x1 : 0;
            const dy = y < y0 ? y0 - y : y > y1 ? y - y1 : 0;
            d = (dx === 0 && dy === 0)
              ? Math.min(x - x0, x1 - x, y - y0, y1 - y)
              : Math.hypot(dx, dy);
          }
        } else if (mk.kind === "fib") {
          const a = xyFromPoint(mk.t1, mk.p1);
          const b = xyFromPoint(mk.t2, mk.p2);
          if (a && b) {
            FIB_LEVELS.forEach(function (lv) {
              const p = mk.p1 + (mk.p2 - mk.p1) * lv;
              const yy = series.priceToCoordinate(p);
              if (yy == null) return;
              const dd = Math.abs(y - yy);
              if (dd < d) d = dd;
            });
          }
        }
        if (d <= bestD) {
          bestD = d;
          best = mk;
        }
      });
      if (best) best._hitD = bestD;
      return best;
    }

    function timeAtX(x) {
      let t = null;
      try { t = chart.timeScale().coordinateToTime(x); } catch (_) {}
      if (t != null) return t;
      try {
        const lr = chart.timeScale().getVisibleLogicalRange();
        const bars = getBars() || [];
        if (!lr || !bars.length) return null;
        let priceW = 56;
        try { priceW = chart.priceScale("right").width() || 56; } catch (_) {}
        const plotW = Math.max(1, (host.clientWidth || 1) - priceW);
        const frac = Math.max(0, Math.min(1, x / plotW));
        const logical = lr.from + frac * (lr.to - lr.from);
        const ix = Math.max(0, Math.min(bars.length - 1, Math.round(logical)));
        const b = bars[ix];
        return b && (b.time != null ? b.time : b.t);
      } catch (_) {}
      return null;
    }

    function priceToY(src, price) {
      if (price == null || !isFinite(price)) return null;
      let y = null;
      if (src && typeof src.priceToCoordinate === "function") {
        try { y = src.priceToCoordinate(price); } catch (_) {}
      }
      if (y == null && series && typeof series.priceToCoordinate === "function") {
        try { y = series.priceToCoordinate(price); } catch (_) {}
      }
      return y;
    }

    function maValueAt(m, time) {
      const rows = (m && m.data) || [];
      if (!rows.length || time == null) return null;
      let nearest = rows[0];
      let bestD = Math.abs(Number(rows[0].time) - Number(time));
      for (let i = 1; i < rows.length; i++) {
        const d = Math.abs(Number(rows[i].time) - Number(time));
        if (d < bestD) {
          bestD = d;
          nearest = rows[i];
        }
      }
      return nearest && isFinite(nearest.value) ? nearest.value : null;
    }

    function findMaNear(x, y, maxDist) {
      const lim = maxDist == null ? 14 : maxDist;
      const t = timeAtX(x);
      if (t == null) return null;
      let best = null;
      let bestD = lim;
      mas.forEach(function (m) {
        const val = maValueAt(m, t);
        if (val == null) return;
        const yy = priceToY(m.series, val);
        if (yy == null) return;
        const d = Math.abs(y - yy);
        if (d <= bestD) {
          bestD = d;
          best = m;
        }
      });
      if (best) best._hitD = bestD;
      return best;
    }

    function nearestSelectable(x, y, maxDist) {
      const lim = maxDist == null ? 14 : maxDist;
      const tl = findTrendNear(x, y, lim);
      const mk = findMarkNear(x, y, lim);
      const ma = findMaNear(x, y, lim);
      const cands = [];
      if (tl) {
        const a = xyFromPoint(tl.t1, tl.p1);
        const b = xyFromPoint(tl.t2, tl.p2);
        let d = lim;
        if (a && b) {
          d = tl.kind === "ray"
            ? distToRay(x, y, a.x, a.y, b.x, b.y)
            : distToSegment(x, y, a.x, a.y, b.x, b.y);
        }
        cands.push({ kind: "trend", id: tl.id, d: d });
      }
      if (mk) cands.push({ kind: "mark", id: mk.id, d: mk._hitD != null ? mk._hitD : lim });
      if (ma) cands.push({ kind: "ma", id: ma.id, d: ma._hitD != null ? ma._hitD : lim });
      cands.sort(function (a, b) { return a.d - b.d; });
      return cands.length ? { kind: cands[0].kind, id: cands[0].id } : null;
    }

    function applyMaHighlight() {
      mas.forEach(function (m) {
        if (!m || !m.series) return;
        const on = m.id === selectedMaId;
        try {
          m.series.applyOptions({
            lineWidth: on ? 3 : 2,
            lastValueVisible: true
          });
        } catch (_) {}
      });
    }

    function selectObject(hit) {
      selectedTrendId = null;
      selectedMarkId = null;
      selectedMaId = null;
      if (!hit || hit.kind !== "vap") vapSelected = false;
      if (hit) {
        if (hit.kind === "trend") selectedTrendId = hit.id;
        else if (hit.kind === "mark") selectedMarkId = hit.id;
        else if (hit.kind === "ma") selectedMaId = hit.id;
        else if (hit.kind === "vap") vapSelected = true;
      }
      applyMaHighlight();
      layoutTrendLines();
    }

    function layoutTrendLinesNow() {
      const layer = ensureDrawLayer();
      const w = host.clientWidth || 0;
      const h = host.clientHeight || 0;
      let svg = "";
      svg += '<svg class="chart-draw-svg" width="' + w + '" height="' + h + '" viewBox="0 0 ' + w + " " + h + '">';

      // VAP stretch range (preview or committed)
      if (!(drawingsHidden && !vapAnchor)) {
      let vapA = vapAnchor;
      let vapB = vapHover;
      if (vapA == null && vapFrom != null && vapTo != null) {
        vapA = vapFrom;
        vapB = vapTo;
      }
      if (vapA != null && vapB != null) {
        const xs = vapRangeXs(vapA, vapB);
        if (xs) {
          const preview = !!vapAnchor;
          const left = xs.left;
          const width = Math.max(2, xs.right - xs.left);
          svg += '<rect class="chart-vap-range' + (preview ? " is-preview" : "")
            + (vapSelected && !preview ? " is-selected" : "")
            + '" x="' + left + '" y="0" width="' + width + '" height="' + h + '" />';
          if (vapSelected && !preview) {
            svg += '<line class="chart-vap-edge" x1="' + xs.left + '" y1="0" x2="' + xs.left + '" y2="' + h + '" />';
            svg += '<line class="chart-vap-edge" x1="' + xs.right + '" y1="0" x2="' + xs.right + '" y2="' + h + '" />';
            svg += '<rect class="chart-vap-handle" data-end="from" x="' + (xs.left - 4) + '" y="' + (h / 2 - 14)
              + '" width="8" height="28" rx="2" />';
            svg += '<rect class="chart-vap-handle" data-end="to" x="' + (xs.right - 4) + '" y="' + (h / 2 - 14)
              + '" width="8" height="28" rx="2" />';
          } else if (preview) {
            const ax = chart.timeScale().timeToCoordinate(vapA);
            if (ax != null) {
              svg += '<line class="chart-vap-edge is-preview" x1="' + ax + '" y1="0" x2="' + ax + '" y2="' + h + '" />';
            }
          }
        }
      } else if (vapAnchor != null) {
        try {
          const ax = chart.timeScale().timeToCoordinate(vapAnchor);
          if (ax != null) {
            svg += '<line class="chart-vap-edge is-preview" x1="' + ax + '" y1="0" x2="' + ax + '" y2="' + h + '" />';
          }
        } catch (_) {}
      }
      }

      function paintLine(tl, cls, soft) {
        const a = xyFromPoint(tl.t1, tl.p1);
        const b = xyFromPoint(tl.t2, tl.p2);
        if (!a || !b) return "";
        const sz = plotSize();
        let x1;
        let y1;
        let x2;
        let y2;
        if (tl.kind === "ray") {
          const dx = b.x - a.x;
          const dy = b.y - a.y;
          const len = Math.hypot(dx, dy) || 1;
          const far = Math.max(sz.w, sz.h) * 4;
          x1 = a.x;
          y1 = a.y;
          x2 = a.x + (dx / len) * far;
          y2 = a.y + (dy / len) * far;
        } else {
          const dx = b.x - a.x;
          const dy = b.y - a.y;
          const len = Math.hypot(dx, dy) || 1;
          const pad = Math.max(12, Math.min(40, len * 0.08));
          const ux = dx / len;
          const uy = dy / len;
          x1 = a.x - ux * pad;
          y1 = a.y - uy * pad;
          x2 = b.x + ux * pad;
          y2 = b.y + uy * pad;
        }
        let out = '<line class="' + cls + (soft ? " is-preview" : "") + '" data-id="' + esc(tl.id || "")
          + '" x1="' + x1 + '" y1="' + y1 + '" x2="' + x2 + '" y2="' + y2 + '" />';
        if (!soft && tl.id && tl.id === selectedTrendId) {
          out += '<circle class="chart-draw-handle" data-id="' + esc(tl.id) + '" data-end="a" cx="'
            + a.x + '" cy="' + a.y + '" r="5" />';
          out += '<circle class="chart-draw-handle" data-id="' + esc(tl.id) + '" data-end="b" cx="'
            + b.x + '" cy="' + b.y + '" r="5" />';
        }
        return out;
      }
      function paintHandles(a, b, id) {
        if (!a || !b || id !== selectedMarkId) return "";
        return '<circle class="chart-draw-handle" data-mid="' + esc(id) + '" data-end="a" cx="'
          + a.x + '" cy="' + a.y + '" r="5" />'
          + '<circle class="chart-draw-handle" data-mid="' + esc(id) + '" data-end="b" cx="'
          + b.x + '" cy="' + b.y + '" r="5" />';
      }
      function paintMark(mk, soft) {
        const sel = !soft && mk.id === selectedMarkId;
        const sz = plotSize();
        if (mk.kind === "hline") {
          const y = series.priceToCoordinate(mk.p1);
          if (y == null) return "";
          let out = '<line class="chart-draw-hline' + (sel ? " is-selected" : "") + (soft ? " is-preview" : "")
            + '" x1="0" y1="' + y + '" x2="' + sz.w + '" y2="' + y + '" />';
          if (sel) {
            const hx2 = Math.max(36, sz.w - 72);
            out += '<circle class="chart-draw-handle is-level-h" data-mid="' + esc(mk.id) + '" data-end="move" cx="16" cy="'
              + y + '" r="6" />';
            out += '<circle class="chart-draw-handle is-level-h" data-mid="' + esc(mk.id) + '" data-end="move" cx="'
              + hx2 + '" cy="' + y + '" r="6" />';
            out += '<text class="chart-draw-sel-label" x="24" y="' + (y - 10) + '">'
              + Number(mk.p1).toFixed(2) + " · Delete</text>";
          }
          return out;
        }
        if (mk.kind === "vline") {
          let x = null;
          try { x = chart.timeScale().timeToCoordinate(mk.t1); } catch (_) {}
          if (x == null) return "";
          let out = '<line class="chart-draw-vline' + (sel ? " is-selected" : "") + (soft ? " is-preview" : "")
            + '" x1="' + x + '" y1="0" x2="' + x + '" y2="' + sz.h + '" />';
          if (sel) {
            const hy2 = Math.max(36, sz.h - 36);
            out += '<circle class="chart-draw-handle is-level-v" data-mid="' + esc(mk.id) + '" data-end="move" cx="'
              + x + '" cy="16" r="6" />';
            out += '<circle class="chart-draw-handle is-level-v" data-mid="' + esc(mk.id) + '" data-end="move" cx="'
              + x + '" cy="' + hy2 + '" r="6" />';
            out += '<text class="chart-draw-sel-label" x="' + (x + 10) + '" y="22">Delete</text>';
          }
          return out;
        }
        const a = xyFromPoint(mk.t1, mk.p1);
        const b = xyFromPoint(mk.t2, mk.p2);
        if (!a || !b) return "";
        if (mk.kind === "rect") {
          const x0 = Math.min(a.x, b.x);
          const y0 = Math.min(a.y, b.y);
          const ww = Math.max(2, Math.abs(b.x - a.x));
          const hh = Math.max(2, Math.abs(b.y - a.y));
          return '<rect class="chart-draw-rect' + (sel ? " is-selected" : "") + (soft ? " is-preview" : "")
            + '" x="' + x0 + '" y="' + y0 + '" width="' + ww + '" height="' + hh + '" />'
            + paintHandles(a, b, mk.id);
        }
        if (mk.kind === "fib") {
          const x0 = Math.min(a.x, b.x);
          const x1 = Math.max(a.x, b.x);
          let out = '<rect class="chart-draw-fib-box' + (sel ? " is-selected" : "") + (soft ? " is-preview" : "")
            + '" x="' + x0 + '" y="' + Math.min(a.y, b.y) + '" width="' + Math.max(2, x1 - x0)
            + '" height="' + Math.max(2, Math.abs(b.y - a.y)) + '" />';
          FIB_LEVELS.forEach(function (lv) {
            const p = mk.p1 + (mk.p2 - mk.p1) * lv;
            const y = series.priceToCoordinate(p);
            if (y == null) return;
            out += '<line class="chart-draw-fib' + (soft ? " is-preview" : "") + '" x1="' + x0
              + '" y1="' + y + '" x2="' + x1 + '" y2="' + y + '" />';
            out += '<text class="chart-draw-fib-label" x="' + (x1 + 4) + '" y="' + (y - 2) + '">'
              + lv.toFixed(3).replace(/0+$/, "").replace(/\.$/, "") + "</text>";
          });
          return out + paintHandles(a, b, mk.id);
        }
        return "";
      }
      if (!drawingsHidden) {
        trendLines.forEach(function (tl) {
          svg += paintLine(tl, "chart-draw-trend" + (tl.id === selectedTrendId ? " is-selected" : ""), false);
        });
        marks.forEach(function (mk) {
          svg += paintMark(mk, false);
        });
      }
      if (selectedMaId) {
        const msel = mas.find(function (z) { return z.id === selectedMaId; });
        if (msel) {
          svg += '<text class="chart-ma-sel-label" x="10" y="22">'
            + esc(msel.type + " " + msel.period) + " · Delete</text>";
        }
      }
      if (trendAnchor && trendHover) {
        if (mode === "rect" || mode === "fib") {
          svg += paintMark({
            id: "", kind: mode, t1: trendAnchor.t, p1: trendAnchor.p,
            t2: trendHover.t, p2: trendHover.p
          }, true);
        } else {
          svg += paintLine({
            id: "",
            kind: mode === "ray" ? "ray" : "trend",
            t1: trendAnchor.t, p1: trendAnchor.p,
            t2: trendHover.t, p2: trendHover.p
          }, "chart-draw-trend", true);
        }
        const a = xyFromPoint(trendAnchor.t, trendAnchor.p);
        if (a) {
          svg += '<circle class="chart-draw-anchor" cx="' + a.x + '" cy="' + a.y + '" r="4" />';
        }
      } else if (trendAnchor) {
        const a = xyFromPoint(trendAnchor.t, trendAnchor.p);
        if (a) {
          svg += '<circle class="chart-draw-anchor" cx="' + a.x + '" cy="' + a.y + '" r="4" />';
        }
      }
      svg += "</svg>";
      layer.innerHTML = svg;
    }

    function addTrendLine(t1, p1, t2, p2, opts) {
      if (t1 == null || t2 == null || !isFinite(p1) || !isFinite(p2)) return null;
      // Same-bar clicks used to create zero-width LineSeries stubs — keep a usable span.
      let tt1 = t1;
      let tt2 = t2;
      let pp1 = p1;
      let pp2 = p2;
      if (tt1 === tt2 && Math.abs(pp1 - pp2) < 1e-12) {
        pp2 = pp1 * 1.001 + (Math.abs(pp1) < 1e-6 ? 0.01 : 0);
      }
      const id = (opts && opts.id) || ("tl-" + Date.now() + "-" + Math.floor(Math.random() * 1e4));
      const tl = { id: id, t1: tt1, p1: pp1, t2: tt2, p2: pp2, kind: (opts && opts.kind) || "trend" };
      trendLines.push(tl);
      selectedTrendId = id;
      selectedMarkId = null;
      selectedMaId = null;
      applyMaHighlight();
      layoutTrendLines();
      if (!(opts && opts.silent)) onChange();
      return tl;
    }

    function updateTrendLine(id, patch) {
      const tl = trendLines.find(function (x) { return x.id === id; });
      if (!tl) return;
      if (patch.t1 != null) tl.t1 = patch.t1;
      if (patch.p1 != null) tl.p1 = patch.p1;
      if (patch.t2 != null) tl.t2 = patch.t2;
      if (patch.p2 != null) tl.p2 = patch.p2;
      layoutTrendLines();
    }

    function removeTrendLine(id) {
      const ix = trendLines.findIndex(function (x) { return x.id === id; });
      if (ix < 0) return;
      trendLines.splice(ix, 1);
      if (selectedTrendId === id) selectedTrendId = null;
      layoutTrendLines();
      onChange();
    }

    function clearTrendLines() {
      trendLines = [];
      trendAnchor = null;
      trendHover = null;
      selectedTrendId = null;
      dragState = null;
      layoutTrendLines();
      onChange();
    }

    function upsertMa(cfg) {
      if (!LC || !chart) return;
      const type = (cfg.type || "SMA").toUpperCase();
      const period = Math.max(2, Number(cfg.period) || 20);
      const color = cfg.color || (type === "EMA" ? "#c2410c" : "#0f766e");
      const bars = getBars() || [];
      const closes = bars.map(function (b) { return Number(b.close); });
      const vals = type === "EMA" ? ema(closes, period) : sma(closes, period);
      const data = [];
      for (let i = 0; i < bars.length; i++) {
        if (vals[i] == null || !isFinite(vals[i])) continue;
        const t = bars[i].time != null ? bars[i].time : bars[i].t;
        data.push({ time: t, value: vals[i] });
      }
      let existing = mas.find(function (m) { return m.id === cfg.id; });
      const created = !existing;
      if (!existing) {
        const seriesMa = chart.addLineSeries({
          color: color,
          lineWidth: 2,
          priceLineVisible: false,
          lastValueVisible: true,
          title: type + " " + period
        });
        existing = { id: cfg.id || ("ma-" + type + "-" + period), type: type, period: period, color: color, series: seriesMa };
        mas.push(existing);
      } else {
        existing.type = type;
        existing.period = period;
        existing.color = color;
        existing.series.applyOptions({ color: color, title: type + " " + period });
      }
      existing.series.setData(data);
      existing.data = data;
      if (created && !cfg.silent) {
        selectedMaId = existing.id;
        selectedTrendId = null;
        selectedMarkId = null;
        vapSelected = false;
        applyMaHighlight();
        layoutTrendLines();
      }
      onChange();
      return existing;
    }

    function removeMa(id) {
      const ix = mas.findIndex(function (m) { return m.id === id; });
      if (ix < 0) return;
      try { chart.removeSeries(mas[ix].series); } catch (_) {}
      mas.splice(ix, 1);
      if (selectedMaId === id) selectedMaId = null;
      applyMaHighlight();
      layoutTrendLines();
      onChange();
    }

    function clearMas() {
      mas.slice().forEach(function (m) { removeMa(m.id); });
    }

    function setMode(next) {
      mode = next;
      vapAnchor = null;
      vapHover = null;
      trendAnchor = null;
      trendHover = null;
      dragState = null;
      host.classList.toggle("is-vap-tool", mode === "vap");
      host.classList.toggle("is-trend-tool", mode === "trend" || mode === "ray");
      host.classList.toggle("is-drawing", !!mode);
      layoutTrendLines();
    }

    function snapTime(t) {
      const bars = getBars() || [];
      const ix = findBarIndex(bars, t);
      return ix >= 0 ? barTimeAt(bars, ix) : t;
    }

    function onChartClick(param) {
      if (dragState) return;
      if (drawingsLocked && !mode) return;
      if (mode === "vap") {
        const pt = snapPt(pointFromChartParam(param));
        if (!pt) return;
        const t = pt.t;
        if (vapAnchor == null) {
          vapAnchor = t;
          vapHover = t;
          selectedTrendId = null;
          selectedMarkId = null;
          selectedMaId = null;
          applyMaHighlight();
          layoutTrendLines();
          return;
        }
        if (timeKey(vapAnchor) === timeKey(t)) {
          applyVapRange(vapAnchor, t);
          return;
        }
        applyVapRange(vapAnchor, t);
        return;
      }
      if (mode === "hline") {
        if (param && param.point) {
          const existing = findMarkNear(param.point.x, param.point.y, 12);
          if (existing && existing.kind === "hline") {
            selectObject({ kind: "mark", id: existing.id });
            return;
          }
        }
        const pt = snapPt(pointFromChartParam(param));
        if (!pt) return;
        addMark("hline", pt.t, pt.p, pt.t, pt.p);
        return;
      }
      if (mode === "vline") {
        if (param && param.point) {
          const existing = findMarkNear(param.point.x, param.point.y, 12);
          if (existing && existing.kind === "vline") {
            selectObject({ kind: "mark", id: existing.id });
            return;
          }
        }
        const pt = snapPt(pointFromChartParam(param));
        if (!pt) return;
        addMark("vline", pt.t, pt.p, pt.t, pt.p);
        return;
      }
      if (TWO_POINT_MODES[mode]) {
        const pt = snapPt(pointFromChartParam(param));
        if (!pt) return;
        if (!trendAnchor) {
          trendAnchor = pt;
          trendHover = null;
          selectedTrendId = null;
          selectedMarkId = null;
          selectedMaId = null;
          applyMaHighlight();
          layoutTrendLines();
          return;
        }
        if (trendAnchor.t === pt.t && Math.abs(trendAnchor.p - pt.p) < 1e-9) return;
        if (mode === "trend" || mode === "ray") {
          addTrendLine(trendAnchor.t, trendAnchor.p, pt.t, pt.p, { kind: mode });
        } else {
          addMark(mode, trendAnchor.t, trendAnchor.p, pt.t, pt.p);
        }
        trendAnchor = null;
        trendHover = null;
        layoutTrendLines();
        return;
      }
      // Idle: select drawing, MA, or VAP range.
      if (!param || param.point == null) return;
      const hit = nearestSelectable(param.point.x, param.point.y, 16);
      if (hit) {
        selectObject(hit);
        return;
      }
      const vapEnd = vapHandleHit(param.point.x, param.point.y);
      if (vapFrom != null && vapTo != null) {
        const xs = vapRangeXs(vapFrom, vapTo);
        if (xs && param.point.x >= xs.left - 6 && param.point.x <= xs.right + 6) {
          selectObject({ kind: "vap" });
          return;
        }
      }
      if (vapEnd) {
        selectObject({ kind: "vap" });
        return;
      }
      selectObject(null);
    }

    function onCrosshairMove(param) {
      if (dragState) return;
      if (TWO_POINT_MODES[mode] && trendAnchor) {
        const pt = snapPt(pointFromChartParam(param));
        if (!pt) return;
        trendHover = pt;
        layoutTrendLines();
        return;
      }
      if (mode === "vap" && vapAnchor != null) {
        const pt = snapPt(pointFromChartParam(param));
        if (!pt) return;
        vapHover = pt.t;
        layoutTrendLines();
      }
    }

    function onPointerDown(ev) {
      if (ev.button != null && ev.button !== 0) return;
      if (drawingsLocked) return;
      const rect = host.getBoundingClientRect();
      const x = ev.clientX - rect.left;
      const y = ev.clientY - rect.top;
      const now = Date.now();
      const likelyDbl = (now - lastPtrDownAt) < 450 && Math.hypot(x - lastPtrX, y - lastPtrY) < 12;
      lastPtrDownAt = now;
      lastPtrX = x;
      lastPtrY = y;

      const vapEnd = vapHandleHit(x, y);
      if (vapEnd) {
        dragState = { kind: "vap", end: vapEnd };
        vapSelected = true;
        selectedTrendId = null;
        selectedMarkId = null;
        selectedMaId = null;
        applyMaHighlight();
        beginDrag(ev);
        return;
      }

      if (mode === "hline" || mode === "vline") {
        if (selectedMarkId) {
          const mk = marks.find(function (z) { return z.id === selectedMarkId; });
          if (mk && mk.kind === mode && levelHandleHit(mk, x, y)) {
            dragState = { kind: "mark", id: mk.id, end: "move" };
            beginDrag(ev);
            return;
          }
        }
        return;
      }
      if (mode === "vap" || TWO_POINT_MODES[mode]) {
        return;
      }

      if (selectedTrendId) {
        const tl = trendLines.find(function (z) { return z.id === selectedTrendId; });
        if (tl) {
          const end = handleHit(tl, x, y);
          if (end) {
            dragState = { kind: "trend", id: tl.id, end: end };
            beginDrag(ev);
            return;
          }
        }
      }
      if (selectedMarkId) {
        const mk = marks.find(function (z) { return z.id === selectedMarkId; });
        if (mk) {
          if (mk.kind === "hline" || mk.kind === "vline") {
            if (levelHandleHit(mk, x, y)) {
              dragState = { kind: "mark", id: mk.id, end: "move" };
              beginDrag(ev);
              return;
            }
          } else {
            const end = handleHit(mk, x, y);
            if (end) {
              dragState = { kind: "mark", id: mk.id, end: end };
              beginDrag(ev);
              return;
            }
          }
        }
      }
      if (likelyDbl) return;
    }

    function beginDrag(ev) {
      host.classList.add("is-dragging-draw");
      try { host.setPointerCapture(ev.pointerId); } catch (_) {}
      document.addEventListener("pointermove", onPointerMove, true);
      document.addEventListener("pointerup", onPointerUp, true);
      document.addEventListener("pointercancel", onPointerUp, true);
      ev.preventDefault();
      ev.stopPropagation();
    }

    function endDragListeners() {
      document.removeEventListener("pointermove", onPointerMove, true);
      document.removeEventListener("pointerup", onPointerUp, true);
      document.removeEventListener("pointercancel", onPointerUp, true);
    }

    function onPointerMove(ev) {
      if (!dragState) return;
      const pt = pointFromClient(ev.clientX, ev.clientY);
      if (!pt) return;
      if (dragState.kind === "trend") {
        if (pt.t == null || pt.p == null || !isFinite(pt.p)) return;
        const sp = snapPt(pt) || pt;
        if (dragState.end === "a") updateTrendLine(dragState.id, { t1: sp.t, p1: sp.p });
        else if (dragState.end === "b") updateTrendLine(dragState.id, { t2: sp.t, p2: sp.p });
      } else if (dragState.kind === "mark") {
        const mk = marks.find(function (z) { return z.id === dragState.id; });
        if (mk) {
          if (mk.kind === "hline") {
            if (pt.p == null || !isFinite(pt.p)) return;
            mk.p1 = pt.p;
            mk.p2 = pt.p;
          } else if (mk.kind === "vline") {
            const t = pt.t != null ? pt.t : timeAtX(pt.x);
            if (t == null) return;
            mk.t1 = t;
            mk.t2 = t;
          } else if (dragState.end === "a") {
            const sp = snapPt(pt) || pt;
            if (sp.t == null || sp.p == null) return;
            mk.t1 = sp.t;
            mk.p1 = sp.p;
          } else if (dragState.end === "b") {
            const sp = snapPt(pt) || pt;
            if (sp.t == null || sp.p == null) return;
            mk.t2 = sp.t;
            mk.p2 = sp.p;
          }
          layoutTrendLines();
        }
      } else if (dragState.kind === "vap") {
        if (pt.t == null) return;
        const t = snapTime(pt.t);
        if (dragState.end === "from") vapFrom = t;
        else vapTo = t;
        applyVapRange(vapFrom, vapTo, { silent: true });
        vapSelected = true;
      }
      ev.preventDefault();
    }

    function onPointerUp(ev) {
      if (!dragState) return;
      const wasVap = dragState.kind === "vap";
      dragState = null;
      host.classList.remove("is-dragging-draw");
      endDragListeners();
      try { host.releasePointerCapture(ev.pointerId); } catch (_) {}
      layoutTrendLines();
      if (wasVap && vapFrom != null && vapTo != null) {
        applyVapRange(vapFrom, vapTo);
      } else {
        onChange();
      }
    }

    function onKeyDown(ev) {
      const tag = (ev.target && ev.target.tagName || "").toLowerCase();
      if (tag === "input" || tag === "textarea" || tag === "select") return;
      if (ev.key === "Escape") {
        if (vapAnchor != null || trendAnchor != null || mode) {
          vapAnchor = null;
          vapHover = null;
          trendAnchor = null;
          trendHover = null;
          if (mode) setMode(null);
          else layoutTrendLines();
          ev.preventDefault();
        } else if (selectedTrendId) {
          selectedTrendId = null;
          layoutTrendLines();
          ev.preventDefault();
        } else if (selectedMarkId) {
          selectedMarkId = null;
          layoutTrendLines();
          ev.preventDefault();
        } else if (selectedMaId) {
          selectedMaId = null;
          applyMaHighlight();
          layoutTrendLines();
          ev.preventDefault();
        } else if (vapSelected) {
          vapSelected = false;
          layoutTrendLines();
          ev.preventDefault();
        }
        return;
      }
      if (ev.key === "Backspace" || ev.key === "Delete") {
        if (drawingsLocked) return;
        if (selectedTrendId) {
          removeTrendLine(selectedTrendId);
          ev.preventDefault();
        } else if (selectedMarkId) {
          removeMark(selectedMarkId);
          ev.preventDefault();
        } else if (selectedMaId) {
          removeMa(selectedMaId);
          ev.preventDefault();
        } else if (vapSelected || vapFrom != null) {
          clearVap();
          ev.preventDefault();
        }
      }
    }

    function onHostDblClick(ev) {
      const rect = host.getBoundingClientRect();
      const x = ev.clientX - rect.left;
      const y = ev.clientY - rect.top;
      let hit = nearestSelectable(x, y, 22);
      if (!hit && vapFrom != null && vapTo != null) {
        const xs = vapRangeXs(vapFrom, vapTo);
        if (xs && x >= xs.left - 6 && x <= xs.right + 6) hit = { kind: "vap" };
      }
      if (!hit) return;
      selectObject(hit);
      ev.preventDefault();
      ev.stopPropagation();
    }

    if (chart && typeof chart.subscribeClick === "function") {
      chart.subscribeClick(onChartClick);
    }
    if (chart && typeof chart.subscribeCrosshairMove === "function") {
      chart.subscribeCrosshairMove(onCrosshairMove);
    }
    if (chart && typeof chart.timeScale === "function") {
      chart.timeScale().subscribeVisibleLogicalRangeChange(function () {
        scheduleToolLayout();
      });
    }
    bindScaleOverlayFollow(host, {
      chart: chart,
      series: series,
      freezePrice: opts.freezePrice !== false,
      onLayout: function () {
        scheduleToolLayout();
        if (typeof opts.onScaleLayout === "function") opts.onScaleLayout();
      }
    });
    host.addEventListener("pointerdown", onPointerDown, true);
    host.addEventListener("dblclick", onHostDblClick, true);
    window.addEventListener("keydown", onKeyDown);
    ensureDrawLayer();
    layoutTrendLines();

    function getState() {
      return {
        vapRange: vapFrom != null ? { from: vapFrom, to: vapTo } : null,
        trendLines: trendLines.map(function (tl) {
          return { t1: tl.t1, p1: tl.p1, t2: tl.t2, p2: tl.p2, id: tl.id, kind: tl.kind || "trend" };
        }),
        marks: marks.map(function (mk) {
          return { id: mk.id, kind: mk.kind, t1: mk.t1, p1: mk.p1, t2: mk.t2, p2: mk.p2 };
        }),
        mas: mas.map(function (m) {
          return { id: m.id, type: m.type, period: m.period, color: m.color };
        }),
        magnet: !!magnetOn,
        drawingsHidden: !!drawingsHidden,
        drawingsLocked: !!drawingsLocked
      };
    }

    function setState(st) {
      if (!st) return;
      trendLines = [];
      marks = [];
      trendAnchor = null;
      trendHover = null;
      selectedTrendId = null;
      selectedMarkId = null;
      selectedMaId = null;
      magnetOn = !!st.magnet;
      drawingsHidden = !!st.drawingsHidden;
      drawingsLocked = !!st.drawingsLocked;
      clearMas();
      clearVap();
      (st.trendLines || []).forEach(function (tl) {
        addTrendLine(tl.t1, tl.p1, tl.t2, tl.p2, { id: tl.id, kind: tl.kind || "trend", silent: true });
      });
      (st.marks || []).forEach(function (mk) {
        addMark(mk.kind, mk.t1, mk.p1, mk.t2, mk.p2, { id: mk.id, silent: true });
      });
      (st.mas || []).forEach(function (m) { upsertMa(Object.assign({}, m, { silent: true })); });
      applyMaHighlight();
      if (st.vapRange && st.vapRange.from != null && st.vapRange.to != null) {
        applyVapRange(st.vapRange.from, st.vapRange.to);
      }
      layoutTrendLines();
    }

    function setPointSize(ps) {
      const next = ps > 0 ? ps : 0.01;
      if (next === pointSize) return;
      pointSize = next;
      if (ohlcTip && typeof ohlcTip.setPointSize === "function") ohlcTip.setPointSize(next);
      if (vapFrom != null && vapTo != null) {
        applyVapRange(vapFrom, vapTo, { silent: true });
      }
    }

    function refreshOverlays() {
      layoutStretchedVapNow();
      layoutTrendLinesNow();
      const eps = effectivePointSize();
      if (ohlcTip && typeof ohlcTip.setPointSize === "function") ohlcTip.setPointSize(eps);
      mas.slice().forEach(function (m) {
        upsertMa({ id: m.id, type: m.type, period: m.period, color: m.color, silent: true });
      });
    }

    function destroy() {
      if (ohlcTip && typeof ohlcTip.destroy === "function") ohlcTip.destroy();
      clearTrendLines();
      clearMarks();
      clearMas();
      clearVap();
      setMode(null);
      host.removeEventListener("pointerdown", onPointerDown, true);
      endDragListeners();
      host.removeEventListener("dblclick", onHostDblClick, true);
      window.removeEventListener("keydown", onKeyDown);
      if (drawLayer && drawLayer.parentNode) drawLayer.parentNode.removeChild(drawLayer);
      drawLayer = null;
    }

    return {
      setMode: setMode,
      getMode: function () { return mode; },
      applyVapRange: applyVapRange,
      clearVap: clearVap,
      addTrendLine: addTrendLine,
      clearTrendLines: clearTrendLines,
      removeTrendLine: removeTrendLine,
      clearMarks: clearMarks,
      upsertMa: upsertMa,
      removeMa: removeMa,
      clearMas: clearMas,
      deleteSelected: function () {
        if (drawingsLocked) return false;
        if (selectedTrendId) {
          removeTrendLine(selectedTrendId);
          return true;
        }
        if (selectedMarkId) {
          removeMark(selectedMarkId);
          return true;
        }
        if (selectedMaId) {
          removeMa(selectedMaId);
          return true;
        }
        if (vapSelected || vapFrom != null) {
          clearVap();
          return true;
        }
        return false;
      },
      hasSelection: function () {
        return !!(selectedTrendId || selectedMarkId || selectedMaId || vapSelected);
      },
      setMagnet: function (on) {
        magnetOn = !!on;
        onChange();
      },
      getMagnet: function () { return magnetOn; },
      setDrawingsHidden: function (on) {
        drawingsHidden = !!on;
        layoutTrendLines();
        layoutStretchedVap();
        onChange();
      },
      getDrawingsHidden: function () { return drawingsHidden; },
      setDrawingsLocked: function (on) {
        drawingsLocked = !!on;
        onChange();
      },
      getDrawingsLocked: function () { return drawingsLocked; },
      getState: getState,
      setState: setState,
      setPointSize: setPointSize,
      setImpulseNotes: function (notes) {
        if (ohlcTip && typeof ohlcTip.setImpulseNotes === "function") ohlcTip.setImpulseNotes(notes);
      },
      paintOhlc: function () {
        if (ohlcTip && typeof ohlcTip.paintLast === "function") ohlcTip.paintLast();
      },
      refreshOverlays: refreshOverlays,
      layoutStretchedVap: layoutStretchedVap,
      layoutTrendLines: layoutTrendLines,
      destroy: destroy
    };
  }

  /**
   * Volume-at-price overlays for the chart terminal: session profile (horizontal
   * volumes), cluster ticks from tape, and numeric footprint on a pinned range.
   */
  function attachFlowOverlays(opts) {
    opts = opts || {};
    const chart = opts.chart;
    const series = opts.series;
    const host = opts.hostEl;
    const getBars = typeof opts.getBars === "function" ? opts.getBars : function () { return []; };
    const timeOf = typeof opts.timeOf === "function" ? opts.timeOf : function (iso) {
      if (iso == null) return null;
      if (typeof iso === "number" && isFinite(iso)) return iso;
      const ms = Date.parse(String(iso).replace(" ", "T"));
      return isFinite(ms) ? Math.floor(ms / 1000) : null;
    };
    const onChange = typeof opts.onChange === "function" ? opts.onChange : function () {};
    const isBusy = typeof opts.isDrawing === "function" ? opts.isDrawing : function () { return false; };
    const barSec = opts.barSec > 0 ? opts.barSec : 300;
    const pointSize = opts.pointSize > 0 ? opts.pointSize : 0.01;
    const CLUSTER_MAX = 40;
    if (!chart || !series || !host) {
      return { destroy: function () {}, layout: function () {} };
    }

    let profile = [];
    let fpByTime = {};
    let showProfile = false;
    let showClusters = false;
    let fpTool = false;
    let fpFrom = null;
    let fpTo = null;
    let fpAnchor = null;
    let fpHover = null;
    let fpPinned = [];
    const FP_PIN_MAX = 24;
    let layoutRaf = 0;
    const fpSnapTol = Math.max(barSec, 300);

    function ensureOv(cls) {
      let ov = host.querySelector("." + cls.split(" ").join("."));
      if (!ov) {
        ov = document.createElement("div");
        ov.className = cls;
        host.appendChild(ov);
      }
      return ov;
    }

    function barTimes() {
      const out = [];
      (getBars() || []).forEach(function (b) {
        const t = b && (b.time != null ? b.time : b.t);
        if (t != null) out.push(t);
      });
      return out;
    }

    function nearestTime(t) {
      const times = barTimes();
      if (!times.length || t == null) return t;
      let best = times[0];
      let bestD = Math.abs(times[0] - t);
      for (let i = 1; i < times.length; i++) {
        const d = Math.abs(times[i] - t);
        if (d < bestD) {
          bestD = d;
          best = times[i];
        }
      }
      return best;
    }

    function lookupFp(t) {
      if (t == null) return null;
      if (fpByTime[t]) return fpByTime[t];
      let best = null;
      let bestD = Infinity;
      Object.keys(fpByTime).forEach(function (k) {
        const d = Math.abs(Number(k) - t);
        if (d < bestD) {
          bestD = d;
          best = fpByTime[k];
        }
      });
      return bestD <= fpSnapTol ? best : null;
    }

    function timesInRange(a, b) {
      const lo = Math.min(a, b);
      const hi = Math.max(a, b);
      return barTimes().filter(function (t) { return t >= lo && t <= hi; });
    }

    function applyFpRange(a, b) {
      const t0 = nearestTime(a);
      const t1 = nearestTime(b);
      fpFrom = Math.min(t0, t1);
      fpTo = Math.max(t0, t1);
      fpPinned = timesInRange(fpFrom, fpTo);
      if (fpPinned.length > FP_PIN_MAX) {
        const step = Math.ceil(fpPinned.length / FP_PIN_MAX);
        const kept = [];
        for (let i = 0; i < fpPinned.length; i += step) kept.push(fpPinned[i]);
        const last = fpPinned[fpPinned.length - 1];
        if (kept[kept.length - 1] !== last) kept.push(last);
        fpPinned = kept.slice(0, FP_PIN_MAX);
      }
      fpAnchor = null;
      fpHover = null;
      layoutNow();
      onChange();
    }

    function indexFp(fps) {
      fpByTime = {};
      const times = barTimes();
      (fps || []).forEach(function (fb) {
        let t = timeOf(fb.time);
        if (t == null) return;
        if (times.length) {
          let best = t;
          let bestD = Infinity;
          for (let i = 0; i < times.length; i++) {
            const d = Math.abs(Number(times[i]) - Number(t));
            if (d < bestD) {
              bestD = d;
              best = times[i];
            }
          }
          if (bestD <= fpSnapTol) t = best;
        }
        fpByTime[t] = fb;
      });
    }

    function sessionVapFallback() {
      const bars = getBars() || [];
      if (!bars.length) return [];
      const n = bars.length;
      const take = Math.min(n, n > 200 ? 120 : 72);
      return vapFromBars(bars, n - take, n - 1, pointSize);
    }

    function paintProfileLevels(ov, levels, maxW) {
      let drawn = 0;
      (levels || []).forEach(function (lvl) {
        const px = Number(lvl.price);
        const vol = Number(lvl.volume);
        if (!isFinite(px) || !(vol > 0)) return;
        const y = series.priceToCoordinate(px);
        if (y == null) return;
        const bar = document.createElement("div");
        bar.className = "signal-vap-bar";
        bar.style.top = (y - 1) + "px";
        bar.style.width = Math.max(2, Math.round((lvl.strength || 0) * maxW)) + "px";
        bar.title = px.toFixed(2) + " · vol " + Math.round(vol);
        ov.appendChild(bar);
        drawn += 1;
      });
      return drawn;
    }

    function layoutProfile() {
      const ov = ensureOv("charts-flow-profile");
      ov.innerHTML = "";
      ov.hidden = !showProfile;
      if (!showProfile) return;
      const maxW = 72;
      let drawn = paintProfileLevels(ov, profile, maxW);
      if (drawn < 3) {
        drawn += paintProfileLevels(ov, sessionVapFallback(), maxW);
      }
      ov.dataset.drawn = String(drawn);
      if (!drawn) {
        const miss = document.createElement("div");
        miss.className = "charts-flow-miss";
        miss.textContent = "нет объёма";
        ov.appendChild(miss);
      }
    }

    function visibleBarTimes() {
      let logical = null;
      try { logical = chart.timeScale().getVisibleLogicalRange(); } catch (_) {}
      const bars = getBars() || [];
      if (!bars.length) return [];
      if (!logical) {
        return bars.slice(-24).map(function (b) { return b.time != null ? b.time : b.t; });
      }
      const from = Math.max(0, Math.floor(logical.from));
      const to = Math.min(bars.length - 1, Math.ceil(logical.to));
      const out = [];
      for (let i = from; i <= to; i++) {
        const b = bars[i];
        if (!b) continue;
        out.push(b.time != null ? b.time : b.t);
      }
      return out;
    }

    function maybeZoomForClusters() {
      const bars = getBars() || [];
      if (bars.length < 8) return false;
      const i1 = bars.length - 1;
      const i0 = Math.max(0, bars.length - 32);
      let spacing = 8;
      let visFrom = 0;
      let visTo = 0;
      try {
        spacing = chart.timeScale().options().barSpacing || 8;
        const lr = chart.timeScale().getVisibleLogicalRange();
        if (lr) {
          visFrom = lr.from;
          visTo = lr.to;
        }
      } catch (_) {}
      const vis = visTo - visFrom;
      const already = vis > 0 && vis <= 36 && visFrom <= i0 + 4 && visTo >= i1 - 2 && spacing >= 12;
      if (already) return false;
      try {
        chart.timeScale().setVisibleLogicalRange({
          from: Math.max(0, i0 - 1),
          to: i1 + 2
        });
        return true;
      } catch (_) {
        return false;
      }
    }

    function barAtTime(t) {
      const bars = getBars() || [];
      let best = null;
      let bestD = Infinity;
      for (let i = 0; i < bars.length; i++) {
        const bt = bars[i] && (bars[i].time != null ? bars[i].time : bars[i].t);
        if (bt == null) continue;
        if (bt === t) return bars[i];
        const d = Math.abs(Number(bt) - Number(t));
        if (d < bestD) {
          bestD = d;
          best = bars[i];
        }
      }
      return bestD <= fpSnapTol ? best : null;
    }

    function levelsFromCandle(bar) {
      if (!bar) return [];
      const lo = Math.min(Number(bar.low), Number(bar.high));
      const hi = Math.max(Number(bar.low), Number(bar.high));
      const open = Number(bar.open);
      const close = Number(bar.close);
      if (!isFinite(lo) || !isFinite(hi)) return [];
      const vol = Number(bar.volume);
      const lots = vol > 0 ? vol : 1;
      const bull = close >= open;
      const span = hi - lo;
      const n = span > 0 ? Math.max(5, Math.min(11, Math.round(span / Math.max(pointSize, span / 10)) || 5)) : 1;
      const bodyLo = Math.min(open, close);
      const bodyHi = Math.max(open, close);
      const levels = [];
      for (let i = 0; i < n; i++) {
        const price = n === 1 ? (isFinite(close) ? close : lo) : lo + span * (i / (n - 1));
        const inBody = n === 1 || (price >= bodyLo && price <= bodyHi);
        const w = inBody ? 1.35 : 0.4;
        const chunk = lots * w / n;
        levels.push({
          price: price,
          buy: bull ? chunk * 0.62 : chunk * 0.38,
          sell: bull ? chunk * 0.38 : chunk * 0.62
        });
      }
      return levels;
    }

    function clusterBins(levels, binPx) {
      const px = binPx > 0 ? binPx : 2;
      const bins = Object.create(null);
      (levels || []).forEach(function (lv) {
        const y = series.priceToCoordinate(lv.price);
        if (y == null) return;
        const key = String(Math.round(y / px) * px);
        let b = bins[key];
        if (!b) {
          b = bins[key] = { y: Number(key), buy: 0, sell: 0, price: Number(lv.price) || 0 };
        }
        const buy = lv.buy || 0;
        const sell = lv.sell || 0;
        b.buy += buy;
        b.sell += sell;
        if (buy + sell >= (bins[key]._best || 0)) {
          b.price = Number(lv.price) || b.price;
          b._best = buy + sell;
        }
      });
      return Object.keys(bins).map(function (k) { return bins[k]; });
    }

    function layoutClusters() {
      const ov = ensureOv("charts-cluster-overlay");
      ov.innerHTML = "";
      ov.hidden = !showClusters;
      if (!showClusters) return;
      let spacing = 8;
      try { spacing = chart.timeScale().options().barSpacing || 8; } catch (_) {}
      let times = visibleBarTimes();
      if (times.length > CLUSTER_MAX) {
        times = times.slice(times.length - CLUSTER_MAX);
      }
      if (!times.length) return;
      const ts = chart.timeScale();
      const half = Math.max(14, Math.min(40, Math.max(spacing, 10) * 0.88));
      const tickH = Math.max(5, Math.min(9, Math.round(Math.max(spacing, 8) * 0.38)));
      const minW = Math.max(8, Math.round(half * 0.28));
      let ticks = 0;
      times.forEach(function (t) {
        const fb = lookupFp(t);
        let levels = fb && (fb.levels || []).length ? fb.levels : null;
        const fromTape = !!levels;
        if (!levels) levels = levelsFromCandle(barAtTime(t));
        if (!levels.length) return;
        const x = ts.timeToCoordinate(t);
        if (x == null) return;
        let bins = clusterBins(levels, Math.max(3, Math.round(tickH * 0.7)));
        if (bins.length > 22) bins = clusterBins(levels, 5);
        let maxV = 1;
        bins.forEach(function (b) {
          const v = b.buy + b.sell;
          if (v > maxV) maxV = v;
        });
        const floor = Math.max(0.0001, maxV * 0.04);
        let use = bins.filter(function (b) { return (b.buy + b.sell) >= floor; });
        if (use.length < 4) {
          use = bins.slice().sort(function (a, b) {
            return (b.buy + b.sell) - (a.buy + a.sell);
          }).slice(0, 6);
        }
        use.forEach(function (b) {
          const y = b.y;
          if (b.buy > 0) {
            const w = Math.max(minW, Math.round((b.buy / maxV) * half));
            const el = document.createElement("div");
            el.className = "charts-cluster-tick is-buy" + (fromTape ? "" : " is-ohlc");
            el.style.top = (y - tickH / 2) + "px";
            el.style.left = x + "px";
            el.style.width = w + "px";
            el.style.height = tickH + "px";
            el.title = Number(b.price).toFixed(2) + " buy " + Math.round(b.buy)
              + (fromTape ? "" : " · по свече");
            ov.appendChild(el);
            ticks += 1;
          }
          if (b.sell > 0) {
            const w = Math.max(minW, Math.round((b.sell / maxV) * half));
            const el = document.createElement("div");
            el.className = "charts-cluster-tick is-sell" + (fromTape ? "" : " is-ohlc");
            el.style.top = (y - tickH / 2) + "px";
            el.style.left = (x - w) + "px";
            el.style.width = w + "px";
            el.style.height = tickH + "px";
            el.title = Number(b.price).toFixed(2) + " sell " + Math.round(b.sell)
              + (fromTape ? "" : " · по свече");
            ov.appendChild(el);
            ticks += 1;
          }
        });
      });
      ov.dataset.ticks = String(ticks);
      if (!ticks) {
        const miss = document.createElement("div");
        miss.className = "charts-flow-miss";
        miss.textContent = Object.keys(fpByTime).length ? "приблизьте" : "нет ленты";
        ov.appendChild(miss);
      }
    }

    function layoutFootprint() {
      const ov = ensureOv("signal-footprint-overlay charts-fp-overlay");
      ov.innerHTML = "";
      const times = {};
      fpPinned.forEach(function (t) { times[t] = "pin"; });
      if (fpTool && fpHover != null && !times[fpHover] && fpAnchor == null) times[fpHover] = "hover";
      if (fpAnchor != null && fpHover != null) {
        timesInRange(fpAnchor, fpHover).forEach(function (t) {
          if (!times[t]) times[t] = "preview";
        });
      }
      let rangeA = fpAnchor;
      let rangeB = fpHover;
      if (rangeA == null && fpFrom != null && fpTo != null) {
        rangeA = fpFrom;
        rangeB = fpTo;
      }
      const keys = Object.keys(times).map(Number).sort(function (a, b) { return a - b; });
      if (!keys.length && rangeA == null) {
        ov.hidden = true;
        return;
      }
      ov.hidden = false;
      if (rangeA != null && rangeB != null) {
        let x1 = null;
        let x2 = null;
        try {
          x1 = chart.timeScale().timeToCoordinate(Math.min(rangeA, rangeB));
          x2 = chart.timeScale().timeToCoordinate(Math.max(rangeA, rangeB));
        } catch (_) {}
        if (x1 != null && x2 != null) {
          let barW = 8;
          try {
            const sp = chart.timeScale().options().barSpacing;
            if (sp > 0) barW = sp;
          } catch (_) {}
          const left = Math.min(x1, x2) - barW * 0.35;
          const right = Math.max(x1, x2) + barW * 0.55;
          const band = document.createElement("div");
          band.className = "signal-fp-range" + (fpAnchor != null ? " is-preview" : " is-selected");
          band.style.left = left + "px";
          band.style.width = Math.max(2, right - left) + "px";
          ov.appendChild(band);
        }
      }
      const ts = chart.timeScale();
      const fpCount = Object.keys(fpByTime).length;
      keys.forEach(function (t) {
        const fb = lookupFp(t);
        const x = ts.timeToCoordinate(t);
        if (x == null) return;
        const col = document.createElement("div");
        col.className = "signal-fp-col"
          + (times[t] === "hover" ? " is-hover" : "")
          + (times[t] === "pin" ? " is-pinned" : "")
          + (times[t] === "preview" ? " is-preview" : "");
        col.style.left = (x - 22) + "px";
        if (!fb || !(fb.levels || []).length) {
          const miss = document.createElement("div");
          miss.className = "signal-fp-miss";
          miss.textContent = fpCount ? "нет уровней" : "нет ленты";
          col.appendChild(miss);
          ov.appendChild(col);
          return;
        }
        (fb.levels || []).slice(0, 18).forEach(function (lv) {
          const y = series.priceToCoordinate(lv.price);
          if (y == null) return;
          const cell = document.createElement("div");
          cell.className = "signal-fp-cell";
          cell.style.top = (y - 6) + "px";
          const buy = lv.buy || 0;
          const sell = lv.sell || 0;
          cell.title = Number(lv.price).toFixed(2) + " buy " + buy + " × sell " + sell;
          cell.innerHTML = "<span class=\"b\">" + buy + "</span><span class=\"x\">×</span><span class=\"s\">" + sell + "</span>";
          col.appendChild(cell);
        });
        ov.appendChild(col);
      });
    }

    function layoutNow() {
      layoutProfile();
      layoutClusters();
      layoutFootprint();
      host.classList.toggle("is-fp-tool", !!fpTool);
    }

    function layout() {
      if (layoutRaf) return;
      layoutRaf = requestAnimationFrame(function () {
        layoutRaf = 0;
        layoutNow();
      });
    }

    if (typeof chart.subscribeClick === "function") {
      chart.subscribeClick(function (param) {
        if (!fpTool || isBusy()) return;
        if (!param || param.time == null) return;
        const t = nearestTime(typeof param.time === "number" ? param.time : timeOf(param.time));
        if (t == null) return;
        if (fpAnchor == null) {
          fpAnchor = t;
          fpHover = t;
          layoutNow();
          return;
        }
        applyFpRange(fpAnchor, t);
      });
    }
    if (typeof chart.subscribeCrosshairMove === "function") {
      chart.subscribeCrosshairMove(function (param) {
        if (!fpTool || isBusy()) return;
        const t = param && param.time != null
          ? nearestTime(typeof param.time === "number" ? param.time : timeOf(param.time))
          : null;
        if (t === fpHover) return;
        fpHover = t;
        layout();
      });
    }
    try {
      chart.timeScale().subscribeVisibleLogicalRangeChange(function () { layout(); });
    } catch (_) {}
    try {
      chart.timeScale().subscribeVisibleTimeRangeChange(function () { layout(); });
    } catch (_) {}

    return {
      setProfile: function (levels) {
        profile = levels || [];
        layoutNow();
      },
      setFootprints: function (fps) {
        indexFp(fps);
        if (fpFrom != null && fpTo != null) {
          fpPinned = timesInRange(fpFrom, fpTo);
        }
        layoutNow();
      },
      setShowProfile: function (on) {
        showProfile = !!on;
        layoutNow();
        onChange();
      },
      getShowProfile: function () { return showProfile; },
      setShowClusters: function (on) {
        showClusters = !!on;
        if (showClusters && maybeZoomForClusters()) {
          requestAnimationFrame(function () { layoutNow(); });
        }
        layoutNow();
        onChange();
      },
      getShowClusters: function () { return showClusters; },
      setFpTool: function (on) {
        fpTool = !!on;
        if (!fpTool) {
          fpAnchor = null;
          fpHover = null;
        }
        layoutNow();
      },
      getFpTool: function () { return fpTool; },
      clearFp: function () {
        fpFrom = fpTo = fpAnchor = fpHover = null;
        fpPinned = [];
        layoutNow();
        onChange();
      },
      layout: layout,
      getState: function () {
        return {
          showProfile: !!showProfile,
          showClusters: !!showClusters,
          fpFrom: fpFrom,
          fpTo: fpTo
        };
      },
      setState: function (st) {
        if (!st) return;
        showProfile = !!st.showProfile;
        showClusters = !!st.showClusters;
        if (st.fpFrom != null && st.fpTo != null) {
          fpFrom = st.fpFrom;
          fpTo = st.fpTo;
          fpPinned = timesInRange(fpFrom, fpTo);
        }
        layoutNow();
      },
      destroy: function () {
        ["charts-flow-profile", "charts-cluster-overlay", "charts-fp-overlay"].forEach(function (cls) {
          const el = host.querySelector("." + cls);
          if (el && el.parentNode) el.parentNode.removeChild(el);
        });
      }
    };
  }

  function promptMaConfig(defaults) {
    const type = window.prompt("Тип средней: SMA или EMA", (defaults && defaults.type) || "SMA");
    if (type == null) return null;
    const periodRaw = window.prompt("Период", String((defaults && defaults.period) || 20));
    if (periodRaw == null) return null;
    const period = parseInt(periodRaw, 10);
    if (!(period >= 2)) return null;
    return { type: String(type).toUpperCase() === "EMA" ? "EMA" : "SMA", period: period };
  }

  global.TrinityChartKit = {
    authHeaders: authHeaders,
    currentUserKey: currentUserKey,
    loadLayouts: loadLayouts,
    saveLayouts: saveLayouts,
    vapFromBars: vapFromBars,
    pointSizeFor: pointSizeFor,
    inferPointSize: inferPointSize,
    thinLevels: thinLevels,
    hvnBands: hvnBands,
    sma: sma,
    ema: ema,
    attachTools: attachTools,
    attachFlowOverlays: attachFlowOverlays,
    bindTradingViewNav: bindTradingViewNav,
    attachFriendlyNav: attachFriendlyNav,
    ensureNavHud: ensureNavHud,
    bindScaleOverlayFollow: bindScaleOverlayFollow,
    bindCandleOhlcTip: bindCandleOhlcTip,
    classifyImpulseSeries: classifyImpulseSeries,
    snapshotTimeScale: snapshotTimeScale,
    applyTimeScaleSnap: applyTimeScaleSnap,
    setSeriesDataKeepView: setSeriesDataKeepView,
    barCacheGet: barCacheGet,
    barCachePut: barCachePut,
    promptMaConfig: promptMaConfig,
    esc: esc
  };
})(typeof window !== "undefined" ? window : globalThis);
