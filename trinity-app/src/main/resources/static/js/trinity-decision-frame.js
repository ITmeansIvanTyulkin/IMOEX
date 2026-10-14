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
    robot: "робот",
    allocator: "капитал"
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
   * Effective badge status — same truth as plaques / journal.
   * Old cores tagged WAITING_FILL as ENTER («в сделке»); limits text → WAIT.
   * @param {object} frame
   * @param {string=} entryStatus optional journal robotStatus override
   */
  function effectiveStatus(frame, entryStatus) {
    var robot = (frame && frame.robot) || {};
    var status = String(robot.status || entryStatus || "").toUpperCase();
    if (status !== "ENTER") return status || "";
    var hero = robotDetailText(frame) || scrubPairs(frame && frame.summary) || "";
    if (/лимитки|ждёт касани|ждет касани|сетка вооружен|WAITING_FILL|ждёт исполнения|ждет исполнения/i.test(hero)
        && !/в позиции|вед[её]т сделку/i.test(hero)) {
      return "WAIT";
    }
    return "ENTER";
  }

  /** Operator label aligned with right-rail plaques. */
  function statusRu(status, frame, entryStatus) {
    var s = frame
      ? effectiveStatus(frame, entryStatus)
      : String(status || entryStatus || "").toUpperCase();
    if (s === "WAIT" && frame) {
      var hero = robotDetailText(frame) || scrubPairs(frame.summary) || "";
      if (/лимитки|сетка вооружен|ждёт касани|ждет касани|WAITING_FILL|ждёт исполнения|ждет исполнения/i.test(hero)) {
        return "ждёт исполнения";
      }
    }
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
    var onFactFocus = typeof opts.onFactFocus === "function" ? opts.onFactFocus : null;
    var open = false;
    var bound = false;
    var densityBound = false;
    var factBound = false;
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
          + esc(c.source) + "\" title=\"" + esc(sourceRu(c.source))
          + " · показать на графике\" tabindex=\"0\" role=\"button\">"
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

    function flushPendingDetails() {
      if (listEl && listEl.dataset.pendingHtml) {
        listEl.innerHTML = listEl.dataset.pendingHtml;
        delete listEl.dataset.pendingHtml;
      }
    }

    function bindFactClicks() {
      if (factBound || !onFactFocus) return;
      factBound = true;
      bindFactFocus({
        host: root,
        origin: "live",
        getFrame: function () { return lastFrame; },
        onFactFocus: onFactFocus,
        onWhyChip: function (chip, thenFocus) {
          setOpen(true);
          flushPendingDetails();
          var src = chip.getAttribute("data-source") || "";
          var li = src && listEl
            ? listEl.querySelector(".decision-frame-fact[data-source=\"" + src + "\"]")
            : null;
          if (li) {
            markFactActive(root, li);
            var detail = detailFromEl(li, lastFrame);
            if (detail) onFactFocus(detail, { origin: "live", el: li, frame: lastFrame });
            try { li.scrollIntoView({ block: "nearest" }); } catch (_) {}
            return;
          }
          thenFocus();
        }
      });
    }

    function bind() {
      if (bound || !btn) return;
      bound = true;
      btn.addEventListener("click", function () {
        setOpen(!open);
        if (open) flushPendingDetails();
      });
      bindDensity();
      bindFactClicks();
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
      clearFactFocus: function () {
        clearFactActive(root);
        if (onFactFocus) onFactFocus(null, { origin: "clear", el: null, frame: null });
      },
      destroy: function () {
        if (staleTimer) clearInterval(staleTimer);
        if (flashTimer) clearTimeout(flashTimer);
      }
    };
  }

  function factLiAttrs(d) {
    var src = d && d.source ? String(d.source) : "";
    var val = d && d.value != null && d.value !== "" ? String(d.value) : "";
    var layer = d && d.layer != null ? String(d.layer) : "";
    return " class=\"decision-frame-fact\""
      + " data-source=\"" + esc(src) + "\""
      + (val !== "" ? (" data-value=\"" + esc(val) + "\"") : "")
      + (layer !== "" ? (" data-layer=\"" + esc(layer) + "\"") : "")
      + " tabindex=\"0\" role=\"button\""
      + " title=\"Показать на графике / в стакане\"";
  }

  function detailsHtml(frame) {
    var rows = frame && Array.isArray(frame.details) ? frame.details : [];
    var html = "";
    rows.forEach(function (d) {
      if (!d || !d.text) return;
      var src = d.source ? String(d.source) : "";
      html += "<li" + factLiAttrs(d) + ">"
        + "<span class=\"decision-frame-source\">" + esc(sourceRu(src)) + "</span>"
        + esc(scrubPairs(d.text))
        + "</li>";
    });
    return html || "<li class=\"decision-frame-fact-empty\">Фактов в кадре пока нет.</li>";
  }

  function clearFactActive(scope) {
    var root = scope || document;
    root.querySelectorAll(".decision-frame-fact.is-fact-active, .decision-frame-why-chip.is-fact-active")
      .forEach(function (n) { n.classList.remove("is-fact-active"); });
  }

  function markFactActive(scope, el) {
    clearFactActive(scope);
    if (el) el.classList.add("is-fact-active");
  }

  /** Build focus payload from a fact / why-chip element + optional frame. */
  function detailFromEl(el, frame) {
    if (!el) return null;
    var src = el.getAttribute("data-source") || "";
    if (!src) return null;
    var valueAttr = el.getAttribute("data-value");
    var value = null;
    if (valueAttr != null && valueAttr !== "") {
      var n = Number(valueAttr);
      value = isFinite(n) ? n : valueAttr;
    }
    var layerAttr = el.getAttribute("data-layer");
    var layer = layerAttr != null && layerAttr !== "" ? Number(layerAttr) : 0;
    var text = "";
    var matched = null;
    if (frame && Array.isArray(frame.details)) {
      var i;
      for (i = 0; i < frame.details.length; i++) {
        var d = frame.details[i];
        if (!d || String(d.source) !== src) continue;
        if (value != null && d.value != null && Number(d.value) === Number(value)) {
          matched = d;
          break;
        }
        if (!matched) matched = d;
      }
    }
    if (matched) {
      text = scrubPairs(matched.text || "");
      if (matched.value != null && value == null) value = matched.value;
      if (matched.layer != null && !layer) layer = matched.layer;
    } else {
      var clone = el.cloneNode(true);
      var srcEl = clone.querySelector(".decision-frame-source");
      if (srcEl) srcEl.parentNode.removeChild(srcEl);
      text = scrubPairs(clone.textContent || "");
    }
    var host = el.closest("[data-bar-time], [data-df-bar-time], .signal-df-journal-member, .signal-df-journal-item");
    var barTime = (host && (host.getAttribute("data-bar-time") || host.getAttribute("data-df-bar-time")))
      || (frame && (frame.barTime || frame.asOf))
      || "";
    var instrument = (host && host.getAttribute("data-instrument"))
      || (frame && frame.instrument)
      || "";
    return {
      source: src,
      value: value,
      layer: layer || 0,
      text: text,
      barTime: barTime || null,
      instrument: instrument || null,
      frame: frame || null
    };
  }

  /**
   * Click / keyboard on fact rows and why-chips → onFactFocus(detail|null, ctx).
   * Esc clears selection and calls onFactFocus(null).
   */
  function bindFactFocus(opts) {
    opts = opts || {};
    var host = opts.host;
    var onFactFocus = opts.onFactFocus;
    if (!host || typeof onFactFocus !== "function") return;
    if (host.dataset.dfFactBound === "1") return;
    host.dataset.dfFactBound = "1";
    var origin = opts.origin || "live";

    function scopeRoot() {
      return host.closest(".decision-frame, .signal-df-journal") || host;
    }

    function fire(el) {
      var frame = typeof opts.getFrame === "function" ? opts.getFrame(el) : null;
      var detail = detailFromEl(el, frame);
      if (!detail) return;
      markFactActive(scopeRoot(), el);
      onFactFocus(detail, { origin: origin, el: el, frame: frame || detail.frame });
    }

    host.addEventListener("click", function (ev) {
      if (ev.target.closest
          && ev.target.closest("[data-df-toggle], [data-df-density], [data-df-kind], a, button.signal-df-journal-toggle")) {
        return;
      }
      var el = ev.target && ev.target.closest
        ? ev.target.closest(".decision-frame-fact[data-source], .decision-frame-why-chip[data-source]")
        : null;
      if (!el || !host.contains(el)) return;
      ev.preventDefault();
      ev.stopPropagation();
      if (el.classList.contains("decision-frame-why-chip") && typeof opts.onWhyChip === "function") {
        opts.onWhyChip(el, function () { fire(el); });
        return;
      }
      fire(el);
    });

    host.addEventListener("keydown", function (ev) {
      if (ev.key !== "Enter" && ev.key !== " ") return;
      var el = ev.target && ev.target.closest
        ? ev.target.closest(".decision-frame-fact[data-source]")
        : null;
      if (!el || !host.contains(el)) return;
      ev.preventDefault();
      fire(el);
    });

    if (typeof onFactFocus === "function") {
      if (!global.__trinityDfFactClearFns) global.__trinityDfFactClearFns = [];
      if (global.__trinityDfFactClearFns.indexOf(onFactFocus) < 0) {
        global.__trinityDfFactClearFns.push(onFactFocus);
      }
    }
    if (!global.__trinityDfFactEscBound) {
      global.__trinityDfFactEscBound = true;
      document.addEventListener("keydown", function (ev) {
        if (ev.key !== "Escape") return;
        var active = document.querySelector(
          ".decision-frame-fact.is-fact-active, .decision-frame-why-chip.is-fact-active, .is-df-focus"
        );
        if (!active && !(global.__trinityDfFactClearFns || []).length) return;
        clearFactActive(document);
        (global.__trinityDfFactClearFns || []).forEach(function (fn) {
          try { fn(null, { origin: "clear", el: null, frame: null }); } catch (_) {}
        });
      });
    }
  }

  function shortHm(iso) {
    var s = String(iso || "");
    var m = s.match(/T(\d{2}:\d{2})/);
    return m ? m[1] : (s.length >= 5 ? s.slice(-5) : (s || "—"));
  }

  function frameForBadge(e) {
    var frame = (e && e.frame) ? Object.assign({}, e.frame) : (e || {});
    if (e && e.summary && !frame.summary) frame.summary = e.summary;
    return frame;
  }

  function entryBadge(e) {
    var frame = frameForBadge(e);
    var entryStatus = e && e.robotStatus;
    var st = effectiveStatus(frame, entryStatus);
    return { status: st, label: statusRu(st, frame, entryStatus), frame: frame };
  }

  function displayKind(e) {
    if (!e) return "wait";
    if (String(e.kind || "").toLowerCase() === "alloc") return "alloc";
    var st = entryBadge(e).status;
    if (st === "ENTER") return "enter";
    if (st === "SKIP") return "skip";
    return "wait";
  }

  /** Collapse key: same status/kind/inst + reason without volatile numbers. */
  function compressKey(e) {
    if (!e) return "";
    var frame = e.frame || {};
    var status = String(entryBadge(e).status || "").toUpperCase();
    var kind = displayKind(e);
    if (status !== "SKIP" && status !== "WAIT") return "";
    if (kind === "enter" || kind === "alloc") return "";
    var reason = "";
    var details = Array.isArray(frame.details) ? frame.details : [];
    var i;
    for (i = 0; i < details.length; i++) {
      var d = details[i];
      if (!d || !d.text) continue;
      if (d.source === "sessionNorm" || d.source === "robot" || d.source === "leadLag") {
        reason = scrubPairs(d.text);
        if (d.source === "sessionNorm" || d.source === "robot") break;
      }
    }
    if (!reason) reason = scrubPairs(e.summary || frame.summary || "");
    reason = reason
      .replace(/\d{1,2}:\d{2}(?::\d{2})?/g, "")
      .replace(/\d+[.,]\d+/g, "#")
      .replace(/[+-]?\d{3,}/g, "#")
      .replace(/\s+/g, " ")
      .trim()
      .toLowerCase()
      .slice(0, 140);
    var inst = String(e.instrument || frame.instrument || "").toUpperCase();
    return status + "|" + kind + "|" + inst + "|" + reason;
  }

  function groupWhyBits(entries) {
    var e = entries[0] || {};
    var frame = e.frame || {};
    var details = Array.isArray(frame.details) ? frame.details : [];
    var bits = [];
    var i;
    for (i = 0; i < details.length; i++) {
      var d = details[i];
      if (!d || !d.text) continue;
      if (d.source !== "sessionNorm" && d.source !== "leadLag" && d.source !== "robot") continue;
      var t = scrubPairs(d.text)
        .replace(/^Норма сессии:\s*/i, "")
        .replace(/^Робот\s+(?:пропускает|ждёт|входит)[:：]\s*/i, "")
        .replace(/\.$/, "");
      if (/окн[оа].*закрыт|правил[оа] сессии/i.test(t)) t = "окно закрыто";
      if (t.length > 42) t = t.slice(0, 41) + "…";
      if (t && bits.indexOf(t) < 0) bits.push(t);
      if (bits.length >= 3) break;
    }
    if (!bits.length) {
      var s = scrubPairs(e.summary || frame.summary || "");
      s = s.replace(/^Робот\s+(?:пропускает|ждёт)[:：]\s*/i, "").replace(/\.$/, "");
      if (s) bits.push(s.length > 52 ? s.slice(0, 51) + "…" : s);
    }
    return bits.join(" · ") || "тот же итог";
  }

  function groupHeadline(entries, statusRuLabel) {
    var n = entries.length;
    var times = entries.map(function (e) {
      return shortHm(e.barTime || e.recordedAt || (e.frame && e.frame.barTime));
    }).filter(Boolean);
    var a = times.length ? times[times.length - 1] : "—";
    var b = times.length ? times[0] : "—";
    var range = a === b ? a : (a + "–" + b);
    var verb = statusRuLabel || "ждёт";
    if (/исполнен/i.test(verb)) verb = "Ждёт";
    else if (/пропуск/i.test(verb)) verb = "Пропуск";
    else if (/сделк/i.test(verb)) verb = "В сделке";
    else verb = verb.charAt(0).toUpperCase() + verb.slice(1);
    return {
      title: verb + " × " + n + " (" + range + ")",
      why: groupWhyBits(entries),
      range: range,
      count: n
    };
  }

  /**
   * Group consecutive identical WAIT/SKIP frames. Threshold 2.
   * Expand shows each member's facts (not only the latest).
   */
  function compressEntries(entries) {
    var out = [];
    var i = 0;
    while (i < entries.length) {
      var e = entries[i];
      var key = compressKey(e);
      if (!key) {
        out.push({ group: false, entry: e });
        i += 1;
        continue;
      }
      var j = i + 1;
      while (j < entries.length && compressKey(entries[j]) === key) j += 1;
      var slice = entries.slice(i, j);
      if (slice.length < 2) {
        slice.forEach(function (one) { out.push({ group: false, entry: one }); });
      } else {
        var first = slice[slice.length - 1] || {};
        var last = slice[0] || {};
        var firstId = String(first.id || (first.frame && first.frame.frameId) || i);
        var lastId = String(last.id || (last.frame && last.frame.frameId) || j);
        var badge = entryBadge(slice[0]);
        var head = groupHeadline(slice, badge.label);
        out.push({
          group: true,
          id: "g:" + firstId + ":" + lastId + ":" + slice.length,
          key: key,
          count: slice.length,
          entries: slice,
          label: head.title,
          why: head.why,
          range: head.range,
          badge: badge
        });
      }
      i = j;
    }
    return out;
  }

  function listSig(filtered, kind) {
    var groups = compressEntries(filtered || []);
    return String(kind || "") + "#" + groups.map(function (g) {
      if (g.group) {
        return "G:" + g.id + ":" + g.count + ":" + g.label + ":" + (g.why || "");
      }
      var e = g.entry;
      return [e && e.id, e && e.kind, e && e.robotStatus, e && e.barTime, e && e.summary].join(":");
    }).join("|");
  }

  /**
   * Shared DF journal for every strategy desk (trend / positional / BRM / invest / arb).
   * @param {{
   *   panel?: string|HTMLElement,
   *   list?: string|HTMLElement,
   *   meta?: string|HTMLElement,
   *   title?: string|HTMLElement,
   *   filters?: string|HTMLElement,
   *   humanizeSummary?: function(string): string,
   *   shortTime?: function(*): string
   * }} opts
   */
  function mountJournal(opts) {
    opts = opts || {};
    function el(ref) {
      if (!ref) return null;
      if (typeof ref === "string") return document.getElementById(ref);
      return ref;
    }
    var panel = el(opts.panel || "signal-df-journal");
    var listEl = el(opts.list || "signal-df-journal-list");
    var meta = el(opts.meta || "signal-df-journal-meta");
    var title = el(opts.title || "signal-df-journal-title");
    var filters = el(opts.filters || "signal-df-journal-filters");
    if (!panel || !listEl) return null;

    var kind = "";
    var cache = null;
    var bound = false;
    var openIds = Object.create(null);
    var onFactFocus = typeof opts.onFactFocus === "function" ? opts.onFactFocus : null;
    var humanize = typeof opts.humanizeSummary === "function"
      ? opts.humanizeSummary
      : function (t) { return scrubPairs(t) || "—"; };
    var timeFn = typeof opts.shortTime === "function" ? opts.shortTime : shortHm;

    function frameForJournalEl(el) {
      if (!el || !cache || !Array.isArray(cache.entries)) return null;
      var item = el.closest(".signal-df-journal-member, .signal-df-journal-item");
      if (!item) return null;
      var id = item.getAttribute("data-df-id") || "";
      var fid = item.getAttribute("data-frame-id") || "";
      var entries = cache.entries;
      var i;
      for (i = 0; i < entries.length; i++) {
        var e = entries[i];
        if (!e) continue;
        var fr = e.frame || {};
        var eid = String(e.id || fr.frameId || "");
        if (fid && (eid === fid || String(fr.frameId || "") === fid)) return fr;
        if (id === eid || id === ("m:" + eid)) return fr;
      }
      return null;
    }

    function bind() {
      if (bound) return;
      bound = true;
      if (filters) {
        filters.addEventListener("click", function (ev) {
          var btn = ev.target && ev.target.closest
            ? ev.target.closest("[data-df-kind]")
            : null;
          if (!btn) return;
          kind = btn.getAttribute("data-df-kind") || "";
          filters.querySelectorAll(".signal-df-kind").forEach(function (node) {
            node.classList.toggle("is-active", node === btn);
          });
          paint(cache);
        });
      }
      listEl.addEventListener("click", function (ev) {
        var btn = ev.target && ev.target.closest
          ? ev.target.closest("[data-df-toggle]")
          : null;
        if (!btn) return;
        ev.stopPropagation();
        var item = btn.closest(".signal-df-journal-item, .signal-df-journal-member");
        if (!item) return;
        var details = null;
        for (var ci = 0; ci < item.children.length; ci++) {
          if (item.children[ci].classList
              && item.children[ci].classList.contains("signal-df-journal-details")) {
            details = item.children[ci];
            break;
          }
        }
        if (!details) return;
        var id = item.getAttribute("data-df-id") || "";
        var open = details.hidden;
        details.hidden = !open;
        btn.setAttribute("aria-expanded", open ? "true" : "false");
        if (id) {
          if (open) openIds[id] = true;
          else delete openIds[id];
        }
      });
      if (onFactFocus) {
        bindFactFocus({
          host: listEl,
          origin: "journal",
          getFrame: frameForJournalEl,
          onFactFocus: onFactFocus
        });
      }
    }

    function memberHtml(e) {
      var frame = e.frame || {};
      var badge = entryBadge(e);
      var id = "m:" + String(e.id || frame.frameId || Math.random());
      var open = !!openIds[id];
      var barIso = e.barTime || e.recordedAt || frame.barTime || "";
      var inst = e.instrument || frame.instrument || "";
      var t = timeFn(barIso);
      var summary = humanize(e.summary || frame.summary || "—");
      return "<li class=\"signal-df-journal-member\" data-df-id=\"" + esc(id) + "\""
        + " data-frame-id=\"" + esc(String(e.id || frame.frameId || "")) + "\""
        + " data-bar-time=\"" + esc(barIso) + "\""
        + " data-instrument=\"" + esc(inst) + "\">"
        + "<button type=\"button\" class=\"signal-df-journal-toggle is-member\" data-df-toggle"
        + " aria-expanded=\"" + (open ? "true" : "false") + "\">"
        + "<span class=\"signal-df-journal-time\">" + esc(t) + "</span>"
        + "<span class=\"signal-df-journal-summary\">" + esc(summary) + "</span>"
        + "</button>"
        + "<div class=\"signal-df-journal-details decision-frame-details\""
        + (open ? "" : " hidden") + ">"
        + "<ul class=\"decision-frame-details-list\">" + detailsHtml(frame) + "</ul>"
        + "</div></li>";
    }

    function paint(payload) {
      bind();
      cache = payload || null;
      var entries = (payload && Array.isArray(payload.entries)) ? payload.entries : [];
      var kinds = { wait: 0, enter: 0, skip: 0, alloc: 0 };
      entries.forEach(function (e) {
        var k = displayKind(e);
        kinds[k] = (kinds[k] || 0) + 1;
      });
      var filtered = kind
        ? entries.filter(function (e) { return displayKind(e) === kind; })
        : entries;
      if (!entries.length) {
        panel.hidden = true;
        listEl.innerHTML = "";
        listEl.dataset.sig = "";
        if (meta) meta.textContent = "";
        return;
      }
      panel.hidden = false;
      if (title) {
        var day = (payload && payload.day) || "";
        title.textContent = "Журнал кадров" + (day ? (" · " + day) : "");
      }
      var groups = compressEntries(filtered);
      if (meta) {
        meta.textContent = "Строк " + groups.length
          + " · кадров " + filtered.length
          + " / " + entries.length
          + " · ждёт " + (kinds.wait || 0)
          + " · вошёл " + (kinds.enter || 0)
          + " · пропуск " + (kinds.skip || 0)
          + " · капитал " + (kinds.alloc || 0)
          + (filtered.length === 0 && kind ? " · нет кадров с этим фильтром" : "");
      }
      if (!filtered.length) {
        listEl.dataset.sig = listSig([], kind);
        listEl.innerHTML = "<li class=\"signal-df-journal-empty\">Нет кадров с этим фильтром.</li>";
        return;
      }
      var sig = listSig(filtered, kind);
      if (listEl.dataset.sig === sig && listEl.querySelector(".signal-df-journal-item")) {
        return;
      }
      listEl.dataset.sig = sig;
      listEl.innerHTML = groups.map(function (g) {
        if (g.group) {
          var latest = g.entries[0] || {};
          var frame = latest.frame || {};
          var badge = g.badge || entryBadge(latest);
          var id = g.id;
          var open = !!openIds[id];
          var inst = latest.instrument || frame.instrument || "";
          return "<li class=\"signal-df-journal-item is-group\" data-df-id=\"" + esc(id) + "\">"
            + "<button type=\"button\" class=\"signal-df-journal-toggle\" data-df-toggle"
            + " aria-expanded=\"" + (open ? "true" : "false") + "\">"
            + "<span class=\"signal-df-journal-status\" data-status=\"" + esc(badge.status) + "\">"
            + esc(badge.label) + "</span>"
            + "<span class=\"signal-df-journal-summary\">"
            + (inst ? ("<span class=\"signal-df-journal-inst\">" + esc(inst) + "</span> ") : "")
            + esc(g.label)
            + (g.why ? (" <span class=\"signal-df-journal-group-why\">— " + esc(g.why) + "</span>") : "")
            + "<span class=\"signal-df-journal-group-count\">" + g.count + "</span>"
            + "</span>"
            + "<span class=\"signal-df-journal-time\">" + esc(g.range || "") + "</span>"
            + "</button>"
            + "<div class=\"signal-df-journal-details\"" + (open ? "" : " hidden") + ">"
            + "<ul class=\"signal-df-journal-members\">"
            + g.entries.map(memberHtml).join("")
            + "</ul></div></li>";
        }
        var e = g.entry;
        var fr = e.frame || {};
        var b = entryBadge(e);
        var eid = String(e.id || fr.frameId || "");
        var eOpen = !!(eid && openIds[eid]);
        var barIso2 = e.barTime || e.recordedAt || fr.barTime || "";
        var t = timeFn(barIso2);
        var inst2 = e.instrument || fr.instrument || "";
        var summary = humanize(e.summary || fr.summary || "—");
        return "<li class=\"signal-df-journal-item\" data-df-id=\"" + esc(eid) + "\""
          + " data-frame-id=\"" + esc(eid) + "\""
          + " data-bar-time=\"" + esc(barIso2) + "\""
          + " data-instrument=\"" + esc(inst2) + "\">"
          + "<button type=\"button\" class=\"signal-df-journal-toggle\" data-df-toggle"
          + " aria-expanded=\"" + (eOpen ? "true" : "false") + "\">"
          + "<span class=\"signal-df-journal-status\" data-status=\"" + esc(b.status) + "\">"
          + esc(b.label) + "</span>"
          + "<span class=\"signal-df-journal-summary\">"
          + (inst2 ? ("<span class=\"signal-df-journal-inst\">" + esc(inst2) + "</span> ") : "")
          + esc(summary)
          + "</span>"
          + "<span class=\"signal-df-journal-time\">" + esc(t) + "</span>"
          + "</button>"
          + "<div class=\"signal-df-journal-details decision-frame-details\""
          + (eOpen ? "" : " hidden") + ">"
          + "<ul class=\"decision-frame-details-list\">" + detailsHtml(fr) + "</ul>"
          + "</div></li>";
      }).join("");
    }

    return {
      paint: paint,
      compressEntries: compressEntries,
      displayKind: displayKind,
      clearFactFocus: function () {
        clearFactActive(panel);
        if (onFactFocus) onFactFocus(null, { origin: "clear", el: null, frame: null });
      }
    };
  }

  global.TrinityDecisionFrame = {
    create: create,
    mountJournal: mountJournal,
    compressEntries: compressEntries,
    sourceRu: sourceRu,
    statusRu: statusRu,
    effectiveStatus: effectiveStatus,
    triggerRu: triggerRu,
    detailsHtml: detailsHtml,
    detailFromEl: detailFromEl,
    clearFactActive: clearFactActive,
    whyChips: whyChips,
    robotDetailText: robotDetailText,
    SOURCE_RU: SOURCE_RU
  };
})(typeof window !== "undefined" ? window : globalThis);
