/**
 * Decision Frame UI (DF-2): hero-итог + кратко/подробно + live details plaque.
 * Mount on any desk that serves `decisionFrame` JSON. Extension point for
 * investments / calendar-arb — same create({ root }).
 *
 * States: idle | updating | stale
 * Density: brief (hero + 1–2 why chips) | full (full summary + facts)
 */
(function (global) {
  "use strict";

  var SOURCE_RU = {
    delta: "дельта",
    dom: "стакан",
    shelf: "полка",
    poc: "POC",
    cluster: "кластер",
    footprint: "footprint",
    sessionNorm: "норма сессии",
    leadLag: "связка",
    sync: "расхождение",
    robot: "робот"
  };

  var TRIGGER_RU = {
    IMPULSE: "импульс",
    SHELF_TOUCH: "касание полки",
    ARM: "arm",
    WAIT: "ожидание",
    SKIP: "пропуск",
    ENTER: "вход",
    MODE: "смена режима"
  };

  /** Punchy operator labels for the 2–3s hero read. */
  var STATUS_RU = {
    ENTER: "в сделке",
    WAIT: "ждёт",
    SKIP: "пропуск"
  };

  var DENSITY_KEY = "trinity.df.density";
  var WHY_PRIORITY = ["sessionNorm", "leadLag", "robot", "shelf", "cluster", "footprint"];

  function esc(t) {
    return String(t == null ? "" : t)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function sourceRu(src) {
    if (!src) return "";
    return SOURCE_RU[src] || String(src);
  }

  /**
   * Effective badge status. Old cores tagged WAITING_FILL as ENTER («в сделке»);
   * if robot text is still about limits / armed grid — show «ждёт».
   */
  function effectiveStatus(frame) {
    var robot = (frame && frame.robot) || {};
    var status = String(robot.status || "").toUpperCase();
    if (status !== "ENTER") return status || "";
    var hero = robotDetailText(frame) || scrubPairs(frame && frame.summary) || "";
    if (/лимитки|ждёт касани|сетка вооружен|WAITING_FILL|ждёт исполнения/i.test(hero)
        && !/в позиции|вед[её]т сделку/i.test(hero)) {
      return "WAIT";
    }
    return "ENTER";
  }

  function statusRu(status, frame) {
    var s = frame ? effectiveStatus(frame) : String(status || "").toUpperCase();
    return STATUS_RU[s] || STATUS_RU[String(status || "").toUpperCase()] || "—";
  }

  function triggerRu(trigger) {
    if (!trigger) return "";
    return TRIGGER_RU[trigger] || String(trigger).toLowerCase();
  }

  function scrubPairs(text) {
    return String(text || "")
      .replace(/\bpairs\s*\d+(?:[.,]\d+)?\s*%\s*·\s*/gi, "")
      .replace(/\bpairs\s*\d+(?:[.,]\d+)?\s*%\s*/gi, "")
      .replace(/\(\s*·\s*/g, "(")
      .replace(/\s{2,}/g, " ")
      .trim();
  }

  function robotDetailText(frame) {
    var rows = frame && Array.isArray(frame.details) ? frame.details : [];
    for (var i = 0; i < rows.length; i++) {
      if (rows[i] && rows[i].source === "robot" && rows[i].text) {
        return scrubPairs(rows[i].text);
      }
    }
    var summary = scrubPairs(frame && frame.summary);
    var m = summary.match(/Робот\s+(?:пропускает|входит|ждёт|в позиции)[^.]*(?:\.|$)/i);
    return m ? m[0].trim() : summary;
  }

  function shortenChip(text, maxLen) {
    var s = scrubPairs(text).replace(/\s+/g, " ").trim();
    if (!s) return "";
    s = s.replace(/\.$/, "");
    // Drop leading «Робот …:» — status chip already says the decision.
    s = s.replace(/^Робот\s+(?:пропускает|входит|ждёт)[:：]\s*/i, "");
    s = s.replace(/^Норма сессии:\s*/i, "");
    s = s.replace(/^Связка:\s*/i, "");
    maxLen = maxLen || 72;
    if (s.length > maxLen) {
      s = s.slice(0, maxLen - 1).replace(/\s+\S*$/, "") + "…";
    }
    return s;
  }

  /**
   * 1–2 decision reasons for the hero (not microstructure noise).
   * Prefer session / lead-lag / robot; skip delta/dom/poc as third-block clutter.
   */
  function whyChips(frame, heroText) {
    var rows = frame && Array.isArray(frame.details) ? frame.details : [];
    var heroNorm = shortenChip(heroText || "", 200).toLowerCase();
    var out = [];
    var seen = Object.create(null);

    function push(d) {
      if (!d || !d.text || out.length >= 2) return;
      var src = String(d.source || "");
      if (src === "delta" || src === "dom" || src === "poc") return;
      var chip = shortenChip(d.text, 64);
      if (!chip) return;
      var key = chip.toLowerCase();
      if (seen[key]) return;
      if (heroNorm && (heroNorm.indexOf(key) >= 0 || key.indexOf(heroNorm.slice(0, 24)) >= 0)) {
        return;
      }
      seen[key] = true;
      out.push({ source: src, text: chip });
    }

    WHY_PRIORITY.forEach(function (want) {
      if (out.length >= 2) return;
      for (var i = 0; i < rows.length; i++) {
        if (rows[i] && rows[i].source === want) {
          push(rows[i]);
          break;
        }
      }
    });
    return out;
  }

  function $(root, id) {
    if (!root) return null;
    if (root.id === id) return root;
    return root.querySelector("#" + id) || document.getElementById(id);
  }

  function readDensity(root) {
    var fromDom = root && root.getAttribute("data-density");
    if (fromDom === "full" || fromDom === "brief") return fromDom;
    try {
      var stored = localStorage.getItem(DENSITY_KEY);
      if (stored === "full" || stored === "brief") return stored;
    } catch (_) {}
    return "brief";
  }

  /**
   * @param {{ root: string|HTMLElement, staleAfterMs?: number }} opts
   */
  function create(opts) {
    opts = opts || {};
    var root = typeof opts.root === "string"
      ? document.getElementById(opts.root)
      : opts.root;
    if (!root) {
      return null;
    }
    var staleAfterMs = opts.staleAfterMs > 0 ? opts.staleAfterMs : 90000;
    var open = false;
    var bound = false;
    var densityBound = false;
    var density = readDensity(root);
    var state = "idle";
    var lastFrameId = "";
    var lastSummary = "";
    var lastPaintAt = 0;
    var flashTimer = null;
    var lastFrame = null;

    var btn = $(root, "decision-frame-summary");
    var textEl = $(root, "decision-frame-text");
    var whyEl = $(root, "decision-frame-why");
    var statusEl = $(root, "decision-frame-status");
    var hint = $(root, "decision-frame-hint");
    var details = $(root, "decision-frame-details");
    var listEl = $(root, "decision-frame-details-list");
    var metaEl = $(root, "decision-frame-meta");
    var stateEl = $(root, "decision-frame-state");

    function applyDensityClass() {
      root.setAttribute("data-density", density);
      root.classList.toggle("is-density-brief", density === "brief");
      root.classList.toggle("is-density-full", density === "full");
      root.querySelectorAll("[data-df-density]").forEach(function (el) {
        el.classList.toggle("is-active", el.getAttribute("data-df-density") === density);
      });
    }

    function setDensity(next, optsLocal) {
      optsLocal = optsLocal || {};
      density = next === "full" ? "full" : "brief";
      try { localStorage.setItem(DENSITY_KEY, density); } catch (_) {}
      applyDensityClass();
      if (optsLocal.fromUser) {
        if (density === "full") setOpen(true);
        else if (density === "brief" && open) {
          // Keep open if operator already drilled in; only change copy density.
        }
      }
      if (lastFrame) paintCopy(lastFrame);
    }

    function setOpen(next) {
      open = !!next;
      if (btn) btn.setAttribute("aria-expanded", open ? "true" : "false");
      if (details) details.hidden = !open;
      if (hint) {
        hint.textContent = open
          ? "свернуть"
          : (density === "brief" ? "подробнее" : "факты");
      }
      root.classList.toggle("is-open", open);
    }

    function setState(next, label) {
      state = next || "idle";
      root.dataset.state = state;
      root.classList.remove("is-idle", "is-updating", "is-stale");
      root.classList.add("is-" + state);
      if (stateEl) {
        if (state === "updating") stateEl.textContent = label || "обновляю…";
        else if (state === "stale") stateEl.textContent = label || "устарело";
        else stateEl.textContent = label || "";
        stateEl.hidden = !stateEl.textContent;
      }
    }

    function paintWhy(frame, heroText) {
      if (!whyEl) return;
      var chips = whyChips(frame, heroText);
      if (!chips.length) {
        whyEl.innerHTML = "";
        whyEl.hidden = true;
        return;
      }
      whyEl.innerHTML = chips.map(function (c) {
        return "<span class=\"decision-frame-why-chip\" data-source=\""
          + esc(c.source) + "\" title=\"" + esc(sourceRu(c.source)) + "\">"
          + esc(c.text) + "</span>";
      }).join("");
      whyEl.hidden = density === "full";
    }

    function paintCopy(frame) {
      var robot = frame.robot || {};
      var fullSummary = scrubPairs(frame.summary || "");
      var hero = robotDetailText(frame) || fullSummary || "—";
      var badgeStatus = effectiveStatus(frame) || robot.status || "";
      if (statusEl) {
        statusEl.textContent = statusRu(badgeStatus, frame);
        statusEl.dataset.status = badgeStatus;
      }
      if (textEl) {
        textEl.textContent = density === "brief" ? hero : (fullSummary || hero);
      }
      paintWhy(frame, hero);
      if (hint && !open) {
        hint.textContent = density === "brief" ? "подробнее" : "факты";
      }
    }

    function bind() {
      if (bound || !btn) return;
      bound = true;
      btn.addEventListener("click", function () {
        setOpen(!open);
        if (open && listEl && listEl.dataset.pendingHtml) {
          listEl.innerHTML = listEl.dataset.pendingHtml;
          delete listEl.dataset.pendingHtml;
        }
      });
      bindDensity();
    }

    function bindDensity() {
      if (densityBound) return;
      var bar = root.querySelector(".decision-frame-density");
      if (!bar) return;
      densityBound = true;
      applyDensityClass();
      bar.addEventListener("click", function (ev) {
        var t = ev.target && ev.target.closest
          ? ev.target.closest("[data-df-density]")
          : null;
        if (!t) return;
        ev.preventDefault();
        ev.stopPropagation();
        setDensity(t.getAttribute("data-df-density") || "brief", { fromUser: true });
      });
    }

    function paintMeta(frame) {
      if (!metaEl) return;
      var bits = [];
      var tr = triggerRu(frame.trigger);
      if (tr) bits.push(tr);
      if (frame.barTime) bits.push(String(frame.barTime).replace("T", " "));
      else if (frame.asOf) bits.push(String(frame.asOf).replace("T", " "));
      if (frame.instrument) bits.push(String(frame.instrument));
      metaEl.textContent = bits.join(" · ");
      metaEl.hidden = bits.length === 0 || density === "brief";
    }

    function flashUpdated() {
      root.classList.add("is-live-flash");
      if (flashTimer) clearTimeout(flashTimer);
      flashTimer = setTimeout(function () {
        root.classList.remove("is-live-flash");
      }, 700);
      if (stateEl && state === "idle") {
        stateEl.textContent = "обновлено";
        stateEl.hidden = false;
        setTimeout(function () {
          if (state === "idle" && stateEl && stateEl.textContent === "обновлено") {
            stateEl.textContent = "";
            stateEl.hidden = true;
          }
        }, 1200);
      }
    }

    function markUpdating() {
      bind();
      // Quiet refresh when a frame is already on screen — no «обновляю…» flicker every poll.
      if (lastFrameId) {
        root.hidden = false;
        root.dataset.state = "updating";
        root.classList.add("is-updating");
        root.classList.remove("is-idle", "is-stale");
        state = "updating";
        return;
      }
      root.hidden = false;
      setState("updating");
    }

    function markStale(reason) {
      bind();
      if (!lastFrameId && (!textEl || !textEl.textContent || textEl.textContent === "Загрузка…")) {
        root.hidden = true;
        setState("stale", reason || "нет кадра");
        return;
      }
      root.hidden = false;
      setState("stale", reason || "устарело");
    }

    function render(frame) {
      bind();
      if (!frame || !frame.summary) {
        markStale("нет кадра");
        return;
      }
      root.hidden = false;
      lastFrame = frame;
      paintCopy(frame);
      root.dataset.trigger = frame.trigger || "";
      root.dataset.frameId = frame.frameId || "";
      paintMeta(frame);
      setOpen(open);
      var html = detailsHtml(frame);
      var changed = (frame.frameId || "") !== lastFrameId
        || String(frame.summary || "") !== lastSummary;
      if (listEl) {
        if (open || changed || !listEl.innerHTML) {
          listEl.innerHTML = html;
          delete listEl.dataset.pendingHtml;
        } else {
          listEl.dataset.pendingHtml = html;
        }
      }
      if (changed && lastFrameId && open) {
        flashUpdated();
      }
      lastFrameId = frame.frameId || "";
      lastSummary = String(frame.summary || "");
      lastPaintAt = Date.now();
      setState("idle");
    }

    function tickStale() {
      if (!lastPaintAt || state === "updating") return;
      if (Date.now() - lastPaintAt > staleAfterMs) {
        setState("stale", "давно без обновления");
      }
    }

    applyDensityClass();
    var staleTimer = setInterval(tickStale, 15000);

    return {
      markUpdating: markUpdating,
      markStale: markStale,
      render: render,
      setDensity: setDensity,
      density: function () { return density; },
      isOpen: function () { return open; },
      state: function () { return state; },
      destroy: function () {
        if (staleTimer) clearInterval(staleTimer);
        if (flashTimer) clearTimeout(flashTimer);
      }
    };
  }

  function detailsHtml(frame) {
    var rows = frame && Array.isArray(frame.details) ? frame.details : [];
    var html = "";
    rows.forEach(function (d) {
      if (!d || !d.text) return;
      var src = d.source ? String(d.source) : "";
      html += "<li data-source=\"" + esc(src) + "\">"
        + "<span class=\"decision-frame-source\">" + esc(sourceRu(src)) + "</span>"
        + esc(scrubPairs(d.text))
        + "</li>";
    });
    return html || "<li>Фактов в кадре пока нет.</li>";
  }

  global.TrinityDecisionFrame = {
    create: create,
    sourceRu: sourceRu,
    statusRu: statusRu,
    effectiveStatus: effectiveStatus,
    triggerRu: triggerRu,
    detailsHtml: detailsHtml,
    whyChips: whyChips,
    robotDetailText: robotDetailText,
    SOURCE_RU: SOURCE_RU
  };
})(typeof window !== "undefined" ? window : globalThis);
