/**
 * Global floating plaques: 3 robots on every /view/* page;
 * wind (trend) only on Сигнал + Терминал графиков.
 * Updates text in-place to avoid twitch on poll.
 */
(function () {
  "use strict";

  const POLL_MS = 20000;
  const $ = function (id) { return document.getElementById(id); };

  let lastLayoutKey = "";
  let lastPayloadKey = "";
  let layoutTimer = 0;
  let refreshInFlight = false;

  function pathIsDashboard() {
    const p = location.pathname;
    return p === "/view" || p === "/view/";
  }

  function pathIsSignal() {
    return location.pathname.indexOf("/view/trend-signal") >= 0
      || location.pathname.indexOf("/view/trend-positional") >= 0;
  }
  function pathIsCharts() {
    return location.pathname.indexOf("/view/trend-charts") >= 0;
  }
  function wantsWind() {
    return pathIsSignal() || pathIsCharts();
  }

  function chartInstruments() {
    const sel = $("charts-instrument");
    if (sel && sel.options && sel.options.length) {
      const out = [];
      for (let i = 0; i < sel.options.length; i++) {
        const v = sel.options[i].value;
        if (v) out.push(v);
      }
      if (out.length) return out;
    }
    const panes = document.querySelectorAll("#charts-terminal-grid [data-secid]");
    if (panes.length) {
      return Array.prototype.map.call(panes, function (el) {
        return el.getAttribute("data-secid");
      }).filter(Boolean);
    }
    return null;
  }

  function windsQuery() {
    if (!wantsWind()) return "";
    if (pathIsCharts()) {
      const list = chartInstruments();
      if (list && list.length) return list.join(",");
      return "all";
    }
    const sel = $("sig-instrument");
    if (sel && sel.value) return sel.value;
    const q = new URLSearchParams(location.search).get("instrument");
    if (q) return q;
    return "active";
  }

  function ensureHost() {
    let host = $("trinity-plaque-host");
    if (host) return host;
    host = document.createElement("div");
    host.id = "trinity-plaque-host";
    host.className = "trinity-plaque-host";
    host.setAttribute("aria-live", "off");
    host.innerHTML =
      '<div class="trinity-plaque-rail" id="trinity-wind-rail" hidden></div>'
      + '<div class="trinity-plaque-stack" id="trinity-robot-stack"></div>';
    document.body.appendChild(host);
    return host;
  }

  function toneClass(tone) {
    const t = String(tone || "scan");
    if (t === "trade") return "is-trade";
    if (t === "armed") return "is-armed";
    if (t === "watch") return "is-watch";
    if (t === "flat") return "is-flat";
    return "is-scan";
  }

  function setTone(el, tone) {
    el.classList.remove("is-trade", "is-armed", "is-watch", "is-flat", "is-scan", "is-bid", "is-ask");
    el.classList.add(toneClass(tone));
  }

  function setText(el, text) {
    if (!el) return false;
    const next = text == null ? "" : String(text);
    if (el.textContent === next) return false;
    el.textContent = next;
    return true;
  }

  function paintWindLegs(root, wind) {
    if (!root) return false;
    const legs = [
      wind && wind.h1,
      wind && wind.h4,
      wind && wind.day
    ];
    let html = "";
    legs.forEach(function (leg) {
      leg = leg || {};
      const tone = leg.tone || "flat";
      const cls = tone === "gold" ? "is-gold" : (tone === "black" ? "is-black" : "is-flat");
      const tf = leg.tf || "—";
      const arrow = leg.arrow || "↔";
      const label = leg.label || "боковик";
      html += '<span class="signal-wind-row ' + cls + '"><span class="k">'
        + tf + "</span> " + arrow + " " + label + "</span>";
    });
    if (root.dataset.sig === html) return false;
    root.dataset.sig = html;
    root.innerHTML = html;
    return true;
  }

  function buildRobotButton(robot) {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "signal-pressure-fab signal-robot-fab trinity-plaque-robot "
      + toneClass(robot.tone);
    btn.dataset.key = robot.key || "";
    btn.dataset.href = robot.href || "/view";
    btn.dataset.playbook = robot.playbookId || "";
    btn.innerHTML =
      '<span class="signal-pressure-fab-dot" aria-hidden="true"></span>'
      + '<span class="signal-robot-fab-body">'
      + '<span class="signal-robot-fab-kicker">Робот</span>'
      + '<span class="signal-robot-fab-status"></span>'
      + '<span class="signal-robot-fab-scope"></span>'
      + '<span class="signal-robot-fab-detail"></span>'
      + "</span>";
    btn.addEventListener("click", function () {
      navigateRobot({
        href: btn.dataset.href,
        playbookId: btn.dataset.playbook,
        key: btn.dataset.key
      });
    });
    updateRobotButton(btn, robot);
    return btn;
  }

  function updateRobotButton(btn, robot) {
    if (!btn || !robot) return false;
    let changed = false;
    const scope = robot.title || "";
    const status = robot.status || "—";
    const detail = robot.detail || "";
    const prevTone = btn.dataset.tone || "";
    const nextTone = String(robot.tone || "scan");
    if (prevTone !== nextTone) {
      setTone(btn, nextTone);
      btn.dataset.tone = nextTone;
      changed = true;
    }
    if (btn.dataset.href !== (robot.href || "")) {
      btn.dataset.href = robot.href || "/view";
    }
    if (btn.dataset.playbook !== (robot.playbookId || "")) {
      btn.dataset.playbook = robot.playbookId || "";
    }
    changed = setText(btn.querySelector(".signal-robot-fab-status"), status) || changed;
    const scopeEl = btn.querySelector(".signal-robot-fab-scope");
    if (scopeEl) {
      scopeEl.hidden = !scope;
      changed = setText(scopeEl, scope) || changed;
    }
    changed = setText(btn.querySelector(".signal-robot-fab-detail"), detail) || changed;
    btn.setAttribute("aria-label", "Робот · " + status + (scope ? (" · " + scope) : ""));
    btn.title = status + (scope ? (" · " + scope) : "") + " — " + detail;
    return changed;
  }

  function buildWindButton(w) {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "signal-pressure-fab signal-wind-fab trinity-plaque-wind";
    btn.dataset.instrument = w.instrument || "";
    btn.innerHTML =
      '<span class="signal-pressure-fab-dot" aria-hidden="true"></span>'
      + '<span class="signal-robot-fab-body">'
      + '<span class="signal-robot-fab-kicker">Ветер (тренд)</span>'
      + '<span class="signal-wind-ticker"></span>'
      + '<span class="signal-wind-rows signal-wind-rows-host"></span>'
      + '<span class="signal-robot-fab-detail"></span>'
      + "</span>";
    btn.addEventListener("click", function () {
      focusWindInstrument(btn.dataset.instrument);
    });
    updateWindButton(btn, w);
    return btn;
  }

  function updateWindButton(btn, w) {
    if (!btn || !w) return false;
    let changed = false;
    const wind = w.wind || {};
    const explain = wind.explain || "Ветер считается…";
    const inst = w.instrument || "";
    if (btn.dataset.instrument !== inst) {
      btn.dataset.instrument = inst;
      changed = true;
    }
    const tick = btn.querySelector(".signal-wind-ticker");
    if (tick) {
      tick.hidden = !inst;
      changed = setText(tick, inst) || changed;
    }
    changed = paintWindLegs(btn.querySelector(".signal-wind-rows-host"), wind) || changed;
    changed = setText(btn.querySelector(".signal-robot-fab-detail"), explain) || changed;
    btn.title = explain;
    btn.setAttribute("aria-label", "Ветер (тренд) · " + inst);
    return changed;
  }

  function esc(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }

  function plaqueAuthHeaders(extra) {
    const headers = Object.assign({ Accept: "application/json" }, extra || {});
    try {
      const token = localStorage.getItem("trinity.supabase.access_token");
      if (token) {
        headers.Authorization = "Bearer " + token;
        return headers;
      }
      const user = (localStorage.getItem("imoex.ops.user") || "").trim();
      const pass = localStorage.getItem("imoex.ops.pass") || "";
      if (user && pass && user.indexOf("@") < 0) {
        headers.Authorization = "Basic " + btoa(unescape(encodeURIComponent(user + ":" + pass)));
      }
    } catch (_) {}
    return headers;
  }

  function navigateRobot(robot) {
    const pb = (robot && robot.playbookId) || "";
    if (pb.indexOf("positional") >= 0) {
      location.href = "/view/trend-positional";
      return;
    }
    if (pb.indexOf("levels-profile") >= 0) {
      location.href = "/view/trend-signal";
      return;
    }
    location.href = (robot && robot.href) || "/view";
  }

  function focusWindInstrument(secid) {
    if (!secid) return;
    if (pathIsCharts()) {
      const sel = $("charts-instrument");
      if (sel) {
        sel.value = secid;
        sel.dispatchEvent(new Event("change", { bubbles: true }));
      }
      const pane = document.querySelector('#charts-terminal-grid [data-secid="' + secid + '"]');
      if (pane && pane.scrollIntoView) pane.scrollIntoView({ behavior: "smooth", block: "center" });
      return;
    }
    if (pathIsSignal()) {
      const sel = $("sig-instrument");
      if (sel && sel.value !== secid) {
        sel.value = secid;
        sel.dispatchEvent(new Event("change", { bubbles: true }));
      }
      return;
    }
    location.href = "/view/trend-charts?instrument=" + encodeURIComponent(secid);
  }

  function layoutPlaques(force) {
    const host = $("trinity-plaque-host");
    if (!host) return;
    const pressure = $("signal-pressure-fab");
    const gap = 10;
    const base = 20;
    let bottom = base;
    let pressureH = 0;
    if (pressure && !pressure.hidden) {
      pressureH = Math.round(pressure.getBoundingClientRect().height || 0);
      bottom += Math.max(pressureH, 48) + gap;
    }
    const robots = host.querySelectorAll(".trinity-plaque-robot");
    const robotList = Array.prototype.slice.call(robots).reverse();
    const heights = [];
    robotList.forEach(function (el) {
      heights.push(Math.round(el.getBoundingClientRect().height || 0));
    });
    const rail = $("trinity-wind-rail");
    const railHidden = !rail || rail.hidden;
    const key = [pressureH, pressure && pressure.hidden ? 1 : 0, heights.join("x"), railHidden ? 0 : 1, bottom].join("|");
    if (!force && key === lastLayoutKey) return;
    lastLayoutKey = key;

    let b = bottom;
    robotList.forEach(function (el, i) {
      const nextBottom = b + "px";
      if (el.style.bottom !== nextBottom) el.style.bottom = nextBottom;
      el.style.position = "fixed";
      el.style.right = "1.15rem";
      b += Math.max(heights[i] || 0, 72) + gap;
    });
    if (rail && !rail.hidden) {
      const nextRailBottom = b + "px";
      if (rail.style.bottom !== nextRailBottom) rail.style.bottom = nextRailBottom;
      const maxH = Math.max(120, window.innerHeight - b - 24) + "px";
      if (rail.style.maxHeight !== maxH) rail.style.maxHeight = maxH;
    }
  }

  function scheduleLayout(force) {
    clearTimeout(layoutTimer);
    layoutTimer = setTimeout(function () {
      layoutPlaques(!!force);
    }, 32);
  }

  function payloadKey(data) {
    try {
      return JSON.stringify({
        r: (data.robots || []).map(function (x) {
          return [x.key, x.tone, x.status, x.detail, x.title];
        }),
        w: (data.winds || []).map(function (x) {
          const wind = x.wind || {};
          return [x.instrument, wind.explain,
            wind.h1 && wind.h1.dir, wind.h4 && wind.h4.dir, wind.day && wind.day.dir,
            wind.h1 && wind.h1.tone, wind.h4 && wind.h4.tone, wind.day && wind.day.tone];
        })
      });
    } catch (_) {
      return String(Date.now());
    }
  }

  function render(data) {
    const host = ensureHost();
    if (pathIsDashboard()) {
      host.hidden = true;
      return;
    }
    host.hidden = false;
    const stack = $("trinity-robot-stack");
    const rail = $("trinity-wind-rail");
    if (!stack) return;

    const key = payloadKey(data || {});
    const samePayload = key === lastPayloadKey;
    lastPayloadKey = key;

    const robots = (data && data.robots) || [];
    let structureChanged = false;
    let contentChanged = false;

    const existing = Array.prototype.slice.call(stack.querySelectorAll(".trinity-plaque-robot"));
    const byKey = {};
    existing.forEach(function (el) { byKey[el.dataset.key || ""] = el; });

    const order = robots.map(function (r) { return r.key || ""; });
    const existingOrder = existing.map(function (el) { return el.dataset.key || ""; });
    if (order.join("|") !== existingOrder.join("|") || existing.length !== robots.length) {
      structureChanged = true;
      stack.innerHTML = "";
      robots.forEach(function (r) {
        stack.appendChild(buildRobotButton(r));
      });
      contentChanged = true;
    } else {
      robots.forEach(function (r) {
        const el = byKey[r.key || ""];
        if (el && updateRobotButton(el, r)) contentChanged = true;
      });
    }

    if (wantsWind() && data && data.winds && data.winds.length) {
      if (rail.hidden) {
        rail.hidden = false;
        structureChanged = true;
      }
      const windBtns = Array.prototype.slice.call(rail.querySelectorAll(".trinity-plaque-wind"));
      const windOrder = data.winds.map(function (w) { return w.instrument || ""; });
      const curOrder = windBtns.map(function (el) { return el.dataset.instrument || ""; });
      if (windOrder.join("|") !== curOrder.join("|") || windBtns.length !== data.winds.length) {
        structureChanged = true;
        rail.innerHTML = "";
        data.winds.forEach(function (w) {
          rail.appendChild(buildWindButton(w));
        });
        contentChanged = true;
      } else {
        data.winds.forEach(function (w, i) {
          if (updateWindButton(windBtns[i], w)) contentChanged = true;
        });
      }
    } else if (rail && !rail.hidden) {
      rail.hidden = true;
      rail.innerHTML = "";
      structureChanged = true;
    }

    if (structureChanged || contentChanged || !samePayload) {
      scheduleLayout(structureChanged);
    }
  }

  async function refresh() {
    if (refreshInFlight) return;
    refreshInFlight = true;
    try {
      const wq = windsQuery();
      const url = "/api/desk/plaques" + (wq ? ("?winds=" + encodeURIComponent(wq)) : "");
      const res = await fetch(url, { headers: { Accept: "application/json" } });
      if (!res.ok) return;
      render(await res.json());
    } catch (_) {
      // silent
    } finally {
      refreshInFlight = false;
    }
  }

  function hideLocalDuplicates() {
    const localRobot = $("signal-status-robot");
    if (localRobot) localRobot.hidden = true;
    const localWind = $("signal-status-wind");
    if (localWind) localWind.hidden = true;
  }

  function boot() {
    hideLocalDuplicates();
    ensureHost();
    refresh();
    setInterval(refresh, POLL_MS);
    window.addEventListener("resize", function () { scheduleLayout(true); });
    const sigInst = $("sig-instrument");
    if (sigInst) {
      sigInst.addEventListener("change", function () { setTimeout(refresh, 500); });
    }
    const chartsInst = $("charts-instrument");
    if (chartsInst) {
      chartsInst.addEventListener("change", function () { setTimeout(refresh, 500); });
    }
    if (pathIsCharts()) {
      let tries = 0;
      const t = setInterval(function () {
        tries++;
        if (chartInstruments() || tries > 15) {
          clearInterval(t);
          refresh();
        }
      }, 800);
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }

  window.TrinityPlaques = {
    refresh: refresh,
    layout: function () { scheduleLayout(false); }
  };
})();
