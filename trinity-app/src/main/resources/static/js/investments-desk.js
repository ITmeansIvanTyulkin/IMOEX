(function () {
  "use strict";

  function $(id) { return document.getElementById(id); }

  function withAuthHeaders(h) {
    /* Cookie + fast-boot JWT. ChartKit would send a stale Bearer and 401 the toggle. */
    h = h || {};
    return Object.assign({ Accept: "application/json" }, h);
  }

  async function api(url, opts) {
    opts = opts || {};
    const res = await fetch(url, Object.assign({ credentials: "include" }, opts, {
      headers: withAuthHeaders(opts.headers || {})
    }));
    if (!res.ok) throw new Error(url + " " + res.status);
    return res.json();
  }

  function stageClass(st) {
    const s = String(st || "").toUpperCase();
    if (s === "PASS") return "is-pass";
    if (s === "WEAK" || s === "NO_DATA") return "is-weak";
    if (s === "FAIL" || s === "SKIP") return "is-fail";
    return "";
  }

  function renderPipeline(stages) {
    const strip = $("invest-ops-strip");
    if (!strip) return;
    if (!stages || !stages.length) {
      strip.innerHTML = "<span class=\"meta\">Pipeline — выберите кандидата или нажмите Скан</span>";
      return;
    }
    strip.innerHTML = stages.map(function (s) {
      return "<span class=\"invest-stage " + stageClass(s.status) + "\" title=\""
        + String(s.detail || "").replace(/"/g, "&quot;") + "\">"
        + (s.title || s.id) + "</span>";
    }).join("");
  }

  function renderCandidates(rows) {
    const el = $("invest-candidates");
    if (!el) return;
    if (!rows || !rows.length) {
      el.innerHTML = "<div class=\"charts-dock-empty\">нет данных</div>";
      return;
    }
    el.innerHTML = rows.slice(0, 40).map(function (r) {
      const t = r.ticker || "?";
      const cat = r.category != null ? "C" + r.category : "—";
      const v = r.verdict || "—";
      return "<div class=\"invest-cand-row\" data-ticker=\"" + t + "\">"
        + "<span>" + t + "</span><span class=\"cat\">" + cat + "</span><span>" + v + "</span></div>";
    }).join("");
    el.querySelectorAll(".invest-cand-row").forEach(function (row) {
      row.addEventListener("click", function () {
        const t = row.getAttribute("data-ticker");
        el.querySelectorAll(".invest-cand-row").forEach(function (x) { x.classList.remove("is-on"); });
        row.classList.add("is-on");
        focusTicker(t, rows);
      });
    });
  }

  function focusTicker(ticker, rows) {
    if (window.TrinityInvestCharts && typeof window.TrinityInvestCharts.openInstrument === "function") {
      window.TrinityInvestCharts.openInstrument(ticker);
    } else {
      const sel = $("charts-instrument");
      if (sel) {
        const opt = Array.prototype.find.call(sel.options, function (o) { return o.value === ticker; });
        if (opt) {
          sel.value = ticker;
          sel.dispatchEvent(new Event("change"));
        }
      }
    }
    const row = (rows || []).find(function (r) { return r.ticker === ticker; });
    if (row && row.stages) renderPipeline(row.stages);
  }

  function renderPositions(fair) {
    const el = $("invest-positions");
    if (!el) return;
    const ladders = (fair && fair.openLadders) || [];
    if (!ladders.length) {
      el.innerHTML = "<div class=\"charts-dock-empty\">нет открытых лестниц</div>";
      return;
    }
    el.innerHTML = ladders.map(function (o) {
      return "<div class=\"invest-cand-row\"><span>" + o.ticker + "</span>"
        + "<span class=\"cat\">C" + o.category + "</span>"
        + "<span>" + (o.sleevePct != null ? (o.sleevePct * 100).toFixed(1) + "%" : "—") + "</span></div>";
    }).join("");
  }

  function renderMetrics(journal) {
    const el = $("invest-metrics");
    if (!el) return;
    const m = (journal && journal.metrics) || {};
    el.textContent = "n=" + (m.count || 0)
      + " · win=" + ((m.winRate || 0) * 100).toFixed(0) + "%"
      + " · E=" + (m.expectancy != null ? Number(m.expectancy).toFixed(2) : "—")
      + " · RR=" + (m.avgRewardRisk != null ? Number(m.avgRewardRisk).toFixed(2) : "—")
      + " · DD=" + (m.maxDrawdownPct != null ? Number(m.maxDrawdownPct).toFixed(1) : "—") + "%";
  }

  function paintAuto(on) {
    const tog = $("invest-auto-execution");
    if (!tog) return;
    tog.checked = !!on;
    tog.setAttribute("aria-checked", on ? "true" : "false");
    tog.disabled = false;
    tog.dataset.hydrated = "1";
    const sw = tog.closest(".mode-switch");
    if (sw) {
      sw.classList.toggle("is-auto", !!on);
      sw.classList.toggle("is-signal", !on);
    }
    const hint = $("invest-mode-hint");
    if (hint) {
      hint.textContent = on
        ? "Авто: paper-лестницы по чек-листу."
        : "Наблюдение: скан без заявок.";
    }
  }

  function autoFrom(obj) {
    if (!obj) return null;
    if (obj.autoExecution === true || obj.autoExecution === false) return !!obj.autoExecution;
    const set = obj.settings;
    if (set && (set.autoExecution === true || set.autoExecution === false)) return !!set.autoExecution;
    return null;
  }

  async function hydrateAuto() {
    try {
      const view = await api("/api/investments/settings");
      const on = autoFrom(view);
      if (on != null) paintAuto(on);
    } catch (e) {
      console.warn(e);
      const tog = $("invest-auto-execution");
      if (tog && tog.dataset.hydrated !== "1") {
        tog.disabled = false;
        tog.dataset.hydrated = "1";
        const hint = $("invest-mode-hint");
        if (hint) hint.textContent = "Режим не загрузился — можно переключить вручную.";
      }
    }
  }

  async function refreshDesk() {
    const statusEl = $("invest-counts");
    if (statusEl) statusEl.textContent = "скан чек-листа…";
    const desk = await api("/api/investments/desk");
    window.__investDeskCache = desk;
    const counts = desk.counts || {};
    const cEl = $("invest-counts");
    if (cEl) {
      cEl.textContent = "invest " + (counts.invest || 0)
        + " · watch " + (counts.watch || 0)
        + " · skip " + (counts.skip || 0)
        + " · " + (desk.delivery || "");
    }
    const uniN = desk.universeSize || ((desk.watchlist && desk.watchlist.length) ? desk.watchlist.length : 0);
    const nEl = $("invest-guide-universe-n");
    if (nEl && uniN) nEl.textContent = String(uniN);
    renderCandidates(desk.candidates || []);
    if (window.TrinityInvestCharts && typeof window.TrinityInvestCharts.refreshWatchlist === "function") {
      window.TrinityInvestCharts.refreshWatchlist();
    }
    if (desk.pipelineSample) renderPipeline(desk.pipelineSample);
    renderMetrics(desk.journal);
    try {
      const st = await api("/api/investments/status");
      renderPositions(st.fairPaper);
      if (st.journal) renderMetrics(st.journal);
    } catch (_) {}
  }

  async function commitAuto(next) {
    paintAuto(next);
    try {
      const view = await api("/api/investments/settings/auto-execution", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ enabled: !!next })
      });
      const on = autoFrom(view);
      paintAuto(on != null ? on : next);
    } catch (e) {
      console.warn(e);
      hydrateAuto().catch(console.warn);
    }
  }

  function bindGuide() {
    let lastFocus = null;
    function openGuide() {
      const gate = $("invest-guide-modal");
      const dialog = gate && gate.querySelector(".signal-guide-modal");
      if (!gate || !dialog) return;
      lastFocus = document.activeElement;
      gate.hidden = false;
      gate.setAttribute("aria-hidden", "false");
      requestAnimationFrame(function () {
        gate.classList.add("is-open");
        dialog.focus();
      });
    }
    function closeGuide() {
      const gate = $("invest-guide-modal");
      if (!gate || gate.hidden) return;
      gate.classList.remove("is-open");
      gate.setAttribute("aria-hidden", "true");
      window.setTimeout(function () {
        gate.hidden = true;
        if (lastFocus && typeof lastFocus.focus === "function") lastFocus.focus();
        lastFocus = null;
      }, 220);
    }
    const openBtn = $("invest-guide-open");
    if (openBtn) openBtn.addEventListener("click", openGuide);
    const gate = $("invest-guide-modal");
    if (gate) {
      gate.querySelectorAll("[data-invest-guide-close]").forEach(function (el) {
        el.addEventListener("click", closeGuide);
      });
      gate.querySelectorAll(".signal-guide-toc a").forEach(function (a) {
        a.addEventListener("click", function (ev) {
          const id = (a.getAttribute("href") || "").replace(/^#/, "");
          const target = id && document.getElementById(id);
          const body = gate.querySelector(".signal-guide-body");
          if (!target || !body) return;
          ev.preventDefault();
          body.scrollTo({ top: Math.max(0, target.offsetTop - 8), behavior: "smooth" });
        });
      });
    }
    document.addEventListener("keydown", function (ev) {
      if (ev.key !== "Escape") return;
      const g = $("invest-guide-modal");
      if (g && !g.hidden) {
        closeGuide();
        ev.preventDefault();
      }
    });
  }

  async function bind() {
    bindGuide();
    const auto = $("invest-auto-execution");
    if (auto) {
      auto.disabled = false;
      auto.dataset.hydrated = "1";
      const wrap = auto.closest(".mode-switch");
      if (wrap) {
        wrap.addEventListener("click", function (ev) {
          ev.preventDefault();
          commitAuto(!auto.checked);
        });
      } else {
        auto.addEventListener("change", function () {
          commitAuto(!!auto.checked);
        });
      }
    }
    const btn = $("invest-desk-refresh");
    if (btn) btn.addEventListener("click", function () { refreshDesk().catch(console.warn); });
    if (window.__investDeskCache) {
      const desk = window.__investDeskCache;
      renderCandidates(desk.candidates || []);
      if (desk.pipelineSample) renderPipeline(desk.pipelineSample);
      renderMetrics(desk.journal);
    }
    hydrateAuto().catch(console.warn);
    refreshDesk().catch(console.warn);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", bind);
  } else {
    bind();
  }
})();
