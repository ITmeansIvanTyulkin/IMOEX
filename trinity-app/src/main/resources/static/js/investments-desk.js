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

  let candidatesBound = false;
  let candidatesRows = [];
  function ensureCandidatesDelegate() {
    const el = $("invest-candidates");
    if (!el || candidatesBound) return;
    candidatesBound = true;
    el.addEventListener("pointerup", function (ev) {
      if (ev.button != null && ev.button !== 0) return;
      const row = ev.target && ev.target.closest ? ev.target.closest(".invest-cand-row") : null;
      if (!row || !el.contains(row) || !row.getAttribute("data-ticker")) return;
      const t = String(row.getAttribute("data-ticker") || "").trim().toUpperCase();
      if (!t) return;
      el.querySelectorAll(".invest-cand-row").forEach(function (x) { x.classList.remove("is-on"); });
      row.classList.add("is-on");
      focusTicker(t, candidatesRows);
    });
  }

  function renderCandidates(rows) {
    const el = $("invest-candidates");
    if (!el) return;
    ensureCandidatesDelegate();
    candidatesRows = rows || [];
    if (!rows || !rows.length) {
      el.innerHTML = "<div class=\"charts-dock-empty\">нет данных</div>";
      return;
    }
    el.innerHTML = rows.slice(0, 40).map(function (r) {
      const t = String(r.ticker || "?").trim().toUpperCase();
      const cat = r.category != null ? "C" + r.category : "—";
      const v = r.verdict || "—";
      return "<div class=\"invest-cand-row\" data-ticker=\"" + t + "\">"
        + "<span>" + t + "</span><span class=\"cat\">" + cat + "</span><span>" + v + "</span></div>";
    }).join("");
  }

  function focusTicker(ticker, rows) {
    ticker = String(ticker || "").trim().toUpperCase();
    if (!ticker) return;
    if (window.TrinityInvestCharts && typeof window.TrinityInvestCharts.openInstrument === "function") {
      window.TrinityInvestCharts.openInstrument(ticker).catch(function (e) { console.warn(e); });
    } else {
      const sel = $("charts-instrument");
      if (sel) {
        const opt = Array.prototype.find.call(sel.options, function (o) {
          return String(o.value || "").toUpperCase() === ticker;
        });
        if (opt) {
          sel.value = opt.value;
          sel.dispatchEvent(new Event("change"));
        }
      }
    }
    const row = (rows || []).find(function (r) {
      return String(r.ticker || "").toUpperCase() === ticker;
    });
    if (row && row.stages) renderPipeline(row.stages);
    refreshDesk({ ticker: ticker, refresh: false }).catch(console.warn);
  }

  function renderPositions(fair) {
    const el = $("invest-positions");
    if (!el) return;
    const positions = (fair && fair.openPositions) || [];
    const ladders = (fair && fair.openLadders) || [];
    const bits = [];
    positions.forEach(function (o) {
      bits.push("<div class=\"invest-cand-row is-pos\"><span>" + o.ticker + "</span>"
        + "<span class=\"cat\">" + (o.side || "LONG") + "</span>"
        + "<span>@ " + (o.entryAvg != null ? Number(o.entryAvg).toFixed(2) : "—") + "</span></div>");
    });
    ladders.forEach(function (o) {
      bits.push("<div class=\"invest-cand-row\"><span>" + o.ticker + "</span>"
        + "<span class=\"cat\">лестница</span>"
        + "<span>" + (o.sleevePct != null ? (o.sleevePct * 100).toFixed(1) + "%" : "—") + "</span></div>");
    });
    if (!bits.length) {
      el.innerHTML = "<div class=\"charts-dock-empty\">нет открытых лестниц / позиций</div>";
      return;
    }
    el.innerHTML = bits.join("");
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

  function escHtml(t) {
    return String(t == null ? "" : t)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function paintBriefHero(desk) {
    const hero = $("invest-desk-brief-hero");
    if (!hero) return;
    const frame = desk && desk.decisionFrame;
    if (!frame || !frame.summary) {
      hero.hidden = true;
      hero.innerHTML = "";
      return;
    }
    const status = (window.TrinityDecisionFrame && TrinityDecisionFrame.effectiveStatus)
      ? TrinityDecisionFrame.effectiveStatus(frame)
      : String((frame.robot && frame.robot.status) || "");
    const label = (window.TrinityDecisionFrame && TrinityDecisionFrame.statusRu)
      ? TrinityDecisionFrame.statusRu(status, frame)
      : (status || "—");
    const heroText = (window.TrinityDecisionFrame && TrinityDecisionFrame.robotDetailText)
      ? TrinityDecisionFrame.robotDetailText(frame)
      : (frame.summary || "—");
    const chips = (window.TrinityDecisionFrame && TrinityDecisionFrame.whyChips)
      ? TrinityDecisionFrame.whyChips(frame, heroText)
      : [];
    const why = chips.map(function (c) { return c.text; }).filter(Boolean).slice(0, 2).join(" · ");
    hero.innerHTML = "<span class=\"signal-brief-hero-status\" data-status=\""
      + escHtml(status) + "\">" + escHtml(label) + "</span>"
      + escHtml(heroText || "—")
      + (why ? (" <span class=\"signal-brief-note\">· " + escHtml(why) + "</span>") : "");
    hero.hidden = false;
  }

  function paintBrief(desk) {
    paintBriefHero(desk);
    const body = $("invest-desk-brief-body");
    const action = $("invest-desk-brief-action");
    const brief = desk && desk.operatorBrief;
    const frame = desk && desk.decisionFrame;
    const counts = (desk && desk.counts) || {};
    const live = desk && desk.liveBridge;
    const regime = brief && brief.regime
      ? brief.regime
      : (desk && desk.regime && desk.regime.regime) || "—";
    if (body) {
      const ticker = (brief && brief.ticker) || (desk && desk.focusTicker) || "—";
      const verdict = (brief && brief.verdict) || "—";
      const uni = desk && desk.universeSize != null
        ? desk.universeSize
        : ((desk && desk.watchlist && desk.watchlist.length) || "—");
      const delivery = (desk && desk.delivery)
        || (live && live.channel)
        || "";
      body.innerHTML = "Режим: <strong>" + escHtml(regime) + "</strong>"
        + " · вселенная <strong>" + escHtml(uni) + "</strong>"
        + " · фокус кадра <strong>" + escHtml(ticker) + "</strong> (" + escHtml(verdict) + ")"
        + " · invest " + (counts.invest || 0)
        + " / watch " + (counts.watch || 0)
        + " / skip " + (counts.skip || 0)
        + (delivery ? " · " + escHtml(delivery) : "")
        + (live && live.brokerStatus
          ? " · брокер: " + escHtml(live.brokerStatus)
          : "");
    }
    if (action) {
      const what = (brief && brief.whatToDo)
        || (frame && frame.summary)
        || "Ждите скан или выберите тикер.";
      action.innerHTML = "<strong>Что делать:</strong> " + escHtml(what);
    }
  }

  let modeState = { auto: false, live: false };

  function paintMode(auto, live) {
    modeState.auto = !!auto;
    modeState.live = !!live;
    const tog = $("invest-auto-execution");
    if (tog) {
      tog.checked = !!auto;
      tog.setAttribute("aria-checked", auto ? "true" : "false");
      tog.disabled = false;
      tog.dataset.hydrated = "1";
      const sw = tog.closest(".mode-switch");
      if (sw) {
        sw.classList.toggle("is-auto", !!auto);
        sw.classList.toggle("is-signal", !auto);
      }
    }
    const liveTog = $("invest-live-execution");
    if (liveTog) {
      liveTog.checked = !!live;
      liveTog.setAttribute("aria-checked", live ? "true" : "false");
      liveTog.disabled = false;
      liveTog.dataset.hydrated = "1";
      const sw = liveTog.closest(".mode-switch");
      if (sw) {
        sw.classList.toggle("is-auto", !!live);
        sw.classList.toggle("is-signal", !live);
      }
    }
    const hint = $("invest-mode-hint");
    if (hint) {
      if (!auto) {
        hint.textContent = "Наблюдение: скан без заявок.";
      } else if (live) {
        hint.textContent = "Авто · Live: мост → T-Invest (или LIVE_GATED в журнал). Paper новых лестниц нет.";
      } else {
        hint.textContent = "Авто · Paper: лестницы, fill по D1, выход по цели/стопу.";
      }
    }
  }

  function paintAuto(on) {
    paintMode(on, modeState.live);
  }

  function autoFrom(obj) {
    if (!obj) return null;
    if (obj.autoExecution === true || obj.autoExecution === false) return !!obj.autoExecution;
    const set = obj.settings;
    if (set && (set.autoExecution === true || set.autoExecution === false)) return !!set.autoExecution;
    return null;
  }

  function liveFrom(obj) {
    if (!obj) return null;
    if (obj.liveExecution === true || obj.liveExecution === false) return !!obj.liveExecution;
    const set = obj.settings;
    if (set && (set.liveExecution === true || set.liveExecution === false)) return !!set.liveExecution;
    return null;
  }

  async function hydrateAuto() {
    try {
      const view = await api("/api/investments/settings");
      const on = autoFrom(view);
      const live = liveFrom(view);
      paintMode(on != null ? on : false, live != null ? live : false);
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

  let decisionFrameUi = null;
  let dfJournalUi = null;
  let investFactTimer = null;
  let focusTickerName = null;

  function focusInvestDecisionFact(detail) {
    if (investFactTimer) {
      clearTimeout(investFactTimer);
      investFactTimer = null;
    }
    document.querySelectorAll("#investments-desk .is-df-focus, #decision-frame.is-df-focus, #invest-desk-brief.is-df-focus")
      .forEach(function (el) { el.classList.remove("is-df-focus"); });
    if (!detail) return;
    const df = document.getElementById("decision-frame");
    if (df) df.classList.add("is-df-focus");
    const brief = document.getElementById("invest-desk-brief");
    if (brief) brief.classList.add("is-df-focus");
    const list = document.getElementById("invest-ops-strip")
      || document.getElementById("invest-candidates");
    if (list) list.classList.add("is-df-focus");
    investFactTimer = setTimeout(function () {
      document.querySelectorAll(".is-df-focus").forEach(function (el) {
        el.classList.remove("is-df-focus");
      });
    }, 12000);
  }

  function paintDecisionFrame(data) {
    if (!window.TrinityDecisionFrame || typeof TrinityDecisionFrame.create !== "function") return;
    if (!decisionFrameUi) {
      decisionFrameUi = TrinityDecisionFrame.create({
        root: "decision-frame",
        onFactFocus: focusInvestDecisionFact
      });
    }
    if (!decisionFrameUi) return;
    decisionFrameUi.render(data && data.decisionFrame);
  }
  function paintDecisionFrameJournal(payload) {
    if (!window.TrinityDecisionFrame || typeof TrinityDecisionFrame.mountJournal !== "function") return;
    if (!dfJournalUi) {
      dfJournalUi = TrinityDecisionFrame.mountJournal({
        panel: "signal-df-journal",
        list: "signal-df-journal-list",
        meta: "signal-df-journal-meta",
        title: "signal-df-journal-title",
        filters: "signal-df-journal-filters",
        onFactFocus: focusInvestDecisionFact
      });
    }
    if (dfJournalUi) dfJournalUi.paint(payload || null);
  }

  async function refreshDesk(opts) {
    opts = opts || {};
    const statusEl = $("invest-counts");
    if (statusEl) statusEl.textContent = opts.refresh ? "скан чек-листа…" : "обновление кадра…";
    const q = [];
    const ticker = opts.ticker != null ? opts.ticker : focusTickerName;
    if (ticker) {
      focusTickerName = ticker;
      q.push("ticker=" + encodeURIComponent(ticker));
    }
    if (opts.refresh) q.push("refresh=true");
    const url = "/api/investments/desk" + (q.length ? "?" + q.join("&") : "");
    const desk = await api(url);
    window.__investDeskCache = desk;
    paintBrief(desk);
    paintDecisionFrame(desk);
    paintDecisionFrameJournal(desk.decisionFrames);
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
    paintMode(next, modeState.live);
    try {
      const view = await api("/api/investments/settings/auto-execution", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ enabled: !!next })
      });
      const on = autoFrom(view);
      const live = liveFrom(view);
      paintMode(on != null ? on : next, live != null ? live : modeState.live);
      if (on) {
        try { await api("/api/investments/fair-paper/tick", { method: "POST" }); } catch (_) {}
      }
      refreshDesk({ refresh: false }).catch(console.warn);
    } catch (e) {
      console.warn(e);
      hydrateAuto().catch(console.warn);
    }
  }

  async function commitLive(next) {
    paintMode(modeState.auto, next);
    try {
      const view = await api("/api/investments/settings/live-execution", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ enabled: !!next })
      });
      const on = autoFrom(view);
      const live = liveFrom(view);
      paintMode(on != null ? on : modeState.auto, live != null ? live : next);
      if (on) {
        try { await api("/api/investments/fair-paper/tick", { method: "POST" }); } catch (_) {}
      }
      refreshDesk({ refresh: false }).catch(console.warn);
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
    const live = $("invest-live-execution");
    if (live) {
      live.disabled = false;
      live.dataset.hydrated = "1";
      const wrap = live.closest(".mode-switch");
      if (wrap) {
        wrap.addEventListener("click", function (ev) {
          ev.preventDefault();
          commitLive(!live.checked);
        });
      } else {
        live.addEventListener("change", function () {
          commitLive(!!live.checked);
        });
      }
    }
    const btn = $("invest-desk-refresh");
    if (btn) {
      btn.addEventListener("click", function () {
        refreshDesk({ refresh: true }).catch(console.warn);
      });
    }
    if (window.__investDeskCache) {
      const desk = window.__investDeskCache;
      paintBrief(desk);
      paintDecisionFrame(desk);
      paintDecisionFrameJournal(desk.decisionFrames);
      renderCandidates(desk.candidates || []);
      if (desk.pipelineSample) renderPipeline(desk.pipelineSample);
      renderMetrics(desk.journal);
    }
    hydrateAuto().catch(console.warn);
    refreshDesk({ refresh: false }).catch(console.warn);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", bind);
  } else {
    bind();
  }
})();
