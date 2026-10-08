/**
 * Decision Frame UI (DF-2): clickable summary + live details plaque.
 * Mount on any desk that serves `decisionFrame` JSON. Extension point for
 * investments / calendar-arb — same create({ root }).
 *
 * States: idle | updating | stale
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

  var STATUS_RU = {
    ENTER: "входит",
    WAIT: "ждёт",
    SKIP: "пропускает"
  };

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

  function statusRu(status) {
    return STATUS_RU[status] || "—";
  }

  function triggerRu(trigger) {
    if (!trigger) return "";
    return TRIGGER_RU[trigger] || String(trigger).toLowerCase();
  }

  function $(root, id) {
    if (!root) return null;
    if (root.id === id) return root;
    return root.querySelector("#" + id) || document.getElementById(id);
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
    var state = "idle";
    var lastFrameId = "";
    var lastSummary = "";
    var lastPaintAt = 0;
    var flashTimer = null;

    var btn = $(root, "decision-frame-summary");
    var textEl = $(root, "decision-frame-text");
    var statusEl = $(root, "decision-frame-status");
    var hint = $(root, "decision-frame-hint");
    var details = $(root, "decision-frame-details");
    var listEl = $(root, "decision-frame-details-list");
    var metaEl = $(root, "decision-frame-meta");
    var stateEl = $(root, "decision-frame-state");

    function setOpen(next) {
      open = !!next;
      if (btn) btn.setAttribute("aria-expanded", open ? "true" : "false");
      if (details) details.hidden = !open;
      if (hint) hint.textContent = open ? "свернуть" : "подробнее";
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
    }

    function detailsHtml(frame) {
      var rows = Array.isArray(frame.details) ? frame.details : [];
      var html = "";
      rows.forEach(function (d) {
        if (!d || !d.text) return;
        var src = d.source ? String(d.source) : "";
        html += "<li data-source=\"" + esc(src) + "\">"
          + "<span class=\"decision-frame-source\">" + esc(sourceRu(src)) + "</span>"
          + esc(d.text)
          + "</li>";
      });
      return html || "<li>Фактов в кадре пока нет.</li>";
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
      metaEl.hidden = bits.length === 0;
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
      var robot = frame.robot || {};
      if (statusEl) {
        statusEl.textContent = statusRu(robot.status);
        statusEl.dataset.status = robot.status || "";
      }
      if (textEl) textEl.textContent = frame.summary;
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

    var staleTimer = setInterval(tickStale, 15000);

    return {
      markUpdating: markUpdating,
      markStale: markStale,
      render: render,
      isOpen: function () { return open; },
      state: function () { return state; },
      destroy: function () {
        if (staleTimer) clearInterval(staleTimer);
        if (flashTimer) clearTimeout(flashTimer);
      }
    };
  }

  global.TrinityDecisionFrame = {
    create: create,
    sourceRu: sourceRu,
    statusRu: statusRu,
    triggerRu: triggerRu,
    SOURCE_RU: SOURCE_RU
  };
})(typeof window !== "undefined" ? window : globalThis);
