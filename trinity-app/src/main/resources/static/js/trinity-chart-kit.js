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

  async function loadLayouts() {
    const res = await fetch("/api/charts/layouts", { headers: authHeaders() });
    if (!res.ok) throw new Error("HTTP " + res.status);
    return res.json();
  }

  async function saveLayouts(doc) {
    const res = await fetch("/api/charts/layouts", {
      method: "PUT",
      headers: authHeaders({ "Content-Type": "application/json" }),
      body: JSON.stringify(doc || {})
    });
    if (!res.ok) throw new Error("HTTP " + res.status);
    return res.json();
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
    // Hard cap: RI@0.01 is ~millions of buckets and kills the main thread.
    const MAX_SPAN = 400;
    const spanBuckets = Math.round((hiAll - loAll) / pt) + 1;
    if (spanBuckets > MAX_SPAN) {
      pt = (hiAll - loAll) / MAX_SPAN;
      if (!(pt > 0)) pt = pointSize > 0 ? pointSize : 0.01;
    }
    const map = Object.create(null);
    for (let i = a; i <= b; i++) {
      const bar = bars[i];
      if (!bar) continue;
      const lo = Math.min(bar.low, bar.high);
      const hi = Math.max(bar.low, bar.high);
      const vol = Number(bar.volume) || 0;
      if (!(vol > 0) || !(hi >= lo)) continue;
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

    let mode = null; // vap | trend | null
    let vapAnchor = null; // time of first candle while drawing
    let vapHover = null; // time under cursor while drawing
    let vapSelected = false; // range edges editable
    let trendAnchor = null; // {t,p} first point while drawing
    let trendHover = null; // {t,p} rubber-band end
    let stretchedLevels = [];
    let stretchedBands = [];
    let vapFrom = null;
    let vapTo = null;
    let trendLines = []; // {id,t1,p1,t2,p2}
    let selectedTrendId = null;
    let dragState = null; // {kind:'trend'|'vap', ...}
    let mas = []; // {id,type,period,color,series}
    let showStretched = true;
    let drawLayer = null;

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
      if (!showStretched || !series) return;
      stretchedBands.forEach(function (b) {
        const y1 = series.priceToCoordinate(b.high);
        const y2 = series.priceToCoordinate(b.low);
        if (y1 == null || y2 == null) return;
        const el = document.createElement("div");
        el.className = "chart-vap-band";
        el.style.top = Math.min(y1, y2) + "px";
        el.style.height = Math.max(4, Math.abs(y2 - y1)) + "px";
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
      stretchedLevels = vapFromBars(bars, i0, i1, pointSize);
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
      const p = series.coordinateToPrice(y);
      if (t == null || p == null || !isFinite(p)) return null;
      return { t: t, p: p, x: x, y: y };
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
        const d = distToSegment(x, y, a.x, a.y, b.x, b.y);
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

    function layoutTrendLinesNow() {
      const layer = ensureDrawLayer();
      const w = host.clientWidth || 0;
      const h = host.clientHeight || 0;
      let svg = "";
      svg += '<svg class="chart-draw-svg" width="' + w + '" height="' + h + '" viewBox="0 0 ' + w + " " + h + '">';

      // VAP stretch range (preview or committed)
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

      function paintLine(tl, cls, soft) {
        const a = xyFromPoint(tl.t1, tl.p1);
        const b = xyFromPoint(tl.t2, tl.p2);
        if (!a || !b) return "";
        // Extend slightly past endpoints so a short span is still visible/grabbable.
        const dx = b.x - a.x;
        const dy = b.y - a.y;
        const len = Math.hypot(dx, dy) || 1;
        const pad = Math.max(12, Math.min(40, len * 0.08));
        const ux = dx / len;
        const uy = dy / len;
        const x1 = a.x - ux * pad;
        const y1 = a.y - uy * pad;
        const x2 = b.x + ux * pad;
        const y2 = b.y + uy * pad;
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
      trendLines.forEach(function (tl) {
        svg += paintLine(tl, "chart-draw-trend" + (tl.id === selectedTrendId ? " is-selected" : ""), false);
      });
      if (trendAnchor && trendHover) {
        svg += paintLine({
          id: "",
          t1: trendAnchor.t, p1: trendAnchor.p,
          t2: trendHover.t, p2: trendHover.p
        }, "chart-draw-trend", true);
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
      const tl = { id: id, t1: tt1, p1: pp1, t2: tt2, p2: pp2 };
      trendLines.push(tl);
      selectedTrendId = id;
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
      onChange();
      return existing;
    }

    function removeMa(id) {
      const ix = mas.findIndex(function (m) { return m.id === id; });
      if (ix < 0) return;
      try { chart.removeSeries(mas[ix].series); } catch (_) {}
      mas.splice(ix, 1);
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
      host.classList.toggle("is-trend-tool", mode === "trend");
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
      if (mode === "vap") {
        const pt = pointFromChartParam(param);
        if (!pt) return;
        const t = snapTime(pt.t);
        if (vapAnchor == null) {
          vapAnchor = t;
          vapHover = t;
          selectedTrendId = null;
          layoutTrendLines();
          return;
        }
        if (timeKey(vapAnchor) === timeKey(t)) {
          // Same candle: keep waiting for a different end (or allow single-bar stretch).
          applyVapRange(vapAnchor, t);
          return;
        }
        applyVapRange(vapAnchor, t);
        // Keep tool armed so user can redraw; edges stay editable via handles.
        return;
      }
      if (mode === "trend") {
        const pt = pointFromChartParam(param);
        if (!pt) return;
        if (!trendAnchor) {
          trendAnchor = pt;
          trendHover = null;
          selectedTrendId = null;
          layoutTrendLines();
          return;
        }
        // Ignore accidental double-click on the same bar/price (was creating stubs).
        if (trendAnchor.t === pt.t && Math.abs(trendAnchor.p - pt.p) < 1e-9) return;
        addTrendLine(trendAnchor.t, trendAnchor.p, pt.t, pt.p);
        trendAnchor = null;
        trendHover = null;
        // Keep tool armed — TradingView stays in draw mode until Esc / tool toggle.
        layoutTrendLines();
        return;
      }
      // Idle: select trend line or VAP range.
      if (!param || param.point == null) return;
      const vapEnd = vapHandleHit(param.point.x, param.point.y);
      if (vapFrom != null && vapTo != null) {
        const xs = vapRangeXs(vapFrom, vapTo);
        if (xs && param.point.x >= xs.left - 6 && param.point.x <= xs.right + 6) {
          vapSelected = true;
          selectedTrendId = null;
          layoutTrendLines();
          return;
        }
      }
      if (vapEnd) {
        vapSelected = true;
        layoutTrendLines();
        return;
      }
      const hit = findTrendNear(param.point.x, param.point.y, 9);
      selectedTrendId = hit ? hit.id : null;
      if (hit) vapSelected = false;
      layoutTrendLines();
    }

    function onCrosshairMove(param) {
      if (dragState) return;
      if (mode === "trend" && trendAnchor) {
        const pt = pointFromChartParam(param);
        if (!pt) return;
        trendHover = pt;
        layoutTrendLines();
        return;
      }
      if (mode === "vap" && vapAnchor != null) {
        const pt = pointFromChartParam(param);
        if (!pt) return;
        vapHover = snapTime(pt.t);
        layoutTrendLines();
      }
    }

    function onPointerDown(ev) {
      if (ev.button != null && ev.button !== 0) return;
      const rect = host.getBoundingClientRect();
      const x = ev.clientX - rect.left;
      const y = ev.clientY - rect.top;

      const vapEnd = vapHandleHit(x, y);
      if (vapEnd) {
        dragState = { kind: "vap", end: vapEnd };
        vapSelected = true;
        selectedTrendId = null;
        host.classList.add("is-dragging-draw");
        try { host.setPointerCapture(ev.pointerId); } catch (_) {}
        ev.preventDefault();
        ev.stopPropagation();
        return;
      }

      if (selectedTrendId) {
        const tl = trendLines.find(function (z) { return z.id === selectedTrendId; });
        if (tl) {
          const end = handleHit(tl, x, y);
          if (end) {
            dragState = { kind: "trend", id: tl.id, end: end };
            host.classList.add("is-dragging-draw");
            try { host.setPointerCapture(ev.pointerId); } catch (_) {}
            ev.preventDefault();
            ev.stopPropagation();
          }
        }
      }
    }

    function onPointerMove(ev) {
      if (!dragState) return;
      const pt = pointFromClient(ev.clientX, ev.clientY);
      if (!pt) return;
      if (dragState.kind === "trend") {
        if (dragState.end === "a") updateTrendLine(dragState.id, { t1: pt.t, p1: pt.p });
        else if (dragState.end === "b") updateTrendLine(dragState.id, { t2: pt.t, p2: pt.p });
      } else if (dragState.kind === "vap") {
        const t = snapTime(pt.t);
        if (dragState.end === "from") vapFrom = t;
        else vapTo = t;
        // Live recompute while dragging edges.
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
      try { host.releasePointerCapture(ev.pointerId); } catch (_) {}
      layoutTrendLines();
      if (wasVap && vapFrom != null && vapTo != null) {
        applyVapRange(vapFrom, vapTo); // persist + onChange
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
          if (mode === "trend" || mode === "vap") setMode(null);
          else layoutTrendLines();
          ev.preventDefault();
        } else if (selectedTrendId) {
          selectedTrendId = null;
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
        if (selectedTrendId) {
          removeTrendLine(selectedTrendId);
          ev.preventDefault();
        } else if (vapSelected || vapFrom != null) {
          clearVap();
          ev.preventDefault();
        }
      }
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
    host.addEventListener("pointerdown", onPointerDown);
    host.addEventListener("pointermove", onPointerMove);
    host.addEventListener("pointerup", onPointerUp);
    host.addEventListener("pointercancel", onPointerUp);
    window.addEventListener("keydown", onKeyDown);
    ensureDrawLayer();
    layoutTrendLines();

    function getState() {
      return {
        vapRange: vapFrom != null ? { from: vapFrom, to: vapTo } : null,
        trendLines: trendLines.map(function (tl) {
          return { t1: tl.t1, p1: tl.p1, t2: tl.t2, p2: tl.p2, id: tl.id };
        }),
        mas: mas.map(function (m) {
          return { id: m.id, type: m.type, period: m.period, color: m.color };
        })
      };
    }

    function setState(st) {
      if (!st) return;
      trendLines = [];
      trendAnchor = null;
      trendHover = null;
      selectedTrendId = null;
      clearMas();
      clearVap();
      (st.trendLines || []).forEach(function (tl) {
        addTrendLine(tl.t1, tl.p1, tl.t2, tl.p2, { id: tl.id, silent: true });
      });
      (st.mas || []).forEach(function (m) { upsertMa(m); });
      if (st.vapRange && st.vapRange.from != null && st.vapRange.to != null) {
        applyVapRange(st.vapRange.from, st.vapRange.to);
      }
      layoutTrendLines();
    }

    function setPointSize(ps) {
      const next = ps > 0 ? ps : 0.01;
      if (next === pointSize) return;
      pointSize = next;
      if (vapFrom != null && vapTo != null) {
        applyVapRange(vapFrom, vapTo, { silent: true });
      }
    }

    function refreshOverlays() {
      layoutStretchedVapNow();
      layoutTrendLinesNow();
      // recompute MAs on new bars
      mas.slice().forEach(function (m) {
        upsertMa({ id: m.id, type: m.type, period: m.period, color: m.color });
      });
    }

    function destroy() {
      clearTrendLines();
      clearMas();
      clearVap();
      setMode(null);
      host.removeEventListener("pointerdown", onPointerDown);
      host.removeEventListener("pointermove", onPointerMove);
      host.removeEventListener("pointerup", onPointerUp);
      host.removeEventListener("pointercancel", onPointerUp);
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
      upsertMa: upsertMa,
      removeMa: removeMa,
      clearMas: clearMas,
      getState: getState,
      setState: setState,
      setPointSize: setPointSize,
      refreshOverlays: refreshOverlays,
      layoutStretchedVap: layoutStretchedVap,
      layoutTrendLines: layoutTrendLines,
      destroy: destroy
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
    thinLevels: thinLevels,
    hvnBands: hvnBands,
    sma: sma,
    ema: ema,
    attachTools: attachTools,
    promptMaConfig: promptMaConfig,
    esc: esc
  };
})(typeof window !== "undefined" ? window : globalThis);
