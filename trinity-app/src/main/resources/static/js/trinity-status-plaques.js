/**
 * Global floating plaques on every strategy desk (/view/* except dashboard).
 * Wind (trend) only on Сигнал desks. Cookie session — do not send a stale Bearer.
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
    return p === "/view" || p === "/view/" || p === "/";
  }

  function pathWantsPlaques() {
    const p = location.pathname || "";
    if (pathIsDashboard()) return false;
    return p.indexOf("/view/") === 0;
  }

  function pathIsCharts() {
    return location.pathname.indexOf("/view/investments") >= 0
      || location.pathname.indexOf("/view/charts") >= 0;
  }

  function pathIsSignal() {
    return location.pathname.indexOf("/view/trend-signal") >= 0
      || location.pathname.indexOf("/view/trend-positional") >= 0
      || location.pathname.indexOf("/view/trend-brm") >= 0;
  }
  function wantsWind() {
    return pathIsSignal();
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
      // Never default to "all" — that loads every family H1 every poll and storms YGC.
      return "active";
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
    /* Do not attach a stored Bearer here: an expired JWT 401s and skips the
     * trinity.desk cookie. trinity-fast-boot injects a live token when it can. */
    return Object.assign({ Accept: "application/json" }, extra || {});
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
    if (pb.indexOf("retail-brm") >= 0 || pb.indexOf("brm") >= 0) {
      location.href = "/view/trend-brm";
      return;
    }
    if ((robot && robot.key) === "investments" || pb.indexOf("invest") >= 0) {
      location.href = "/view/investments";
      return;
    }
    if ((robot && robot.key) === "calendar-arb" || pb.indexOf("calendar") >= 0) {
      location.href = "/view/calendar-arb";
      return;
    }
    if ((robot && robot.key) === "spread" || pb.indexOf("dual") >= 0) {
      location.href = "/view/spread";
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
    location.href = "/view/investments?instrument=" + encodeURIComponent(secid);
  }

  function chromeBottomPx() {
    let bottom = 0;
    const nodes = document.querySelectorAll(
      ".site-header, nav.topnav, nav.topnav-secondary:not([hidden]), .auth-session-bar:not([hidden])"
    );
    for (let i = 0; i < nodes.length; i++) {
      const r = nodes[i].getBoundingClientRect();
      if (r.bottom > bottom) bottom = r.bottom;
    }
    return Math.max(0, Math.ceil(bottom));
  }

  function layoutPlaques(force) {
    const host = $("trinity-plaque-host");
    if (!host) return;
    const clipTop = chromeBottomPx();
    const pressure = $("signal-pressure-fab");
    let padBottom = 22;
    if (pressure && !pressure.hidden) {
      const ph = Math.round(pressure.getBoundingClientRect().height || 0);
      padBottom = Math.max(padBottom, Math.max(ph, 48) + 18);
    }
    const key = [clipTop, padBottom, host.scrollHeight, host.clientHeight].join("|");
    if (!force && key === lastLayoutKey) return;
    lastLayoutKey = key;

    host.style.top = clipTop + "px";
    host.style.right = "0";
    host.style.bottom = "0";
    host.style.width = "12.2rem";
    host.style.paddingBottom = padBottom + "px";

    const robots = host.querySelectorAll(".trinity-plaque-robot");
    robots.forEach(function (el) {
      el.style.position = "";
      el.style.right = "";
      el.style.bottom = "";
      el.style.left = "";
    });
    const rail = $("trinity-wind-rail");
    if (rail) {
      rail.style.right = "";
      rail.style.bottom = "";
      rail.style.maxHeight = "";
    }
    /* Keep the lower plaques (current desk) in view; scroll up for the rest. */
    if (host.scrollHeight > host.clientHeight) {
      host.scrollTop = host.scrollHeight;
    } else {
      host.scrollTop = 0;
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
    if (!pathWantsPlaques()) {
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

  let refreshRetryTimer = 0;

  async function refresh() {
    if (refreshInFlight) return;
    refreshInFlight = true;
    try {
      const wq = windsQuery();
      const url = "/api/desk/plaques" + (wq ? ("?winds=" + encodeURIComponent(wq)) : "");
      let res = await fetch(url, { credentials: "include", headers: plaqueAuthHeaders() });
      if (res.status === 401) {
        res = await fetch(url, { credentials: "include", headers: { Accept: "application/json" } });
      }
      const ct = (res.headers.get("content-type") || "").toLowerCase();
      if (!res.ok || ct.indexOf("json") < 0) {
        scheduleRefreshRetry();
        return;
      }
      render(await res.json());
    } catch (_) {
      scheduleRefreshRetry();
    } finally {
      refreshInFlight = false;
    }
  }

  function scheduleRefreshRetry() {
    clearTimeout(refreshRetryTimer);
    refreshRetryTimer = setTimeout(refresh, 2500);
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
    window.addEventListener("scroll", function () { scheduleLayout(false); }, { passive: true });
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
