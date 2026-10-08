(function () {
  "use strict";
  const $ = function (id) { return document.getElementById(id); };
  const panes = Object.create(null); // secid -> {el, chart, series, tools, bars}
  let layoutDoc = null;
  let instruments = [];
  let activeId = null;
  let toolbarMode = null; // null | trend | ray | hline | vline | rect | fib | vap | measure
  let saveTimer = null;
  let hydrating = true;
  let fsBusy = false;

  async function authFetch(url, opts) {
    opts = opts || {};
    const headers = (window.TrinityChartKit
      ? TrinityChartKit.authHeaders(opts.headers || {})
      : Object.assign({ Accept: "application/json" }, opts.headers || {}));
    const ms = opts.ms != null ? opts.ms
      : ((window.TrinityFastBoot && TrinityFastBoot.DESK_MS) || 25000);
    const base = { credentials: "include" };
    let res;
    if (window.TrinityFastBoot && typeof TrinityFastBoot.fetchAbort === "function") {
      res = await TrinityFastBoot.fetchAbort(url, Object.assign({}, base, opts, { headers: headers, ms: ms }));
    } else {
      res = await fetch(url, Object.assign({}, base, opts, { headers: headers }));
    }
    if (res.status === 401 || res.status === 403) {
      const cookieHeaders = Object.assign({}, headers);
      delete cookieHeaders.Authorization;
      if (window.TrinityFastBoot && typeof TrinityFastBoot.fetchAbort === "function") {
        res = await TrinityFastBoot.fetchAbort(url, Object.assign({}, base, opts, { headers: cookieHeaders, ms: ms }));
      } else {
        res = await fetch(url, Object.assign({}, base, opts, { headers: cookieHeaders }));
      }
    }
    return res;
  }

  function toChartTime(iso) {
    if (iso == null || iso === "") return null;
    if (typeof iso === "object" && iso.year) return iso;
    if (typeof iso === "number" && isFinite(iso)) {
      return iso > 1e12 ? Math.floor(iso / 1000) : Math.floor(iso);
    }
    const s = String(iso).trim();
    if (/^\d+(\.\d+)?$/.test(s)) {
      const n = Number(s);
      if (!isFinite(n)) return null;
      return n > 1e12 ? Math.floor(n / 1000) : Math.floor(n);
    }
    const ms = Date.parse(s.replace(" ", "T"));
    if (!isFinite(ms)) return null;
    return Math.floor(ms / 1000);
  }

  function mergeLayoutDocs(a, b) {
    const out = Object.assign({}, a || {}, b || {});
    const ia = (a && a.investTerminal) || {};
    const ib = (b && b.investTerminal) || {};
    const by = Object.assign({}, ia.byInstrument || {}, ib.byInstrument || {});
    Object.keys(ia.byInstrument || {}).forEach(function (id) {
      const prev = ia.byInstrument[id];
      const next = by[id];
      if (drawingPayloadEmpty(next) && !drawingPayloadEmpty(prev)) by[id] = prev;
    });
    out.investTerminal = Object.assign({}, ia, ib, { byInstrument: by });
    if (a && a.desk && !out.desk) out.desk = a.desk;
    if (a && a.terminal && !out.terminal) out.terminal = a.terminal;
    const templates = [];
    [a, b].forEach(function (d) {
      (d && Array.isArray(d.templates) ? d.templates : []).forEach(function (t) { templates.push(t); });
    });
    if (templates.length) out.templates = templates;
    return out;
  }

  function scheduleSave() {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(persist, 600);
  }

  function drawingPayloadEmpty(st) {
    if (!st) return true;
    return !(st.marks && st.marks.length)
      && !(st.trendLines && st.trendLines.length)
      && !(st.mas && st.mas.length)
      && !(st.vapRange && st.vapRange.from != null);
  }

  function investLayout(doc) {
    doc = doc || {};
    if (!doc.investTerminal) doc.investTerminal = {};
    return doc.investTerminal;
  }

  async function persist() {
    if (hydrating) return;
    if (!window.TrinityChartKit) return;
    try {
      let cur = {};
      try { cur = await TrinityChartKit.loadLayouts(); } catch (_) { cur = layoutDoc || {}; }
      if (layoutDoc) cur = mergeLayoutDocs(cur, layoutDoc);
      const term = investLayout(cur);
      term.active = activeId;
      term.instruments = Object.keys(panes);
      term.byInstrument = term.byInstrument || {};
      cur.templates = Array.isArray(layoutDoc && layoutDoc.templates)
        ? layoutDoc.templates
        : (Array.isArray(cur.templates) ? cur.templates : []);
      Object.keys(panes).forEach(function (id) {
        const p = panes[id];
        if (p && p.tools) {
          const st = p.tools.getState() || {};
          if (p.flow && typeof p.flow.getState === "function") st.flow = p.flow.getState();
          st.tf = p.tf || "D1";
          const prev = term.byInstrument[id];
          if (!p.layoutDirty && drawingPayloadEmpty(st) && prev && !drawingPayloadEmpty(prev)) {
            st.marks = prev.marks;
            st.trendLines = prev.trendLines;
            st.mas = prev.mas;
            st.vapRange = prev.vapRange;
            if (prev.flow && !st.flow) st.flow = prev.flow;
          }
          term.byInstrument[id] = st;
          p.layoutDirty = false;
        }
        if (p && p.barSpacing > 0) {
          term.scaleByInstrument = term.scaleByInstrument || {};
          term.scaleByInstrument[id] = {
            barSpacing: p.barSpacing,
            logical: p.logical || null
          };
        }
      });
      layoutDoc = await TrinityChartKit.saveLayouts(cur);
      try {
        localStorage.setItem("trinity.invest.layouts.local", JSON.stringify(layoutDoc));
      } catch (_) {}
      const meta = $("charts-terminal-meta");
      if (meta) {
        meta.textContent = "Рисунки сохранены · " + TrinityChartKit.currentUserKey()
          + " · " + (layoutDoc.updatedAt || "");
      }
    } catch (e) {
      console.warn("invest terminal layout save", e);
    }
  }

  function setPressed(btn, on) {
    if (!btn) return;
    btn.classList.toggle("is-active", !!on);
    btn.setAttribute("aria-pressed", on ? "true" : "false");
  }

  function currentPane() {
    if (activeId && panes[activeId]) return panes[activeId];
    const keys = Object.keys(panes);
    if (!keys.length) return null;
    activeId = keys[0];
    return panes[activeId];
  }

  function syncToolButtons() {
    const p = currentPane();
    const drawMode = toolbarMode && toolbarMode !== "measure" ? toolbarMode : null;
    document.querySelectorAll(".charts-tv-btn[data-tool]").forEach(function (btn) {
      const tool = btn.getAttribute("data-tool") || "";
      const on = tool === "" ? !toolbarMode : toolbarMode === tool;
      setPressed(btn, on);
    });
    setPressed($("charts-tool-magnet"), !!(p && p.tools && p.tools.getMagnet && p.tools.getMagnet()));
    setPressed($("charts-tool-hide"), !!(p && p.tools && p.tools.getDrawingsHidden && p.tools.getDrawingsHidden()));
    setPressed($("charts-tool-lock"), !!(p && p.tools && p.tools.getDrawingsLocked && p.tools.getDrawingsLocked()));
    setPressed($("charts-tool-clusters"), !!(p && p.flow && p.flow.getShowClusters && p.flow.getShowClusters()));
    setPressed($("charts-tool-hvol"), !!(p && p.flow && p.flow.getShowProfile && p.flow.getShowProfile()));
    const name = $("charts-active-name");
    if (name) {
      const inst = instruments.find(function (o) { return o.secid === activeId; });
      name.textContent = inst
        ? ((inst.name || inst.family || inst.secid) + " · " + inst.secid)
        : (activeId || "—");
    }
  }

  const DRAW_MODES = { trend: 1, ray: 1, hline: 1, vline: 1, rect: 1, fib: 1, vap: 1 };

  function applyToolbar() {
    Object.keys(panes).forEach(function (id) {
      const p = panes[id];
      if (!p) return;
      const mine = id === activeId;
      if (p.tools && typeof p.tools.setMode === "function") {
        const next = mine && DRAW_MODES[toolbarMode] ? toolbarMode : null;
        if (p.tools.getMode() !== next) p.tools.setMode(next);
      }
      if (p.nav && typeof p.nav.setMeasureMode === "function") {
        const want = mine && toolbarMode === "measure";
        if (p.nav.getMeasureMode() !== want) p.nav.setMeasureMode(want);
      }
      if (p.flow && typeof p.flow.setFpTool === "function") {
        p.flow.setFpTool(mine && toolbarMode === "footprint");
      }
    });
    syncToolButtons();
  }

  function setToolbarMode(next) {
    toolbarMode = next || null;
    applyToolbar();
  }

  function createPane(secid, title) {
    const grid = $("charts-terminal-grid");
    const wrap = document.createElement("article");
    wrap.className = "charts-pane";
    wrap.dataset.secid = secid;
    wrap.innerHTML = '<header class="charts-pane-head">'
      + '<strong>' + (title || secid) + '</strong>'
      + '<button type="button" class="btn btn-ghost btn-xs charts-pane-fs">⛶</button>'
      + '</header>'
      + '<div class="charts-pane-chart" id="pane-chart-' + secid + '"></div>';
    grid.appendChild(wrap);
    wrap.querySelector(".charts-pane-fs").addEventListener("click", function (ev) {
      ev.stopPropagation();
      togglePaneFullscreen(secid);
    });
    wrap.addEventListener("pointerdown", function () {
      if (activeId !== secid) setActive(secid);
    }, true);
    wrap.addEventListener("click", function () {
      setActive(secid);
    });

    const el = wrap.querySelector(".charts-pane-chart");
    const chart = LightweightCharts.createChart(el, {
      width: Math.max(280, el.clientWidth || 480),
      height: Math.max(280, el.clientHeight || 320),
      layout: { backgroundColor: "#ffffff", textColor: "#1a2228" },
      grid: { vertLines: { color: "#eef1f3" }, horzLines: { color: "#eef1f3" } },
      rightPriceScale: { borderColor: "#d5dde2", autoScale: true },
      timeScale: {
        borderColor: "#d5dde2",
        timeVisible: true,
        secondsVisible: false,
        rightOffset: 6,
        lockVisibleTimeRangeOnResize: false,
        barSpacing: 8
      },
      handleScroll: { mouseWheel: true, pressedMouseMove: true, horzTouchDrag: true, vertTouchDrag: false },
      handleScale: { axisPressedMouseMove: true, mouseWheel: true, pinch: true }
    });
    const series = chart.addCandlestickSeries({
      upColor: "#16a34a", downColor: "#dc2626",
      borderUpColor: "#16a34a", borderDownColor: "#dc2626",
      wickUpColor: "#16a34a", wickDownColor: "#dc2626"
    });
    const kit = window.TrinityChartKit;
    const hud = (kit && typeof kit.ensureNavHud === "function")
      ? kit.ensureNavHud(el, { measure: false })
      : {};
    const tools = TrinityChartKit.attachTools({
      chart: chart,
      candleSeries: series,
      hostEl: el,
      pointSize: 0.01,
      overlayId: "vap-" + secid,
      barSec: 300,
      getBars: function () { return (panes[secid] && panes[secid].bars) || []; },
      onChange: function () {
        if (panes[secid]) panes[secid].layoutDirty = true;
        scheduleSave();
        syncToolButtons();
      },
      freezePrice: false,
      legendEl: hud.legendEl || null
    });
    panes[secid] = { wrap: wrap, el: el, chart: chart, series: series, tools: tools, bars: [],
      m5Bars: [], tfCache: {}, ticks: [], tf: "D1",
      scaleLocked: false, barSpacing: null, logical: null, nav: null, flow: null, timeline: null,
      layoutDirty: false };
    if (kit && typeof kit.attachTimelineRail === "function") {
      panes[secid].timeline = kit.attachTimelineRail({ hostEl: el, chart: chart });
    }
    if (kit && typeof kit.attachFlowOverlays === "function") {
      panes[secid].flow = kit.attachFlowOverlays({
        chart: chart,
        series: series,
        hostEl: el,
        barSec: 300,
        pointSize: 0.01,
        timeOf: toChartTime,
        getBars: function () { return (panes[secid] && panes[secid].bars) || []; },
        isDrawing: function () { return !!(tools && tools.getMode()); },
        onChange: function () {
          if (panes[secid]) panes[secid].layoutDirty = true;
          scheduleSave();
          syncToolButtons();
        }
      });
    }
    if (kit && typeof kit.attachFriendlyNav === "function") {
      panes[secid].nav = kit.attachFriendlyNav({
        chart: chart,
        series: series,
        hostEl: el,
        skipOhlcTip: true,
        measure: false,
        legendEl: hud.legendEl,
        lockBtn: hud.lockBtn,
        goLiveBtn: hud.goLiveBtn,
        barSec: 300,
        pointSize: 0.01,
        getBars: function () { return (panes[secid] && panes[secid].bars) || []; },
        isDrawing: function () {
          const p = panes[secid];
          return !!(tools && tools.getMode())
            || !!(p && p.flow && p.flow.getFpTool && p.flow.getFpTool());
        },
        onTimeGesture: function () {
          const snap = snapshotPaneScale(panes[secid]);
          if (snap && snap.barSpacing > 0) {
            panes[secid].barSpacing = snap.barSpacing;
            panes[secid].logical = snap.logical;
            panes[secid].scaleLocked = true;
            scheduleSave();
          }
          if (panes[secid].flow) panes[secid].flow.layout();
        },
        onMeasureMode: function (on) {
          if (on) toolbarMode = "measure";
          else if (toolbarMode === "measure") toolbarMode = null;
          syncToolButtons();
        }
      });
    }
    bindPaneScale(panes[secid]);
    if (typeof ResizeObserver !== "undefined") {
      const ro = new ResizeObserver(function () { resizePane(secid); });
      ro.observe(el);
      panes[secid].ro = ro;
    }
    return panes[secid];
  }

  async function paintPaneFromCache(secid) {
    const p = panes[secid];
    if (!p || !window.TrinityChartKit || typeof TrinityChartKit.barCacheGet !== "function") return;
    const row = await TrinityChartKit.barCacheGet(secid, "D1")
      || await TrinityChartKit.barCacheGet(secid, "W1")
      || await TrinityChartKit.barCacheGet(secid, "H1")
      || await TrinityChartKit.barCacheGet(secid, "M5");
    if (!cacheRowOk(row)) return;
    paintCandles(p, row.raw || row.bars);
    const key = String(row.tf || "D1").toUpperCase();
    p.tfCache = p.tfCache || {};
    p.tfCache[key] = (row.raw && row.raw.length) ? row.raw : sanitizeCandles(row.bars);
  }

  function snapshotPaneScale(p) {
    if (!p || !p.chart) return null;
    try {
      const opt = p.chart.timeScale().options();
      return { barSpacing: opt.barSpacing, logical: p.chart.timeScale().getVisibleLogicalRange() };
    } catch (_) {
      return null;
    }
  }
  function clampPaneSpacing(s) {
    const n = Number(s);
    if (!(n > 0) || !isFinite(n)) return 8;
    return Math.max(2, Math.min(120, n));
  }
  function showRecentBars(p) {
    if (!p || !p.chart) return;
    const n = (p.bars && p.bars.length) || 0;
    const tf = String(p.tf || "D1").toUpperCase();
    let want = 90;
    if (tf === "MN") want = 36;
    else if (tf === "W1") want = 80;
    else if (tf === "H1") want = 120;
    else if (tf === "M5" || tf === "M15") want = 150;
    if (gridFullscreen()) want = Math.round(want * 1.4);
    const vis = Math.min(want, Math.max(12, n));
    try {
      p.chart.timeScale().applyOptions({
        barSpacing: gridFullscreen() ? 10 : (tf === "MN" || tf === "W1" ? 12 : 8),
        rightOffset: 4
      });
      if (n > 0) {
        p.chart.timeScale().setVisibleLogicalRange({ from: n - vis, to: n + 3 });
      }
    } catch (_) {
      try { p.chart.timeScale().fitContent(); } catch (__) {}
    }
    p.scaleLocked = false;
  }

  function restorePaneScale(p) {
    if (!p || !p.chart || !(p.barSpacing > 0)) return false;
    try {
      const spacing = clampPaneSpacing(p.barSpacing);
      p.barSpacing = spacing;
      p.chart.timeScale().applyOptions({ barSpacing: spacing });
      const span = p.logical ? Math.abs(Number(p.logical.to) - Number(p.logical.from)) : 0;
      if (span >= 4) p.chart.timeScale().setVisibleLogicalRange(p.logical);
      else {
        p.logical = null;
        p.chart.timeScale().scrollToRealTime();
      }
      return true;
    } catch (_) {
      return false;
    }
  }
  function bindPaneScale(p) {
    if (!p || !p.el || p.el._trinityScaleBound) return;
    p.el._trinityScaleBound = true;
    const remember = function () {
      requestAnimationFrame(function () {
        const snap = snapshotPaneScale(p);
        if (!snap || !(snap.barSpacing > 0)) return;
        p.barSpacing = clampPaneSpacing(snap.barSpacing);
        p.logical = snap.logical;
        p.scaleLocked = true;
        scheduleSave();
      });
    };
    p.el.addEventListener("wheel", remember, { passive: true });
    p.el.addEventListener("pointerup", remember);
  }

  function setActive(secid) {
    activeId = secid;
    Object.keys(panes).forEach(function (id) {
      panes[id].wrap.classList.toggle("is-active", id === secid);
    });
    const sel = $("charts-instrument");
    if (sel) sel.value = secid;
    applyToolbar();
    const pane = panes[secid];
    if (pane && pane.tf) {
      document.querySelectorAll(".charts-tf-btn").forEach(function (b) {
        b.classList.toggle("is-on", b.getAttribute("data-tf") === pane.tf);
      });
    }
    if (pane && pane.flow && typeof pane.flow.layout === "function") pane.flow.layout();
    const dom = $("charts-dom");
    if (dom) {
      dom.innerHTML = "<div class=\"charts-dock-empty\">подключаем стрим "
        + (secid || "") + "…</div>";
    }
    seedTapeFromBars([secid]);
    scheduleSave();
    renderWatchlist();
  }

  function prefersReducedMotion() {
    try {
      return window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    } catch (_) {
      return false;
    }
  }

  function clearPaneMotion(wrap) {
    if (!wrap) return;
    wrap.style.transform = "";
    wrap.style.transformOrigin = "";
    wrap.style.transition = "";
    wrap.style.boxShadow = "";
    wrap.style.zIndex = "";
    wrap.style.opacity = "";
    wrap.classList.remove("is-fs-motion");
  }

  function workspaceRoot() {
    return document.getElementById("investments-charts-terminal")
      || document.querySelector(".investments-workspace");
  }

  function enterFullscreen(secid) {
    const p = panes[secid];
    if (!p) return;
    const grid = $("charts-terminal-grid");
    const root = workspaceRoot();
    fsBusy = true;
    if (root) root.classList.add("is-chart-fs");
    document.body.classList.add("invest-chart-fs");
    grid.classList.add("is-fullscreen");
    Object.keys(panes).forEach(function (id) {
      panes[id].wrap.classList.toggle("is-fs-target", id === secid);
      panes[id].wrap.hidden = id !== secid;
      if (id !== secid) clearPaneMotion(panes[id].wrap);
    });
    setActive(secid);
    syncFsChrome();
    requestAnimationFrame(function () {
      resizePane(secid);
      requestAnimationFrame(function () {
        resizePane(secid);
        if (!p.scaleLocked) showRecentBars(p);
        fsBusy = false;
      });
    });
  }

  function exitFullscreen() {
    const grid = $("charts-terminal-grid");
    const root = workspaceRoot();
    let targetId = null;
    Object.keys(panes).forEach(function (id) {
      if (panes[id].wrap.classList.contains("is-fs-target")) targetId = id;
    });
    if (root) root.classList.remove("is-chart-fs");
    document.body.classList.remove("invest-chart-fs");
    grid.classList.remove("is-fullscreen");
    Object.keys(panes).forEach(function (id) {
      panes[id].wrap.hidden = false;
      panes[id].wrap.classList.remove("is-fs-target");
      clearPaneMotion(panes[id].wrap);
    });
    syncFsChrome();
    fsBusy = false;
    requestAnimationFrame(function () {
      resizeAll();
      if (targetId) resizePane(targetId);
    });
  }

  function togglePaneFullscreen(secid) {
    if (fsBusy) return;
    if (gridFullscreen() && panes[secid] && panes[secid].wrap.classList.contains("is-fs-target")) {
      exitFullscreen();
      return;
    }
    enterFullscreen(secid);
  }

  function syncFsChrome() {
    const on = gridFullscreen();
    const exitBtn = $("charts-exit-fs");
    if (exitBtn) exitBtn.hidden = !on;
    const topFs = $("charts-fullscreen");
    if (topFs) {
      topFs.textContent = on ? "Сетка графиков" : "Полный экран";
      topFs.setAttribute("aria-pressed", on ? "true" : "false");
    }
    Object.keys(panes).forEach(function (id) {
      const p = panes[id];
      if (!p || !p.wrap) return;
      const btn = p.wrap.querySelector(".charts-pane-fs");
      if (!btn) return;
      const mine = on && p.wrap.classList.contains("is-fs-target");
      btn.textContent = mine ? "❐" : "⛶";
      btn.title = mine ? "Вернуть сетку" : "Развернуть график";
    });
  }

  function showInstrument(secid) {
    if (!secid || !panes[secid]) return;
    setActive(secid);
    if (gridFullscreen()) {
      enterFullscreen(secid);
      return;
    }
    try {
      panes[secid].wrap.scrollIntoView({ block: "nearest", behavior: "smooth" });
    } catch (_) {}
    renderWatchlist();
  }

  var MAX_OPEN_PANES = 6;

  function snapshotPaneIntoLayout(id) {
    const p = panes[id];
    if (!p || !p.tools) return;
    layoutDoc = layoutDoc || {};
    const term = investLayout(layoutDoc);
    term.byInstrument = term.byInstrument || {};
    const st = p.tools.getState() || {};
    if (p.flow && typeof p.flow.getState === "function") st.flow = p.flow.getState();
    st.tf = p.tf || "D1";
    term.byInstrument[id] = st;
    if (p.barSpacing > 0) {
      term.scaleByInstrument = term.scaleByInstrument || {};
      term.scaleByInstrument[id] = { barSpacing: p.barSpacing, logical: p.logical || null };
    }
  }

  function restorePaneFromLayout(secid) {
    const term = investLayout(layoutDoc);
    const pane = panes[secid];
    if (!pane) return;
    const saved = term.byInstrument && term.byInstrument[secid];
    const sc = term.scaleByInstrument && term.scaleByInstrument[secid];
    if (saved && saved.tf) pane.tf = saved.tf;
    if (saved && pane.tools) {
      pane.tools.setState(saved);
      if (saved.flow && pane.flow) pane.flow.setState(saved.flow);
      pane.layoutDirty = false;
    }
    if (sc && sc.barSpacing > 0) {
      pane.scaleLocked = true;
      pane.barSpacing = sc.barSpacing;
      pane.logical = sc.logical || null;
      restorePaneScale(pane);
    } else {
      showRecentBars(pane);
    }
  }

  function destroyPane(secid) {
    const p = panes[secid];
    if (!p) return;
    snapshotPaneIntoLayout(secid);
    try { if (p.ro) p.ro.disconnect(); } catch (_) {}
    try { if (p.chart) p.chart.remove(); } catch (_) {}
    if (p.wrap && p.wrap.parentNode) p.wrap.parentNode.removeChild(p.wrap);
    delete panes[secid];
  }

  async function openInstrument(secid) {
    secid = String(secid || "").trim().toUpperCase();
    if (!secid) return;
    if (panes[secid]) {
      showInstrument(secid);
      return;
    }
    const inst = (instruments || []).find(function (o) {
      return String(o.secid).toUpperCase() === secid;
    }) || { secid: secid, name: secid };
    const openIds = Object.keys(panes);
    if (openIds.length >= MAX_OPEN_PANES) {
      const replaceId = (activeId && panes[activeId]) ? activeId : openIds[0];
      if (replaceId) destroyPane(replaceId);
    }
    createPane(secid, inst.name || secid);
    await paintPaneFromCache(secid);
    restorePaneFromLayout(secid);
    await refreshPane(secid);
    restorePaneFromLayout(secid);
    setActive(secid);
    if (gridFullscreen()) enterFullscreen(secid);
    else {
      try { panes[secid].wrap.scrollIntoView({ block: "nearest", behavior: "smooth" }); } catch (_) {}
    }
    ensureTapeCoverage();
    warmLocalHistory([{ secid: secid }]).catch(function (e) { console.warn("warm", e); });
    renderWatchlist();
    scheduleSave();
  }

  function resizePane(secid) {
    const p = panes[secid];
    if (!p || !p.el || p.wrap.hidden) return;
    const w = Math.max(240, p.el.clientWidth || p.wrap.clientWidth || 480);
    const h = Math.max(240, p.el.clientHeight || 320);
    try { p.chart.applyOptions({ width: w, height: h }); } catch (_) {}
    if (p.tools && typeof p.tools.layoutStretchedVap === "function") p.tools.layoutStretchedVap();
    if (p.flow && typeof p.flow.layout === "function") p.flow.layout();
    if (p.timeline && typeof p.timeline.layout === "function") p.timeline.layout();
  }

  function resizeAll() {
    Object.keys(panes).forEach(function (id) { resizePane(id); });
  }

  function gridFullscreen() {
    return $("charts-terminal-grid").classList.contains("is-fullscreen");
  }

  const BAR_CACHE_V = 4;
  const HISTORY_YEARS = 5;

  function apiTfFor(tf) {
    const u = String(tf || "D1").toUpperCase();
    if (u === "M5" || u === "M15") return "M5";
    if (u === "H1" || u === "RENKO" || u === "RANGE" || u === "TICK") return "H1";
    if (u === "W1" || u === "WEEK" || u === "MN" || u === "MONTH") return "W1";
    return "D1";
  }

  function looksLikeSyntheticStairs(bars) {
    if (!bars || bars.length < 40) return false;
    const first = Number(bars[0].open != null ? bars[0].open : bars[0].close);
    const last = Number(bars[bars.length - 1].close);
    if (!(first > 90 && first < 115 && last > 230 && last < 290)) return false;
    let up = 0;
    for (let i = 1; i < Math.min(bars.length, 80); i++) {
      if (Number(bars[i].close) >= Number(bars[i - 1].close)) up++;
    }
    return up / Math.min(bars.length - 1, 79) > 0.92;
  }

  function aggregateCalendarBars(bars, mode) {
    const out = [];
    let cur = null;
    (bars || []).forEach(function (b) {
      const t = toChartTime(b.time != null ? b.time : b.begin);
      if (t == null) return;
      const d = new Date(t * 1000);
      let bucket;
      if (mode === "month" || mode === "MN") {
        bucket = Math.floor(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 1) / 1000);
      } else {
        const day = d.getUTCDay();
        const diff = (day + 6) % 7;
        bucket = Math.floor(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate() - diff) / 1000);
      }
      const o = Number(b.open), h = Number(b.high), l = Number(b.low), c = Number(b.close);
      if (![o, h, l, c].every(function (x) { return isFinite(x) && x > 0; })) return;
      if (!cur || cur.time !== bucket) {
        if (cur) out.push(cur);
        cur = { time: bucket, open: o, high: h, low: l, close: c, volume: Number(b.volume) || 0 };
      } else {
        if (h > cur.high) cur.high = h;
        if (l < cur.low) cur.low = l;
        cur.close = c;
        cur.volume += Number(b.volume) || 0;
      }
    });
    if (cur) out.push(cur);
    return out;
  }

  async function loadDeskFor(secid, forceTf) {
    const p = panes[secid];
    const tfBtn = document.querySelector("#charts-tf .charts-tf-btn.is-on");
    const tf = forceTf || (p && p.tf) || (tfBtn && tfBtn.getAttribute("data-tf")) || "D1";
    const chartTf = apiTfFor(tf);
    let url = "/api/investments/chart/" + encodeURIComponent(secid) + "?tf=" + encodeURIComponent(chartTf);
    const res = await authFetch(url, { ms: 25000 });
    if (!res.ok) throw new Error("invest chart HTTP " + res.status);
    const data = await res.json();
    if (data && String(data.source || "").toUpperCase() === "SYNTHETIC") {
      data.bars = [];
    }
    if (data && looksLikeSyntheticStairs(data.bars || [])) {
      data.bars = [];
      data.source = "NONE";
    }
    if (data && (!data.bars || !data.bars.length) && data.barsH1 && data.barsH1.length) {
      data.bars = data.barsH1;
      data.timeframe = data.timeframe || "H1";
    }
    return data;
  }

  function deskBarsMatchPane(secid, data, bars) {
    // Equity terminal: never apply FORTS family price gates (SIBN≠Si, ROSN≠BR).
    if (!bars || !bars.length) return false;
    if (looksLikeSyntheticStairs(bars)) return false;
    if (data && data.instrument) {
      const a = String(data.instrument).trim().toUpperCase();
      const b = String(secid || "").trim().toUpperCase();
      if (a && b && a !== b) return false;
    }
    const last = bars[bars.length - 1];
    const px = Number(last && (last.close != null ? last.close : last.value));
    return px > 0 && isFinite(px);
  }

  function sanitizeCandles(raw) {
    const out = [];
    const seen = Object.create(null);
    (raw || []).forEach(function (b) {
      const t = toChartTime(b.time != null ? b.time : b.begin);
      if (t == null) return;
      let o = Number(b.open), h = Number(b.high), l = Number(b.low), c = Number(b.close);
      if (![o, h, l, c].every(function (x) { return isFinite(x) && x > 0; })) return;
      h = Math.max(h, o, c);
      l = Math.min(l, o, c);
      if (h < l) { const tmp = h; h = l; l = tmp; }
      // LW rejects duplicate times — keep last
      seen[t] = { time: t, open: o, high: h, low: l, close: c };
    });
    Object.keys(seen).map(Number).sort(function (a, b) { return a - b; }).forEach(function (k) {
      out.push(seen[k]);
    });
    return out;
  }

  function paintCandles(p, raw, opts) {
    if (!p || !p.series) return false;
    opts = opts || {};
    const candles = sanitizeCandles(raw);
    if (!candles.length) return false;
    if (looksLikeSyntheticStairs(candles)) return false;
    const vols = Object.create(null);
    (raw || []).forEach(function (b) {
      const t = toChartTime(b.time != null ? b.time : b.begin);
      if (t != null) vols[t] = Number(b.volume) || 0;
    });
    p.bars = candles.map(function (c) {
      return {
        time: c.time, open: c.open, high: c.high, low: c.low, close: c.close,
        volume: vols[c.time] || 0
      };
    });
    if (p.line) {
      try { p.chart.removeSeries(p.line); } catch (_) {}
      p.line = null;
    }
    try {
      p.series.applyOptions({
        visible: true,
        upColor: "#16a34a", downColor: "#dc2626",
        borderUpColor: "#16a34a", borderDownColor: "#dc2626",
        wickUpColor: "#16a34a", wickDownColor: "#dc2626"
      });
    } catch (_) {}
    try { p.series.setData(candles); } catch (e) {
      console.warn("paintCandles setData", e);
      return false;
    }
    try { p.series.priceScale().applyOptions({ autoScale: true }); } catch (_) {}
    if (!opts.keepView) {
      p.scaleLocked = false;
      showRecentBars(p);
    } else if (p.scaleLocked && p.barSpacing > 0) {
      restorePaneScale(p);
    }
    const sid = p.wrap && p.wrap.dataset ? p.wrap.dataset.secid : null;
    if (sid) resizePane(sid);
    if (p.tools && typeof p.tools.paintOhlc === "function") p.tools.paintOhlc();
    if (p.tools && typeof p.tools.refreshOverlays === "function") p.tools.refreshOverlays();
    return true;
  }

  function cacheRowOk(row) {
    if (!row || row.v !== BAR_CACHE_V) return false;
    const src = String(row.source || "").toUpperCase();
    if (src === "SYNTHETIC" || src === "NONE") return false;
    const bars = row.raw || row.bars || [];
    if (!bars.length || looksLikeSyntheticStairs(bars)) return false;
    return true;
  }

  async function ensureTfBars(secid, tf, opts) {
    const p = panes[secid];
    if (!p) return [];
    opts = opts || {};
    p.tfCache = p.tfCache || {};
    const key = apiTfFor(tf);
    if (!opts.forceNetwork && p.tfCache[key] && p.tfCache[key].length
        && !looksLikeSyntheticStairs(p.tfCache[key])) {
      return p.tfCache[key];
    }
    if (!opts.forceNetwork && window.TrinityChartKit && typeof TrinityChartKit.barCacheGet === "function") {
      try {
        const row = await TrinityChartKit.barCacheGet(secid, key);
        if (cacheRowOk(row)) {
          const candles = sanitizeCandles(row.raw || row.bars || []);
          if (candles.length) {
            p.tfCache[key] = (row.raw && row.raw.length) ? row.raw : candles;
            if (key === "M5") p.m5Bars = p.tfCache[key].slice();
            return p.tfCache[key];
          }
        }
      } catch (_) {}
    }
    try {
      const data = await loadDeskFor(secid, tf);
      const candles = sanitizeCandles(data.bars || []);
      if (!deskBarsMatchPane(secid, data, candles)) return p.tfCache[key] || [];
      const toolBars = candles.map(function (c) {
        return { time: c.time, open: c.open, high: c.high, low: c.low, close: c.close, volume: 0 };
      });
      const volByT = Object.create(null);
      (data.bars || []).forEach(function (b) {
        const t = toChartTime(b.time);
        if (t != null) volByT[t] = Number(b.volume) || 0;
      });
      toolBars.forEach(function (b) { b.volume = volByT[b.time] || 0; });
      p.tfCache[key] = toolBars;
      if (key === "M5") p.m5Bars = toolBars.slice();
      if (window.TrinityChartKit && typeof TrinityChartKit.barCachePut === "function") {
        TrinityChartKit.barCachePut(secid, key, {
          v: BAR_CACHE_V,
          source: data.source || "LIVE",
          instrument: (data && data.instrument) || secid,
          tf: key,
          bars: candles,
          raw: toolBars
        });
      }
      return toolBars;
    } catch (e) {
      console.warn("ensureTfBars", secid, tf, e);
      return p.tfCache[key] || [];
    }
  }

  async function refreshPane(secid) {
    const p = panes[secid];
    if (!p) return;
    try {
      const wantTf = String((p.tf || "D1")).toUpperCase();
      // Bust in-memory cache so Обновить / bootstrap always pull live bars.
      if (p.tfCache) {
        delete p.tfCache[apiTfFor(wantTf)];
        if (wantTf === "W1" || wantTf === "MN") delete p.tfCache.W1;
      }
      await applyTf(wantTf, secid);
      if (p.flow && typeof p.flow.layout === "function") p.flow.layout();
      if (p.timeline && typeof p.timeline.layout === "function") p.timeline.layout();
      resizePane(secid);
    } catch (e) {
      console.warn("refreshPane", secid, e);
    }
  }

  async function bootstrap() {
    // Paint equity panes immediately — never wait on full /desk scan.
    const FALLBACK = [
      { secid: "SBER", name: "Сбер" },
      { secid: "LKOH", name: "Лукойл" },
      { secid: "GAZP", name: "Газпром" },
      { secid: "GMKN", name: "Норникель" },
      { secid: "MGNT", name: "Магнит" },
      { secid: "MTSS", name: "МТС" }
    ];
    instruments = FALLBACK.slice();
    try {
      const wlRes = await authFetch("/api/investments/watchlist", { ms: 8000 });
      const wl = wlRes.ok ? await wlRes.json() : {};
      if (wl.instruments && wl.instruments.length) {
        instruments = wl.instruments;
      } else if (wl.watchlist && wl.watchlist.length) {
        instruments = wl.watchlist.map(function (t) { return { secid: t, name: t }; });
      }
    } catch (_) {}
    try {
      layoutDoc = await TrinityChartKit.loadLayouts();
    } catch (_) {
      layoutDoc = {};
    }
    try {
      const raw = localStorage.getItem("trinity.invest.layouts.local")
        || localStorage.getItem("trinity.chart.layouts.local");
      if (raw) layoutDoc = mergeLayoutDocs(JSON.parse(raw), layoutDoc);
    } catch (_) {
      if (!layoutDoc) layoutDoc = { investTerminal: {} };
    }
    const term = investLayout(layoutDoc);
    const savedIds = (term.instruments && term.instruments.length) ? term.instruments : null;
    // At most 6 panes on first paint (full universe stays in the select / ops panel).
    const catalog = instruments;
    const list = (savedIds && savedIds.length
      ? savedIds.map(function (id) {
          return catalog.find(function (o) { return o.secid === id; }) || { secid: id, name: id };
        })
      : catalog
    ).slice(0, 6);

    const sel = $("charts-instrument");
    sel.innerHTML = "";
    catalog.forEach(function (o) {
      const opt = document.createElement("option");
      opt.value = o.secid;
      opt.textContent = (o.name || o.family || o.secid) + " · " + o.secid;
      sel.appendChild(opt);
    });
    list.forEach(function (o) {
      createPane(o.secid, o.name || o.secid);
    });

    activeId = term.active || list[0].secid;
    if (!panes[activeId]) activeId = list[0].secid;
    setActive(activeId);

    await Promise.all(list.map(function (o) { return paintPaneFromCache(o.secid); }));

    // Parallel live refresh — then restore drawings/MA/VAP from investTerminal.
    await Promise.allSettled(list.map(async function (o) {
      const by = term.byInstrument || {};
      const sc = (term.scaleByInstrument || {})[o.secid];
      const pane = panes[o.secid];
      const saved = by[o.secid];
      if (saved && saved.tf && pane) pane.tf = saved.tf;
      await refreshPane(o.secid);
      if (saved && panes[o.secid]) {
        panes[o.secid].tools.setState(saved);
        if (saved.flow && panes[o.secid].flow) {
          panes[o.secid].flow.setState(saved.flow);
        }
        panes[o.secid].layoutDirty = false;
      }
      if (sc && sc.barSpacing > 0 && panes[o.secid]) {
        panes[o.secid].scaleLocked = true;
        panes[o.secid].barSpacing = sc.barSpacing;
        panes[o.secid].logical = sc.logical || null;
        restorePaneScale(panes[o.secid]);
      } else if (panes[o.secid]) {
        showRecentBars(panes[o.secid]);
      }
    }));
    resizeAll();
    syncToolButtons();
    syncFsChrome();
    hydrating = false;
    scheduleSave();
    bindTerminalTape(catalog);
    renderWatchlist();
    // Operator / any subscription: warm multi-year D1 (+H1) into IndexedDB for experiments.
    warmLocalHistory(list).catch(function (e) { console.warn("warmLocalHistory", e); });
    if (window.TrinityPlaques && typeof window.TrinityPlaques.refresh === "function") {
      window.TrinityPlaques.refresh();
    }
  }

  function historyFresh(row, minBars, minYears) {
    if (!cacheRowOk(row)) return false;
    const needYears = minYears != null ? minYears : HISTORY_YEARS;
    if (needYears > 0 && !(Number(row.years) >= needYears)) return false;
    const bars = row.raw || row.bars || [];
    if (bars.length < (minBars || 400)) return false;
    const age = Date.now() - Number(row.savedAt || 0);
    return age >= 0 && age < 20 * 60 * 60 * 1000;
  }

  async function storeWarmBars(secid, tf, data, years) {
    const candles = sanitizeCandles((data && data.bars) || []);
    if (!deskBarsMatchPane(secid, data, candles)) return 0;
    const toolBars = candles.map(function (c) {
      return { time: c.time, open: c.open, high: c.high, low: c.low, close: c.close, volume: 0 };
    });
    const volByT = Object.create(null);
    (data.bars || []).forEach(function (b) {
      const t = toChartTime(b.time);
      if (t != null) volByT[t] = Number(b.volume) || 0;
    });
    toolBars.forEach(function (b) { b.volume = volByT[b.time] || 0; });
    const p = panes[secid];
    if (p) {
      p.tfCache = p.tfCache || {};
      p.tfCache[tf] = toolBars;
      if (tf === "M5") p.m5Bars = toolBars.slice();
    }
    if (window.TrinityChartKit && typeof TrinityChartKit.barCachePut === "function") {
      await TrinityChartKit.barCachePut(secid, tf, {
        v: BAR_CACHE_V,
        years: years || 0,
        source: (data && data.source) || "LIVE",
        instrument: secid,
        tf: tf,
        bars: candles,
        raw: toolBars,
        savedAt: Date.now()
      });
    }
    return toolBars.length;
  }

  async function warmLocalHistory(list) {
    const meta = $("charts-terminal-meta");
    let entitlement = {
      allowed: false, years: HISTORY_YEARS, h1Days: 400, m5Days: 20, localArchive: true, reason: "login"
    };
    try {
      const res = await authFetch("/api/investments/history-cache", { ms: 8000 });
      if (res.ok) entitlement = await res.json();
    } catch (_) {}
    if (!entitlement.allowed) {
      if (meta) {
        meta.textContent = "Кэш истории недоступен (" + (entitlement.reason || "locked")
          + ") — нужна подписка или вход оператора.";
      }
      return;
    }
    const years = Number(entitlement.years) > 0 ? Number(entitlement.years) : HISTORY_YEARS;
    const ids = (list || []).map(function (o) { return o.secid; }).filter(Boolean);
    if (!ids.length) return;
    if (meta) {
      meta.textContent = "Локальный архив истории (" + years + "г D1 / H1 / M5) · 0/" + ids.length
        + " · на этот компьютер · " + (entitlement.reason || "ok");
    }
    let done = 0;
    async function pullTf(id, tf, y, minBars, minYears) {
      let row = null;
      if (window.TrinityChartKit && typeof TrinityChartKit.barCacheGet === "function") {
        row = await TrinityChartKit.barCacheGet(id, tf);
      }
      if (historyFresh(row, minBars, minYears)) {
        if (panes[id] && tf === "D1") {
          panes[id].tfCache = panes[id].tfCache || {};
          panes[id].tfCache.D1 = (row.raw && row.raw.length) ? row.raw : sanitizeCandles(row.bars);
        }
        return row;
      }
      const q = y > 0 ? ("?tf=" + tf + "&years=" + y) : ("?tf=" + tf);
      const res = await authFetch(
        "/api/investments/chart/" + encodeURIComponent(id) + q,
        { ms: 60000 }
      );
      if (!res.ok) return null;
      const data = await res.json();
      await storeWarmBars(id, tf, data, y > 0 ? y : 1);
      return data;
    }
    async function one(id) {
      try {
        const data = await pullTf(id, "D1", years, 500, years);
        if (data && panes[id]) {
          const d1 = panes[id].tfCache && panes[id].tfCache.D1
            ? panes[id].tfCache.D1
            : sanitizeCandles(data.bars || []);
          const w1 = aggregateCalendarBars(d1 || [], "week");
          const mn = aggregateCalendarBars(d1 || [], "month");
          if (w1.length) {
            await storeWarmBars(id, "W1", { bars: w1, source: data.source, instrument: id }, years);
          }
          if (mn.length && window.TrinityChartKit) {
            await TrinityChartKit.barCachePut(id, "MN", {
              v: BAR_CACHE_V, years: years, source: data.source, instrument: id, tf: "MN",
              bars: sanitizeCandles(mn), raw: mn, savedAt: Date.now()
            });
          }
        }
        await pullTf(id, "H1", 1, 200, 1);
        await pullTf(id, "M5", 0, 80, 0);
      } catch (e) {
        console.warn("warm", id, e);
      } finally {
        done++;
        if (meta) {
          meta.textContent = "Архив на диске → браузер " + done + "/" + ids.length
            + " · D1/H1/M5 · " + (entitlement.reason || "");
        }
      }
    }
    // Two at a time — don't stampede ISS / T-Invest on first fill.
    const q = ids.slice();
    async function worker() {
      while (q.length) {
        const id = q.shift();
        if (id) await one(id);
      }
    }
    await Promise.all([worker(), worker()]);
    if (meta) {
      meta.textContent = "Локальный архив готов · " + ids.length + " тикеров · "
        + years + "г D1 + H1 + M5 (остаётся после обновления приложения) · "
        + (entitlement.user || entitlement.reason || "");
    }
  }

  function tapeIds() {
    const fromCat = (instruments || []).map(function (o) { return o.secid; }).filter(Boolean);
    return fromCat.length ? fromCat : Object.keys(panes);
  }

  function ensureTapeCoverage() {
    const kit = window.TrinityChartKit;
    const ids = tapeIds();
    if (kit && kit.tape) kit.tape.subscribe(ids, { all: false });
    pollEquityLast(ids);
  }

  function bindTerminalTape(list) {
    const kit = window.TrinityChartKit;
    if (!kit || !kit.tape) return;
    const ids = (list || []).map(function (o) { return o.secid; }).filter(Boolean);
    // Exact tickers only — {all:true} without ids never warmed equity FIGIs.
    kit.tape.subscribe(ids.length ? ids : tapeIds(), { all: false });
    kit.tape.onTrade(function (msg) {
      const px = Number(msg.px);
      if (!(px > 0)) return;
      const inst = String(msg.instrument || "").toUpperCase();
      noteWatch(msg);
      checkAlerts(msg);
      Object.keys(panes).forEach(function (id) {
        // Equity: match ticker exactly (FORTS familyOf would map SIBN→Si).
        if (String(id).toUpperCase() !== inst) return;
        const p = panes[id];
        if (!p || !p.series) return;
        if (!p.ticks) p.ticks = [];
        p.ticks.push({ time: Math.floor(Date.now() / 1000), value: px });
        if (p.ticks.length > 400) p.ticks = p.ticks.slice(-400);
        const tf = p.tf || "M5";
        if (p.m5Bars && p.m5Bars.length) {
          kit.applyTradeToCandle({ update: function () {} }, p.m5Bars, px, 5);
        }
        if (tf === "M5" || tf === "M15" || tf === "H1") {
          kit.applyTradeToCandle(p.series, p.bars, px, tf === "H1" ? 60 : (tf === "M15" ? 15 : 5));
        } else if (tf === "TICK" && p.line) {
          try { p.line.update(p.ticks[p.ticks.length - 1]); } catch (_) {}
        } else if (tf === "RENKO" || tf === "RANGE") {
          rebuildSpecialTf(p, tf);
        }
        if (p.flow && typeof p.flow.ingestPrint === "function") {
          p.flow.ingestPrint(px, msg.qty || 1, msg.side, Math.floor(Date.now() / 1000));
        }
        if (p.tools && typeof p.tools.paintOhlc === "function") p.tools.paintOhlc();
      });
      if (String(activeId || "").toUpperCase() === inst) {
        appendTermTape(msg);
      }
    });
    kit.tape.onBook(function (book) {
      if (String(activeId || "").toUpperCase() !== String(book.instrument || "").toUpperCase()) return;
      renderTermDom(book);
    });
    seedTapeFromBars(ids);
    pollEquityLast(ids);
    renderWatchlist();
    renderAlerts();
    renderTemplates();
  }

  function seedTapeFromBars(ids) {
    (ids || Object.keys(panes)).forEach(function (id) {
      const p = panes[id];
      const bars = p && p.bars;
      if (!bars || !bars.length) return;
      const last = bars[bars.length - 1];
      const px = Number(last.close);
      if (!(px > 0)) return;
      noteWatch({ instrument: id, px: px });
      if (String(id).toUpperCase() === String(activeId || "").toUpperCase()) {
        const dom = $("charts-dom");
        if (dom && /ждём ленту/i.test(dom.textContent || "")) {
          dom.innerHTML = "<div class=\"charts-dock-empty\">подключаем стрим " + id + "…</div>";
        }
      }
    });
  }

  let equityPollTimer = 0;
  function pollEquityLast(ids) {
    const list = (ids || Object.keys(panes)).slice();
    if (!list.length) return;
    async function tick() {
      await Promise.allSettled(list.map(async function (id) {
        try {
          const res = await authFetch("/api/marketdata/iss-last?secid=" + encodeURIComponent(id), { ms: 2500 });
          if (!res.ok) return;
          const d = await res.json();
          const px = Number(d && d.px);
          if (px > 0) noteWatch({ instrument: id, px: px });
        } catch (_) {}
      }));
    }
    tick();
    clearInterval(equityPollTimer);
    equityPollTimer = setInterval(tick, 8000);
  }

  const quotes = Object.create(null);
  function noteWatch(msg) {
    const px = Number(msg.px);
    if (!(px > 0)) return;
    const id = String(msg.instrument || "").trim().toUpperCase();
    if (!id) return;
    // Investments panes are equities — never fold SIBN into Si via familyOf.
    let row = quotes[id];
    if (!row) row = quotes[id] = { px: 0, prev: 0, inst: id };
    if (row.px > 0) row.prev = row.px;
    row.px = px;
    row.inst = id;
    renderWatchlist();
  }
  function deskVerdict(id) {
    const desk = window.__investDeskCache;
    const rows = desk && desk.candidates;
    if (!rows || !rows.length) return "";
    const hit = rows.find(function (r) { return String(r.ticker || "").toUpperCase() === id; });
    return hit && hit.verdict ? String(hit.verdict) : "";
  }

  function renderWatchlist() {
    const el = $("charts-watchlist");
    if (!el) return;
    const catalog = (instruments && instruments.length)
      ? instruments.map(function (o) { return String(o.secid || "").toUpperCase(); }).filter(Boolean)
      : Object.keys(panes);
    const open = Object.keys(panes);
    const seen = Object.create(null);
    const ids = [];
    function push(id) {
      if (!id || seen[id]) return;
      seen[id] = 1;
      ids.push(id);
    }
    if (activeId) push(String(activeId).toUpperCase());
    open.forEach(function (id) { push(String(id).toUpperCase()); });
    catalog.forEach(push);
    const nEl = $("charts-watch-n");
    if (nEl) nEl.textContent = String(ids.length);
    el.innerHTML = ids.map(function (id) {
      const q = quotes[id];
      const px = q ? q.px : 0;
      const up = q && q.prev && px >= q.prev;
      const opened = !!panes[id];
      const v = deskVerdict(id);
      const cls = "charts-watch-row"
        + (id === activeId ? " is-on" : "")
        + (opened ? " is-open" : "");
      return "<div class=\"" + cls + "\" data-id=\"" + id + "\">"
        + "<span>" + id
        + (v && v !== "skip" ? "<span class=\"v is-" + v + "\">" + v + "</span>" : "")
        + "</span><span class=\"charts-watch-px " + (up ? "is-up" : "is-down") + "\">"
        + (px > 0 ? px.toFixed(2) : "—") + "</span></div>";
    }).join("");
    el.querySelectorAll(".charts-watch-row").forEach(function (row) {
      row.addEventListener("click", function () {
        openInstrument(row.getAttribute("data-id")).catch(function (e) { console.warn(e); });
      });
    });
  }
  function appendTermTape(msg) {
    const el = $("charts-tape");
    if (!el) return;
    const row = document.createElement("div");
    const sell = String(msg.side || "").toUpperCase() === "SELL";
    row.className = "charts-tape-row " + (sell ? "is-sell" : "is-buy");
    row.textContent = Number(msg.px).toFixed(2) + " ×" + (msg.qty || "");
    el.insertBefore(row, el.firstChild);
    while (el.childNodes.length > 80) el.removeChild(el.lastChild);
  }
  function renderTermDom(book) {
    const el = $("charts-dom");
    if (!el) return;
    const kit = window.TrinityChartKit;
    if (renderTermDom._last && book && kit && typeof kit.sameTapeInstrument === "function"
        && book.instrument && renderTermDom._last.instrument
        && !kit.sameTapeInstrument(renderTermDom._last.instrument, book.instrument)) {
      renderTermDom._last = null;
    }
    if (kit && typeof kit.mergeDomBook === "function") {
      book = kit.mergeDomBook(renderTermDom._last, book);
    }
    if (book && book.instrument) renderTermDom._last = book;
    const SIDE = 8;
    function pad(rows) {
      const out = (rows || []).slice(0, SIDE);
      while (out.length < SIDE) out.push({ p: NaN, q: 0 });
      return out;
    }
    const asks = pad(book && book.asks).reverse();
    const bids = pad(book && book.bids);
    function row(lv, cls) {
      const empty = !(Number(lv.p) > 0);
      return "<div class=\"charts-dom-row " + cls + (empty ? " is-pad" : "") + "\"><span>"
        + (empty ? "" : (lv.q || "")) + "</span><span>"
        + (empty ? "" : Number(lv.p).toFixed(2)) + "</span><span></span></div>";
    }
    el.innerHTML = asks.map(function (lv) { return row(lv, "is-ask"); }).join("")
      + bids.map(function (lv) { return row(lv, "is-bid"); }).join("");
  }
  function alertKey() { return "trinity.chart.alerts"; }
  function loadAlerts() {
    try { return JSON.parse(localStorage.getItem(alertKey()) || "[]"); } catch (_) { return []; }
  }
  function saveAlerts(list) {
    try { localStorage.setItem(alertKey(), JSON.stringify(list || [])); } catch (_) {}
  }
  function renderAlerts() {
    const el = $("charts-alerts");
    if (!el) return;
    const list = loadAlerts();
    el.innerHTML = list.map(function (a, i) {
      return "<div class=\"charts-alert-item\" data-i=\"" + i + "\">" + (a.instrument || "") + " "
        + (a.dir === "below" ? "≤" : "≥") + " " + a.price + " <span data-del=\"" + i + "\">×</span></div>";
    }).join("");
    el.querySelectorAll("[data-del]").forEach(function (x) {
      x.addEventListener("click", function (ev) {
        ev.stopPropagation();
        const list2 = loadAlerts();
        list2.splice(Number(x.getAttribute("data-del")), 1);
        saveAlerts(list2);
        renderAlerts();
      });
    });
  }
  function checkAlerts(msg) {
    const px = Number(msg.px);
    const list = loadAlerts();
    let changed = false;
    list.forEach(function (a) {
      if (!a || a.fired) return;
      if (!window.TrinityChartKit.sameTapeInstrument(a.instrument, msg.instrument)) return;
      const hit = a.dir === "below" ? px <= a.price : px >= a.price;
      if (!hit) return;
      a.fired = true;
      changed = true;
      try { window.alert("Алерт " + a.instrument + " " + px); } catch (_) {}
    });
    if (changed) saveAlerts(list.filter(function (a) { return !a.fired; }));
    if (changed) renderAlerts();
  }
  function rebuildSpecialTf(p, tf) {
    const kit = window.TrinityChartKit;
    if (!p || !kit) return;
    const src = (p.tfCache && (p.tfCache.H1 || p.tfCache.M5 || p.tfCache.D1 || p.tfCache.W1)) || [];
    if (!src.length) return;
    let out = src;
    if (tf === "RENKO" && kit.buildRenko) out = kit.buildRenko(src, 0.04);
    else if (tf === "RANGE" && kit.buildRangeBars) out = kit.buildRangeBars(src, 0.08);
    paintCandles(p, out);
  }

  function tickLineFromBars(bars) {
    return (bars || []).slice(-240).map(function (b) {
      return { time: toChartTime(b.time), value: Number(b.close) };
    }).filter(function (x) { return x.time != null && x.value > 0; });
  }

  let tfSeq = 0;
  async function applyTf(tf, paneId) {
    const target = paneId || activeId;
    tf = String(tf || "D1").toUpperCase();
    if (tf === "WEEK") tf = "W1";
    if (tf === "MONTH") tf = "MN";
    if (!paneId || target === activeId) {
      document.querySelectorAll(".charts-tf-btn").forEach(function (b) {
        b.classList.toggle("is-on", b.getAttribute("data-tf") === tf);
        b.classList.toggle("is-loading", false);
      });
      const onBtn = document.querySelector('.charts-tf-btn[data-tf="' + tf + '"]');
      if (onBtn) onBtn.classList.add("is-loading");
    }
    const kit = window.TrinityChartKit;
    const p = panes[target];
    if (!p) return;
    p.tf = tf;
    const seq = ++tfSeq;
    try {
      if (tf === "TICK") {
        const base = (p.tfCache && (p.tfCache.H1 || p.tfCache.M5 || p.tfCache.D1)) || p.bars || [];
        if (!base.length) await ensureTfBars(target, "H1", { forceNetwork: true });
        const srcBars = (p.tfCache && (p.tfCache.H1 || p.tfCache.M5 || p.tfCache.D1)) || p.bars || [];
        const lineData = (p.ticks && p.ticks.length) ? p.ticks : tickLineFromBars(srcBars);
        if (!p.line) {
          p.line = p.chart.addLineSeries({ color: "#0f766e", lineWidth: 2 });
        }
        try { p.series.applyOptions({ visible: false }); } catch (_) {}
        try { p.line.applyOptions({ visible: true }); } catch (_) {}
        try { p.line.setData(lineData); } catch (_) {}
        try { p.chart.timeScale().fitContent(); } catch (_) {}
        if (p.tools && typeof p.tools.paintOhlc === "function") p.tools.paintOhlc();
        return;
      }

      let src = [];
      if (tf === "D1" || tf === "H1" || tf === "M5") {
        src = await ensureTfBars(target, tf, { forceNetwork: !p.tfCache || !p.tfCache[apiTfFor(tf)] });
      } else if (tf === "W1" || tf === "MN") {
        src = await ensureTfBars(target, "W1", { forceNetwork: !p.tfCache || !p.tfCache.W1 });
        if (!src.length) src = await ensureTfBars(target, "D1", { forceNetwork: true });
        src = aggregateCalendarBars(src, tf === "MN" ? "month" : "week");
      } else if (tf === "M15") {
        src = await ensureTfBars(target, "M5", { forceNetwork: !p.tfCache || !p.tfCache.M5 });
        if (src.length && kit && kit.aggregateBars) src = kit.aggregateBars(src, 15);
        if (!src.length) src = await ensureTfBars(target, "H1", { forceNetwork: true });
      } else if (tf === "RENKO" || tf === "RANGE") {
        src = await ensureTfBars(target, "H1", { forceNetwork: !p.tfCache || !p.tfCache.H1 });
        if (!src.length) src = await ensureTfBars(target, "D1", { forceNetwork: true });
        if (tf === "RENKO" && kit && kit.buildRenko) src = kit.buildRenko(src, 0.04);
        else if (tf === "RANGE" && kit && kit.buildRangeBars) src = kit.buildRangeBars(src, 0.08);
      } else {
        src = (p.tfCache && (p.tfCache[apiTfFor(tf)] || p.tfCache.D1)) || p.bars || [];
      }
      if (seq !== tfSeq && target === activeId) return;
      if (!src.length) return;
      paintCandles(p, src);
      if (p.flow && typeof p.flow.layout === "function") p.flow.layout();
      resizePane(target);
    } finally {
      document.querySelectorAll(".charts-tf-btn.is-loading").forEach(function (b) {
        b.classList.remove("is-loading");
      });
      if (!hydrating) scheduleSave();
    }
  }
  function renderTemplates() {
    const sel = $("charts-templates");
    if (!sel) return;
    const tpls = (layoutDoc && layoutDoc.templates) || [];
    sel.innerHTML = tpls.map(function (t, i) {
      return "<option value=\"" + i + "\">" + (t.name || ("шаблон " + (i + 1))) + "</option>";
    }).join("") || "<option value=\"\">нет шаблонов</option>";
  }

  $("charts-instrument").addEventListener("change", function () {
    openInstrument($("charts-instrument").value).catch(function (e) { console.warn(e); });
  });
  document.querySelectorAll(".charts-tf-btn").forEach(function (b) {
    b.addEventListener("click", function () {
      applyTf(b.getAttribute("data-tf") || "D1");
    });
  });
  const alertAdd = $("charts-alert-add");
  if (alertAdd) {
    alertAdd.addEventListener("click", function () {
      const pxEl = $("charts-alert-px");
      const dirEl = $("charts-alert-dir");
      const px = Number(pxEl && pxEl.value);
      if (!(px > 0) || !activeId) return;
      const dir = (dirEl && dirEl.value) || "above";
      const list = loadAlerts();
      list.push({ instrument: activeId, price: px, dir: dir === "below" ? "below" : "above" });
      saveAlerts(list);
      if (pxEl) pxEl.value = "";
      renderAlerts();
    });
  }
  const tplSave = $("charts-tpl-save");
  if (tplSave) {
    tplSave.addEventListener("click", function () {
      const p = currentPane();
      if (!p || !p.tools) return;
      const name = window.prompt("Имя шаблона", "шаблон");
      if (!name) return;
      layoutDoc = layoutDoc || {};
      layoutDoc.templates = layoutDoc.templates || [];
      layoutDoc.templates.push({
        name: String(name).slice(0, 64),
        drawings: p.tools.getState(),
        flow: (p.flow && typeof p.flow.getState === "function") ? p.flow.getState() : null,
        tf: p.tf || "M5"
      });
      persist();
      renderTemplates();
    });
  }
  const tplApply = $("charts-tpl-apply");
  if (tplApply) {
    tplApply.addEventListener("click", function () {
      const sel = $("charts-templates");
      const tpls = (layoutDoc && layoutDoc.templates) || [];
      const t = tpls[Number(sel && sel.value)];
      const p = currentPane();
      if (!t || !p) return;
      if (t.drawings && p.tools && typeof p.tools.setState === "function") p.tools.setState(t.drawings);
      if (t.flow && p.flow && typeof p.flow.setState === "function") p.flow.setState(t.flow);
      if (t.tf) applyTf(t.tf);
      if (p) p.layoutDirty = true;
      scheduleSave();
    });
  }
  document.querySelectorAll(".charts-tv-btn[data-tool]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      const tool = btn.getAttribute("data-tool") || "";
      if (!tool) {
        setToolbarMode(null);
        return;
      }
      setToolbarMode(toolbarMode === tool ? null : tool);
    });
  });
  $("charts-tool-ma").addEventListener("click", function () {
    const p = currentPane();
    if (!p) return;
    const cfg = TrinityChartKit.promptMaConfig({ type: "SMA", period: 20 });
    if (!cfg) return;
    p.tools.upsertMa(cfg);
  });
  $("charts-tool-clusters").addEventListener("click", function () {
    const p = currentPane();
    if (!p || !p.flow) return;
    p.flow.setShowClusters(!p.flow.getShowClusters());
    if (p.flow.getShowClusters() && p.chart) {
      try {
        const sp = p.chart.timeScale().options().barSpacing;
        const lr = p.chart.timeScale().getVisibleLogicalRange();
        if (sp > 0) {
          p.barSpacing = sp;
          p.logical = lr;
          p.scaleLocked = true;
        }
      } catch (_) {}
    }
    syncToolButtons();
  });
  $("charts-tool-hvol").addEventListener("click", function () {
    const p = currentPane();
    if (!p || !p.flow) return;
    p.flow.setShowProfile(!p.flow.getShowProfile());
    syncToolButtons();
  });
  $("charts-tool-magnet").addEventListener("click", function () {
    const p = currentPane();
    if (!p || !p.tools || !p.tools.setMagnet) return;
    p.tools.setMagnet(!p.tools.getMagnet());
    syncToolButtons();
  });
  $("charts-tool-hide").addEventListener("click", function () {
    const p = currentPane();
    if (!p || !p.tools || !p.tools.setDrawingsHidden) return;
    p.tools.setDrawingsHidden(!p.tools.getDrawingsHidden());
    syncToolButtons();
  });
  $("charts-tool-lock").addEventListener("click", function () {
    const p = currentPane();
    if (!p || !p.tools || !p.tools.setDrawingsLocked) return;
    p.tools.setDrawingsLocked(!p.tools.getDrawingsLocked());
    syncToolButtons();
  });
  $("charts-tool-delete").addEventListener("click", function () {
    const p = currentPane();
    if (!p) return;
    const gone = p.tools && p.tools.deleteSelected && p.tools.deleteSelected();
    if (!gone && p.nav) {
      p.nav.clearMeasure();
      if (toolbarMode === "measure") setToolbarMode(null);
    }
    if (!gone && p.flow && typeof p.flow.clearFp === "function") p.flow.clearFp();
    scheduleSave();
    syncToolButtons();
  });
  $("charts-tool-clear").addEventListener("click", function () {
    const p = currentPane();
    if (!p) return;
    const label = ($("charts-active-name") && $("charts-active-name").textContent) || activeId;
    if (!window.confirm("Стереть все рисунки на «" + label + "»? Средние (MA) тоже снимутся. Это только этот график.")) return;
    p.tools.clearVap();
    p.tools.clearTrendLines();
    if (p.tools.clearMarks) p.tools.clearMarks();
    p.tools.clearMas();
    if (p.flow && typeof p.flow.clearFp === "function") p.flow.clearFp();
    if (p.nav) {
      p.nav.setMeasureMode(false);
      p.nav.clearMeasure();
    }
    toolbarMode = null;
    applyToolbar();
    scheduleSave();
  });
  $("charts-fullscreen").addEventListener("click", function () {
    togglePaneFullscreen(activeId);
  });
  $("charts-exit-fs").addEventListener("click", exitFullscreen);
  $("charts-fit").addEventListener("click", function () {
    Object.keys(panes).forEach(function (id) {
      const p = panes[id];
      if (!p) return;
      p.scaleLocked = false;
      p.barSpacing = null;
      p.logical = null;
      try { p.series.applyOptions({ autoscaleInfoProvider: undefined }); } catch (_) {}
      try { p.series.priceScale().applyOptions({ autoScale: true }); } catch (_) {}
      showRecentBars(p);
      if (p.flow) p.flow.layout();
    });
    scheduleSave();
  });
  $("charts-refresh").addEventListener("click", async function () {
    for (const id of Object.keys(panes)) await refreshPane(id);
  });
  window.addEventListener("resize", resizeAll);
  window.addEventListener("pagehide", function () {
    if (hydrating) return;
    persist();
  });
  document.addEventListener("visibilitychange", function () {
    if (document.visibilityState === "hidden") persist();
  });
  document.addEventListener("keydown", function (ev) {
    if (ev.key === "Escape") {
      if (gridFullscreen()) exitFullscreen();
      else if (toolbarMode) setToolbarMode(null);
    }
  });

  window.TrinityInvestCharts = {
    openInstrument: openInstrument,
    refreshWatchlist: renderWatchlist
  };

  bootstrap().catch(function (e) {
    const meta = $("charts-terminal-meta");
    if (meta) meta.textContent = "Ошибка загрузки: " + (e && e.message ? e.message : e);
  });
})();
