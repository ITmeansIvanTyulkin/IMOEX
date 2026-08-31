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
    const base = { credentials: "include" };
    let res = await fetch(url, Object.assign({}, base, opts, { headers: headers }));
    if (res.status === 401 || res.status === 403) {
      const cookieHeaders = Object.assign({}, headers);
      delete cookieHeaders.Authorization;
      res = await fetch(url, Object.assign({}, base, opts, { headers: cookieHeaders }));
    }
    return res;
  }

  function toChartTime(iso) {
    if (!iso) return null;
    const ms = Date.parse(String(iso).replace(" ", "T"));
    if (!isFinite(ms)) return null;
    return Math.floor(ms / 1000);
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

  async function persist() {
    if (hydrating) return;
    if (!window.TrinityChartKit) return;
    try {
      const cur = layoutDoc || await TrinityChartKit.loadLayouts();
      cur.terminal = cur.terminal || {};
      cur.terminal.active = activeId;
      cur.terminal.instruments = instruments.map(function (o) { return o.secid; });
      cur.terminal.byInstrument = cur.terminal.byInstrument || {};
      Object.keys(panes).forEach(function (id) {
        const p = panes[id];
        if (p && p.tools) {
          const st = p.tools.getState() || {};
          if (p.flow && typeof p.flow.getState === "function") st.flow = p.flow.getState();
          const prev = cur.terminal.byInstrument[id];
          if (!p.layoutDirty && drawingPayloadEmpty(st) && prev && !drawingPayloadEmpty(prev)) {
            st.marks = prev.marks;
            st.trendLines = prev.trendLines;
            st.mas = prev.mas;
            st.vapRange = prev.vapRange;
            if (prev.flow && !st.flow) st.flow = prev.flow;
          }
          cur.terminal.byInstrument[id] = st;
        }
        if (p && p.scaleLocked && p.barSpacing > 0) {
          cur.terminal.scaleByInstrument = cur.terminal.scaleByInstrument || {};
          cur.terminal.scaleByInstrument[id] = {
            barSpacing: p.barSpacing,
            logical: p.logical || null
          };
        }
      });
      layoutDoc = await TrinityChartKit.saveLayouts(cur);
      const meta = $("charts-terminal-meta");
      if (meta) {
        meta.textContent = "Сохранено · " + TrinityChartKit.currentUserKey()
          + " · " + (layoutDoc.updatedAt || "");
      }
    } catch (e) {
      console.warn("terminal layout save", e);
    }
  }

  function setPressed(btn, on) {
    if (!btn) return;
    btn.classList.toggle("is-active", !!on);
    btn.setAttribute("aria-pressed", on ? "true" : "false");
  }

  function syncToolButtons() {
    const p = panes[activeId];
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
      width: el.clientWidth || 480,
      height: 320,
      layout: { backgroundColor: "#ffffff", textColor: "#1a2228" },
      grid: { vertLines: { color: "#eef1f3" }, horzLines: { color: "#eef1f3" } },
      rightPriceScale: { borderColor: "#d5dde2" },
      timeScale: {
        borderColor: "#d5dde2",
        timeVisible: true,
        secondsVisible: false,
        rightOffset: 8,
        lockVisibleTimeRangeOnResize: true
      },
      handleScroll: { mouseWheel: false, pressedMouseMove: true, horzTouchDrag: true, vertTouchDrag: false },
      handleScale: { axisPressedMouseMove: true, mouseWheel: false, pinch: true }
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
      pointSize: (window.TrinityChartKit && TrinityChartKit.pointSizeFor)
        ? TrinityChartKit.pointSizeFor(secid) : (secid.toUpperCase().indexOf("RI") === 0 ? 10 : 0.01),
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
      scaleLocked: false, barSpacing: null, logical: null, nav: null, flow: null };
    if (kit && typeof kit.attachFlowOverlays === "function") {
      panes[secid].flow = kit.attachFlowOverlays({
        chart: chart,
        series: series,
        hostEl: el,
        barSec: 300,
        pointSize: (kit.pointSizeFor) ? kit.pointSizeFor(secid) : 0.01,
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
        pointSize: (kit.pointSizeFor) ? kit.pointSizeFor(secid) : 0.01,
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
    return panes[secid];
  }

  async function paintPaneFromCache(secid) {
    const p = panes[secid];
    if (!p || !window.TrinityChartKit || typeof TrinityChartKit.barCacheGet !== "function") return;
    const row = await TrinityChartKit.barCacheGet(secid, "M5");
    if (!row || !row.bars || !row.bars.length) return;
    p.bars = row.raw || row.bars;
    try { p.series.setData(row.bars); } catch (_) {}
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
  function restorePaneScale(p) {
    if (!p || !p.chart || !(p.barSpacing > 0)) return false;
    try {
      p.chart.timeScale().applyOptions({ barSpacing: p.barSpacing });
      if (p.logical) p.chart.timeScale().setVisibleLogicalRange(p.logical);
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
        p.barSpacing = snap.barSpacing;
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
    scheduleSave();
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

  function enterFullscreen(secid) {
    const p = panes[secid];
    if (!p) return;
    const grid = $("charts-terminal-grid");
    const wrap = p.wrap;
    const first = wrap.getBoundingClientRect();
    const instant = prefersReducedMotion() || fsBusy;
    fsBusy = true;
    grid.classList.add("is-fullscreen");
    Object.keys(panes).forEach(function (id) {
      panes[id].wrap.classList.toggle("is-fs-target", id === secid);
      panes[id].wrap.hidden = id !== secid;
      if (id !== secid) clearPaneMotion(panes[id].wrap);
    });
    setActive(secid);
    syncFsChrome();
    resizeAll();
    if (instant) {
      fsBusy = false;
      return;
    }
    const last = wrap.getBoundingClientRect();
    const sx = last.width > 1 ? first.width / last.width : 1;
    const sy = last.height > 1 ? first.height / last.height : 1;
    const dx = first.left - last.left;
    const dy = first.top - last.top;
    wrap.classList.add("is-fs-motion");
    wrap.style.zIndex = "6";
    wrap.style.transformOrigin = "top left";
    wrap.style.transition = "none";
    wrap.style.transform = "translate(" + dx + "px," + dy + "px) scale(" + sx + "," + sy + ")";
    wrap.style.boxShadow = "0 8px 24px rgba(15, 23, 42, 0.08)";
    void wrap.offsetWidth;
    requestAnimationFrame(function () {
      wrap.style.transition = "transform 0.52s cubic-bezier(0.22, 1, 0.36, 1), box-shadow 0.52s ease";
      wrap.style.transform = "none";
      wrap.style.boxShadow = "0 28px 80px rgba(15, 23, 42, 0.18)";
    });
    window.setTimeout(function () {
      clearPaneMotion(wrap);
      wrap.classList.add("is-fs-target");
      fsBusy = false;
      resizeAll();
    }, 540);
  }

  function exitFullscreen() {
    const grid = $("charts-terminal-grid");
    let targetId = null;
    Object.keys(panes).forEach(function (id) {
      if (panes[id].wrap.classList.contains("is-fs-target")) targetId = id;
    });
    const wrap = targetId && panes[targetId] ? panes[targetId].wrap : null;
    const first = wrap ? wrap.getBoundingClientRect() : null;
    const instant = prefersReducedMotion() || !wrap;
    grid.classList.remove("is-fullscreen");
    Object.keys(panes).forEach(function (id) {
      panes[id].wrap.hidden = false;
      if (id !== targetId) panes[id].wrap.classList.remove("is-fs-target");
    });
    syncFsChrome();
    resizeAll();
    if (instant || !first) {
      if (wrap) {
        wrap.classList.remove("is-fs-target");
        clearPaneMotion(wrap);
      }
      fsBusy = false;
      return;
    }
    fsBusy = true;
    const last = wrap.getBoundingClientRect();
    const sx = last.width > 1 ? first.width / last.width : 1;
    const sy = last.height > 1 ? first.height / last.height : 1;
    const dx = first.left - last.left;
    const dy = first.top - last.top;
    wrap.style.zIndex = "8";
    wrap.style.transformOrigin = "top left";
    wrap.style.transition = "none";
    wrap.style.transform = "translate(" + dx + "px," + dy + "px) scale(" + sx + "," + sy + ")";
    wrap.style.boxShadow = "0 32px 90px rgba(15, 23, 42, 0.22)";
    Object.keys(panes).forEach(function (id) {
      if (id === targetId) return;
      const other = panes[id].wrap;
      other.classList.add("is-fs-ghost");
      other.style.opacity = "0";
      other.style.transform = "translateY(10px) scale(0.975)";
    });
    void wrap.offsetWidth;
    requestAnimationFrame(function () {
      wrap.style.transition = "transform 0.55s cubic-bezier(0.22, 1, 0.36, 1), box-shadow 0.55s ease";
      wrap.style.transform = "none";
      wrap.style.boxShadow = "0 0 0 1px rgba(37, 99, 235, 0.2)";
      Object.keys(panes).forEach(function (id) {
        if (id === targetId) return;
        const other = panes[id].wrap;
        other.style.transition = "opacity 0.42s ease 0.08s, transform 0.48s cubic-bezier(0.22, 1, 0.36, 1) 0.06s";
        other.style.opacity = "1";
        other.style.transform = "none";
      });
    });
    window.setTimeout(function () {
      wrap.classList.remove("is-fs-target");
      clearPaneMotion(wrap);
      Object.keys(panes).forEach(function (id) {
        const other = panes[id].wrap;
        other.classList.remove("is-fs-ghost");
        other.style.opacity = "";
        other.style.transform = "";
        other.style.transition = "";
      });
      fsBusy = false;
      resizeAll();
    }, 580);
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
  }

  function resizeAll() {
    Object.keys(panes).forEach(function (id) {
      const p = panes[id];
      if (!p || p.wrap.hidden) return;
      const w = p.el.clientWidth || 480;
      const h = gridFullscreen() ? Math.max(480, window.innerHeight - 180) : 320;
      try { p.chart.applyOptions({ width: w, height: h }); } catch (_) {}
      if (p.tools) p.tools.layoutStretchedVap();
      if (p.flow) p.flow.layout();
    });
  }

  function gridFullscreen() {
    return $("charts-terminal-grid").classList.contains("is-fullscreen");
  }

  async function loadDeskFor(secid) {
    const res = await authFetch("/api/trend/desk?instrument=" + encodeURIComponent(secid));
    if (!res.ok) throw new Error("desk HTTP " + res.status);
    return res.json();
  }

  async function refreshPane(secid) {
    const p = panes[secid];
    if (!p) return;
    try {
      const data = await loadDeskFor(secid);
      const raw = data.bars || [];
      const candles = [];
      const toolBars = [];
      raw.forEach(function (b) {
        const t = toChartTime(b.time);
        if (t == null) return;
        candles.push({ time: t, open: b.open, high: b.high, low: b.low, close: b.close });
        toolBars.push({
          time: t, open: b.open, high: b.high, low: b.low, close: b.close, volume: b.volume
        });
      });
      p.bars = toolBars;
      const kit = window.TrinityChartKit;
      if (kit && typeof kit.barCachePut === "function") {
        kit.barCachePut(secid, "M5", { instrument: secid, tf: "M5", bars: candles, raw: toolBars });
      }
      if (p.scaleLocked && p.barSpacing > 0) {
        if (kit && typeof kit.setSeriesDataKeepView === "function") {
          kit.setSeriesDataKeepView(p.chart, p.series, candles);
        } else {
          p.series.setData(candles);
        }
        restorePaneScale(p);
      } else {
        if (kit && typeof kit.setSeriesDataKeepView === "function") {
          kit.setSeriesDataKeepView(p.chart, p.series, candles);
        } else {
          p.series.setData(candles);
        }
        try { p.chart.timeScale().fitContent(); } catch (_) {}
      }
      if (p.flow) {
        p.flow.setProfile(data.profile || []);
        p.flow.setFootprints(data.footprint || []);
      }
      if (p.tools) p.tools.refreshOverlays();
      if (p.tools && typeof p.tools.paintOhlc === "function") p.tools.paintOhlc();
    } catch (e) {
      console.warn("refreshPane", secid, e);
    }
  }

  async function bootstrap() {
    const deskRes = await authFetch("/api/trend/desk");
    const desk = deskRes.ok ? await deskRes.json() : {};
    instruments = desk.instruments || [
      { secid: "BRU6", name: "Нефть (BR)", family: "BR" },
      { secid: "RiU6", name: "RTS (Ri)", family: "RI" },
      { secid: "NGU6", name: "Газ (NG)", family: "NG" },
      { secid: "SiU6", name: "Si (USD/RUB)", family: "SI" },
      { secid: "GDU6", name: "GOLD (GD)", family: "GD" },
      { secid: "MXU6", name: "MIX (IMOEX/MX)", family: "MX" }
    ];
    try {
      layoutDoc = await TrinityChartKit.loadLayouts();
    } catch (_) {
      layoutDoc = { terminal: {} };
    }
    const savedIds = (layoutDoc.terminal && layoutDoc.terminal.instruments) || null;
    const list = savedIds && savedIds.length
      ? savedIds.map(function (id) {
          return instruments.find(function (o) { return o.secid === id; }) || { secid: id, name: id };
        })
      : instruments;

    const sel = $("charts-instrument");
    sel.innerHTML = "";
    list.forEach(function (o) {
      const opt = document.createElement("option");
      opt.value = o.secid;
      opt.textContent = (o.name || o.family || o.secid) + " · " + o.secid;
      sel.appendChild(opt);
      createPane(o.secid, o.name || o.secid);
    });

    activeId = (layoutDoc.terminal && layoutDoc.terminal.active) || list[0].secid;
    setActive(activeId);

    for (let i = 0; i < list.length; i++) {
      await paintPaneFromCache(list[i].secid);
    }

    // Sequential load to avoid instrument thrash
    for (let i = 0; i < list.length; i++) {
      await refreshPane(list[i].secid);
      const by = (layoutDoc.terminal && layoutDoc.terminal.byInstrument) || {};
      if (by[list[i].secid] && panes[list[i].secid]) {
        panes[list[i].secid].tools.setState(by[list[i].secid]);
        if (by[list[i].secid].flow && panes[list[i].secid].flow) {
          panes[list[i].secid].flow.setState(by[list[i].secid].flow);
        }
      }
      const sc = ((layoutDoc.terminal && layoutDoc.terminal.scaleByInstrument) || {})[list[i].secid];
      const pane = panes[list[i].secid];
      if (sc && sc.barSpacing > 0 && pane) {
        pane.scaleLocked = true;
        pane.barSpacing = sc.barSpacing;
        pane.logical = sc.logical || null;
        restorePaneScale(pane);
      }
    }
    resizeAll();
    syncToolButtons();
    syncFsChrome();
    hydrating = false;
    if (window.TrinityPlaques && typeof window.TrinityPlaques.refresh === "function") {
      window.TrinityPlaques.refresh();
    }
  }

  $("charts-instrument").addEventListener("change", function () {
    showInstrument($("charts-instrument").value);
  });
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
    const p = panes[activeId];
    if (!p) return;
    const cfg = TrinityChartKit.promptMaConfig({ type: "SMA", period: 20 });
    if (!cfg) return;
    p.tools.upsertMa(cfg);
  });
  $("charts-tool-clusters").addEventListener("click", function () {
    const p = panes[activeId];
    if (!p || !p.flow) return;
    p.flow.setShowClusters(!p.flow.getShowClusters());
    syncToolButtons();
  });
  $("charts-tool-hvol").addEventListener("click", function () {
    const p = panes[activeId];
    if (!p || !p.flow) return;
    p.flow.setShowProfile(!p.flow.getShowProfile());
    syncToolButtons();
  });
  $("charts-tool-magnet").addEventListener("click", function () {
    const p = panes[activeId];
    if (!p || !p.tools || !p.tools.setMagnet) return;
    p.tools.setMagnet(!p.tools.getMagnet());
    syncToolButtons();
  });
  $("charts-tool-hide").addEventListener("click", function () {
    const p = panes[activeId];
    if (!p || !p.tools || !p.tools.setDrawingsHidden) return;
    p.tools.setDrawingsHidden(!p.tools.getDrawingsHidden());
    syncToolButtons();
  });
  $("charts-tool-lock").addEventListener("click", function () {
    const p = panes[activeId];
    if (!p || !p.tools || !p.tools.setDrawingsLocked) return;
    p.tools.setDrawingsLocked(!p.tools.getDrawingsLocked());
    syncToolButtons();
  });
  $("charts-tool-delete").addEventListener("click", function () {
    const p = panes[activeId];
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
    const p = panes[activeId];
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
      try { p.chart.timeScale().fitContent(); } catch (_) {}
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

  bootstrap().catch(function (e) {
    const meta = $("charts-terminal-meta");
    if (meta) meta.textContent = "Ошибка загрузки: " + (e && e.message ? e.message : e);
  });
})();
