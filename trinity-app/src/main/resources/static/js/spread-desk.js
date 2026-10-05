(function () {
  const POLL_MS = 20000;
  const DESK_TIMEOUT_MS = (window.TrinityFastBoot && TrinityFastBoot.DESK_MS) || 25000;
  let pollTimer;

  function $(id) { return document.getElementById(id); }

  function fmt(v, d) {
    if (v == null || !isFinite(Number(v))) return "—";
    return Number(v).toFixed(d == null ? 2 : d);
  }

  function bookRu(book) {
    if (book === "HALF_SPREAD") return "не спред";
    if (book === "FULL_PAIR") return "полная пара";
    if (book === "TOO_SMALL") return "мало капитала";
    return "—";
  }

  function fetchJson(url) {
    if (window.TrinityFastBoot && typeof TrinityFastBoot.fetchJson === "function") {
      return TrinityFastBoot.fetchJson(url, { timeoutMs: DESK_TIMEOUT_MS });
    }
    return fetch(url, { headers: { Accept: "application/json" } }).then(function (res) {
      if (!res.ok) throw new Error("HTTP " + res.status);
      return res.json();
    });
  }

  async function lastPx(secid) {
    try {
      const j = await fetchJson("/api/marketdata/iss-last?secid=" + encodeURIComponent(secid));
      return j && j.ok ? Number(j.px) : NaN;
    } catch (_) {
      return NaN;
    }
  }

  function premiumPct(ao, ap) {
    if (!(ao > 0) || !(ap > 0)) return NaN;
    return 100.0 * (ao - ap) / ao;
  }

  function bindGuide() {
    let lastFocus = null;
    function openGuide() {
      const gate = $("spread-guide-modal");
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
      const gate = $("spread-guide-modal");
      if (!gate || gate.hidden) return;
      gate.classList.remove("is-open");
      gate.setAttribute("aria-hidden", "true");
      window.setTimeout(function () {
        gate.hidden = true;
        if (lastFocus && typeof lastFocus.focus === "function") lastFocus.focus();
        lastFocus = null;
      }, 220);
    }
    const openBtn = $("spread-guide-open");
    if (openBtn) openBtn.addEventListener("click", openGuide);
    const gate = $("spread-guide-modal");
    if (gate) {
      gate.querySelectorAll("[data-spread-guide-close]").forEach(function (el) {
        el.addEventListener("click", closeGuide);
      });
    }
    document.addEventListener("keydown", function (ev) {
      if (ev.key !== "Escape") return;
      const g = $("spread-guide-modal");
      if (g && !g.hidden) {
        closeGuide();
        ev.preventDefault();
      }
    });
  }

  async function paint() {
    const data = await fetchJson("/api/dual-class/desk");
    const auto = !!data.autoExecution;
    if ($("spread-floor")) {
      $("spread-floor").textContent = data.entryZ != null ? ("|z|≥" + Number(data.entryZ).toFixed(1)) : "—";
    }
    if ($("spread-book")) $("spread-book").textContent = bookRu(data.book);
    if ($("spread-reason")) $("spread-reason").textContent = data.message || "—";
    if ($("spread-delivery") && !$("spread-delivery-title")) {
      $("spread-delivery").textContent = auto ? "Авто · без журнала" : "Наблюдение";
    }
    const rows = Array.isArray(data.universe) ? data.universe : [];
    const body = $("spread-body");
    if (!body) return;
    if (!rows.length) {
      body.innerHTML = "<tr><td colspan=\"6\">Whitelist пуст</td></tr>";
      return;
    }
    const painted = [];
    for (let i = 0; i < rows.length; i++) {
      const r = rows[i];
      const ao = await lastPx(r.ordinary);
      const ap = await lastPx(r.preferred);
      const z = r.z;
      const res = r.residualPct;
      const rolling = r.centerMode === "rolling-60d";
      let status = r.skipReason || "выкл";
      if (r.tradeable) {
        if (!isFinite(prem)) status = "нет last";
        else if (isFinite(Number(z))) status = "z " + fmt(z, 1);
        else status = "мало истории";
      }
      const mark = r.key === data.focusKey ? " · фокус" : "";
      painted.push("<tr>"
        + "<td>" + (r.issuer || "—") + mark + "</td>"
        + "<td>" + (r.key || "—") + "</td>"
        + "<td>" + fmt(prem, 2) + "</td>"
        + "<td>" + fmt(r.medianPct, 1) + "</td>"
        + "<td>" + fmt(res, 2) + "</td>"
        + "<td>" + status + "</td>"
        + "</tr>");
    }
    body.innerHTML = painted.join("");
  }

  async function tick() {
    try {
      await paint();
    } catch (err) {
      if ($("spread-reason")) {
        $("spread-reason").textContent = "Не удалось загрузить доску: " + (err.message || err);
      }
    }
  }

  function bind() {
    if (!$("spread-desk")) return;
    bindGuide();
    const refresh = $("spread-desk-refresh");
    if (refresh) refresh.addEventListener("click", tick);
    tick();
    pollTimer = setInterval(tick, POLL_MS);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", bind);
  } else {
    bind();
  }
})();
