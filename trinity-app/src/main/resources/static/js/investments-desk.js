(function () {
  "use strict";

  function $(id) { return document.getElementById(id); }

  function withAuthHeaders(h) {
    h = h || {};
    if (window.TrinityChartKit && typeof TrinityChartKit.authHeaders === "function") {
      return TrinityChartKit.authHeaders(h);
    }
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
    const sel = $("charts-instrument");
    if (sel) {
      const opt = Array.prototype.find.call(sel.options, function (o) { return o.value === ticker; });
      if (opt) {
        sel.value = ticker;
        sel.dispatchEvent(new Event("change"));
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
    renderCandidates(desk.candidates || []);
    if (desk.pipelineSample) renderPipeline(desk.pipelineSample);
    renderMetrics(desk.journal);
    try {
      const st = await api("/api/investments/status");
      renderPositions(st.fairPaper);
      if (st.journal) renderMetrics(st.journal);
      const auto = $("invest-auto-execution");
      if (auto && st.settings) auto.checked = !!st.settings.autoExecution;
    } catch (_) {}
  }

  async function bind() {
    const auto = $("invest-auto-execution");
    if (auto) {
      auto.addEventListener("change", async function () {
        try {
          await api("/api/investments/settings/auto-execution", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ enabled: !!auto.checked })
          });
          await refreshDesk();
        } catch (e) {
          console.warn(e);
        }
      });
    }
    const btn = $("invest-desk-refresh");
    if (btn) btn.addEventListener("click", function () { refreshDesk().catch(console.warn); });
    if (window.__investDeskCache) {
      const desk = window.__investDeskCache;
      renderCandidates(desk.candidates || []);
      if (desk.pipelineSample) renderPipeline(desk.pipelineSample);
      renderMetrics(desk.journal);
    }
    refreshDesk().catch(console.warn);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", bind);
  } else {
    bind();
  }
})();
