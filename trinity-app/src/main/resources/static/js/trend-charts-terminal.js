(function () {
  "use strict";
  const $ = function (id) { return document.getElementById(id); };
  const panes = Object.create(null); // secid -> {el, chart, series, tools, bars}
  let layoutDoc = null;
  let instruments = [];
  let activeId = null;
  let saveTimer = null;

  function authFetch(url, opts) {
    opts = opts || {};
    opts.headers = (window.TrinityChartKit
      ? TrinityChartKit.authHeaders(opts.headers || {})
      : Object.assign({ Accept: "application/json" }, opts.headers || {}));
    return fetch(url, opts);
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

  async function persist() {
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
          cur.terminal.byInstrument[id] = p.tools.getState();
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
    const mode = p && p.tools ? p.tools.getMode() : null;
    setPressed($("charts-tool-vap"), mode === "vap");
    setPressed($("charts-tool-trend"), mode === "trend");
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
    wrap.querySelector(".charts-pane-fs").addEventListener("click", function () {
      enterFullscreen(secid);
    });
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
      }
    });
    const series = chart.addCandlestickSeries({
      upColor: "#16a34a", downColor: "#dc2626",
      borderUpColor: "#16a34a", borderDownColor: "#dc2626",
      wickUpColor: "#16a34a", wickDownColor: "#dc2626"
    });
    const tools = TrinityChartKit.attachTools({
      chart: chart,
      candleSeries: series,
      hostEl: el,
      pointSize: (window.TrinityChartKit && TrinityChartKit.pointSizeFor)
        ? TrinityChartKit.pointSizeFor(secid) : (secid.toUpperCase().indexOf("RI") === 0 ? 10 : 0.01),
      overlayId: "vap-" + secid,
      getBars: function () { return (panes[secid] && panes[secid].bars) || []; },
      onChange: function () {
        scheduleSave();
        syncToolButtons();
      }
    });
    panes[secid] = { wrap: wrap, el: el, chart: chart, series: series, tools: tools, bars: [],
      scaleLocked: false, barSpacing: null, logical: null };
    bindPaneScale(panes[secid]);
    return panes[secid];
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
    syncToolButtons();
    scheduleSave();
  }

  function enterFullscreen(secid) {
    const grid = $("charts-terminal-grid");
    grid.classList.add("is-fullscreen");
    Object.keys(panes).forEach(function (id) {
      panes[id].wrap.classList.toggle("is-fs-target", id === secid);
      panes[id].wrap.hidden = id !== secid;
    });
    $("charts-exit-fs").hidden = false;
    setActive(secid);
    resizeAll();
  }

  function exitFullscreen() {
    const grid = $("charts-terminal-grid");
    grid.classList.remove("is-fullscreen");
    Object.keys(panes).forEach(function (id) {
      panes[id].wrap.hidden = false;
      panes[id].wrap.classList.remove("is-fs-target");
    });
    $("charts-exit-fs").hidden = true;
    resizeAll();
  }

  function resizeAll() {
    Object.keys(panes).forEach(function (id) {
      const p = panes[id];
      if (!p || p.wrap.hidden) return;
      const w = p.el.clientWidth || 480;
      const h = gridFullscreen() ? Math.max(480, window.innerHeight - 180) : 320;
      try { p.chart.applyOptions({ width: w, height: h }); } catch (_) {}
      if (p.tools) p.tools.layoutStretchedVap();
    });
  }

  function gridFullscreen() {
    return $("charts-terminal-grid").classList.contains("is-fullscreen");
  }

  async function loadBarsFor(secid) {
    const res = await authFetch("/api/trend/desk?instrument=" + encodeURIComponent(secid));
    if (!res.ok) throw new Error("desk HTTP " + res.status);
    const data = await res.json();
    return data.bars || [];
  }

  async function refreshPane(secid) {
    const p = panes[secid];
    if (!p) return;
    try {
      const raw = await loadBarsFor(secid);
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
      p.series.setData(candles);
      if (p.tools) p.tools.refreshOverlays();
      if (p.scaleLocked && p.barSpacing > 0) {
        restorePaneScale(p);
      } else {
        try { p.chart.timeScale().fitContent(); } catch (_) {}
      }
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

    // Sequential load to avoid instrument thrash
    for (let i = 0; i < list.length; i++) {
      await refreshPane(list[i].secid);
      const by = (layoutDoc.terminal && layoutDoc.terminal.byInstrument) || {};
      if (by[list[i].secid] && panes[list[i].secid]) {
        panes[list[i].secid].tools.setState(by[list[i].secid]);
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
    if (window.TrinityPlaques && typeof window.TrinityPlaques.refresh === "function") {
      window.TrinityPlaques.refresh();
    }
  }

  $("charts-instrument").addEventListener("change", function () {
    setActive($("charts-instrument").value);
  });
  $("charts-tool-vap").addEventListener("click", function () {
    const p = panes[activeId];
    if (!p) return;
    const on = p.tools.getMode() !== "vap";
    p.tools.setMode(on ? "vap" : null);
    syncToolButtons();
  });
  $("charts-tool-trend").addEventListener("click", function () {
    const p = panes[activeId];
    if (!p) return;
    const on = p.tools.getMode() !== "trend";
    p.tools.setMode(on ? "trend" : null);
    syncToolButtons();
  });
  $("charts-tool-ma").addEventListener("click", function () {
    const p = panes[activeId];
    if (!p) return;
    const cfg = TrinityChartKit.promptMaConfig({ type: "SMA", period: 20 });
    if (!cfg) return;
    p.tools.upsertMa(cfg);
  });
  $("charts-tool-clear").addEventListener("click", function () {
    const p = panes[activeId];
    if (!p) return;
    p.tools.clearVap();
    p.tools.clearTrendLines();
    p.tools.clearMas();
    scheduleSave();
  });
  $("charts-fullscreen").addEventListener("click", function () {
    enterFullscreen(activeId);
  });
  $("charts-exit-fs").addEventListener("click", exitFullscreen);
  $("charts-fit").addEventListener("click", function () {
    Object.keys(panes).forEach(function (id) {
      const p = panes[id];
      if (!p) return;
      p.scaleLocked = false;
      p.barSpacing = null;
      p.logical = null;
      try { p.chart.timeScale().fitContent(); } catch (_) {}
    });
    scheduleSave();
  });
  $("charts-refresh").addEventListener("click", async function () {
    for (const id of Object.keys(panes)) await refreshPane(id);
  });
  window.addEventListener("resize", resizeAll);
  document.addEventListener("keydown", function (ev) {
    if (ev.key === "Escape") {
      if (gridFullscreen()) exitFullscreen();
      else {
        const p = panes[activeId];
        if (p && p.tools.getMode()) {
          p.tools.setMode(null);
          syncToolButtons();
        }
      }
    }
  });

  bootstrap().catch(function (e) {
    const meta = $("charts-terminal-meta");
    if (meta) meta.textContent = "Ошибка загрузки: " + (e && e.message ? e.message : e);
  });
})();
