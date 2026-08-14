(function () {
  const POLL_MS = 12000;
  let chart;
  let series;
  let pollTimer;
  let lastFamily = "";
  let lastGood = null;
  let everReady = false;

  function $(id) { return document.getElementById(id); }

  function fmt(v, d) {
    if (v == null || !isFinite(Number(v))) return "—";
    return Number(v).toFixed(d == null ? 2 : d);
  }

  function loadFamilyFromUrl() {
    try {
      const u = new URL(location.href);
      return (u.searchParams.get("family") || "").toUpperCase();
    } catch (_) { return ""; }
  }

  function loadStructureFromUrl() {
    try {
      const u = new URL(location.href);
      return (u.searchParams.get("structure") || "").toUpperCase();
    } catch (_) { return ""; }
  }

  function hasCurve(data) {
    return !!(data && data.selected && data.selected.pair);
  }

  async function refresh() {
    const fam = ($("arb-family") && $("arb-family").value) || loadFamilyFromUrl();
    const st = window.__arbStructure || loadStructureFromUrl();
    let qs = fam ? ("?family=" + encodeURIComponent(fam)) : "";
    if (st) qs += (qs ? "&" : "?") + "structure=" + encodeURIComponent(st);
    const res = await fetch("/api/calendar-arb/desk" + qs, { headers: { Accept: "application/json" } });
    if (!res.ok) throw new Error("HTTP " + res.status);
    let data = await res.json();
    // First cold poll often returns SKIP_NO_CURVE while T-Invest gRPC warms — keep last good / loading.
    if (!hasCurve(data) && lastGood) {
      data = Object.assign({}, lastGood, {
        stale: true,
        warming: false,
        warning: data.warning || lastGood.warning
      });
    }
    if (hasCurve(data)) {
      lastGood = data;
      everReady = true;
    }
    render(data);
  }

  function render(data) {
    const warming = !!data.warming && !everReady;
    const desk = $("calendar-arb-desk");
    if (desk) desk.classList.toggle("is-warming", warming);

    const sel = data.selected || {};
    const settings = data.settings || {};
    $("arb-source").textContent = data.dataSource || "T_INVEST";
    $("arb-delivery").textContent = settings.delivery || "—";
    $("arb-pair").textContent = sel.pair || (warming ? "…" : "—");
    if ($("arb-structure")) $("arb-structure").textContent = sel.structure || (warming ? "…" : "—");
    if ($("arb-hedge")) $("arb-hedge").textContent = sel.hedge || (warming ? "…" : "—");
    if ($("arb-go")) {
      const go = sel.goRub;
      const factor = sel.goFactor;
      $("arb-go").textContent = go == null || !isFinite(Number(go)) || Number(go) <= 0
        ? (warming ? "…" : "—")
        : (Math.round(Number(go)) + " ₽"
          + (factor ? (" ×" + Number(factor).toFixed(2)) : ""));
    }
    if ($("arb-book")) {
      const b = sel.book || {};
      $("arb-book").textContent = b.ok
        ? ("long " + fmt(b.longSpread, 3) + " / short " + fmt(b.shortSpread, 3)
          + " · " + (b.minTopLots == null ? "—" : b.minTopLots) + " лот")
        : (warming ? "…" : "нет DOM");
    }
    if ($("arb-book-detail")) {
      const b = sel.book || {};
      $("arb-book-detail").textContent = b.ok
        ? ("исполняемый LONG (buy far/sell near) " + fmt(b.longSpread, 3)
          + " · SHORT " + fmt(b.shortSpread, 3)
          + " · крест " + fmt(b.roundTripPoints, 3)
          + " · топ " + (b.minTopLots == null ? "—" : b.minTopLots) + " лот")
        : (warming
          ? "ждём кривую и DOM у T-Invest…"
          : "два стакана T-Invest ещё не пришли — paper не ставит вслепую");
    }
    if ($("arb-eia") || $("arb-eia-chip")) {
      const line = sel.eiaLine || "EIA: нет цифры FRED";
      if ($("arb-eia")) $("arb-eia").textContent = line;
      if ($("arb-eia-chip")) {
        $("arb-eia-chip").textContent = sel.eiaOk
          ? ("surprise " + (sel.eiaSurpriseKbbl == null ? "—" : Math.round(sel.eiaSurpriseKbbl))
            + (sel.eiaStreetConsensus ? " street" : ""))
          : "нет цифры";
      }
    }
    if ($("arb-cot")) $("arb-cot").textContent = sel.cotLine || "CFTC: —";
    if ($("arb-crack")) $("arb-crack").textContent = sel.crackLine || "Crack: —";
    if ($("arb-oos")) {
      const o = sel.oos || {};
      $("arb-oos").textContent = o.oosN == null
        ? (warming ? "…" : "—")
        : ("n=" + o.oosN + " · " + (o.oosPnl == null ? "—" : Math.round(o.oosPnl) + " ₽"));
    }
    if ($("arb-chart-label")) {
      $("arb-chart-label").textContent = sel.structure === "FLY"
        ? "Fly near − 2·mid + far (H1 брокера)"
        : "Спред far − near (H1 брокера)";
    }
    window.__arbStructure = sel.structure || window.__arbStructure || "";
    $("arb-spread").textContent = warming && sel.spread == null ? "…" : fmt(sel.spread, 3);
    $("arb-z").textContent = warming && sel.z == null ? "…" : fmt(sel.z, 2);
    $("arb-action").textContent = warming && !sel.action
      ? "LOADING"
      : (sel.action || "—");
    $("arb-reason").textContent = warming
      ? "Загрузка near/next у T-Invest (первый запрос часто пустой, пока прогреется gRPC)…"
      : (data.stale
        ? ((sel.reason || "—") + " · снимок чуть устарел")
        : (sel.reason || "—"));
    $("arb-legs").textContent = sel.pair
      ? (sel.near + " last=" + fmt(sel.nearLast, 3)
        + " · " + sel.next + " last=" + fmt(sel.nextLast, 3)
        + (sel.wing ? (" · " + sel.wing + " last=" + fmt(sel.wingLast, 3)) : "")
        + " · DTE near=" + (sel.nearDte == null ? "—" : sel.nearDte)
        + (sel.hedge ? (" · hedge " + sel.hedge) : "")
        + (sel.ltd ? (" · LTD " + sel.ltd) : "")
        + (sel.expiration ? (" · EXP " + sel.expiration) : ""))
      : (warming ? "ждём кривую у брокера…" : "нет кривой у брокера");
    const fp = data.fairPaper || {};
    $("arb-fair").textContent = fp.open
      ? ("OPEN " + fp.open.side + " " + fp.open.pair)
      : ((fp.lastAction || "idle") + (fp.lastReason ? " · " + fp.lastReason : ""));
    if (fp.liveBroker) {
      $("arb-fair").textContent += " · broker " + fp.liveBroker
        + (fp.liveArmed ? " · LIVE ARMED" : "");
    }
    const warn = $("arb-warning");
    if (data.warning) {
      warn.hidden = false;
      warn.textContent = data.warning;
    } else {
      warn.hidden = true;
    }
    fillFamilySelect(data.families || [], sel.family || settings.family);
    if (warming && !(data.cards && data.cards.some(function (c) { return c.pair; }))) {
      fillCardsSkeleton(data.families || []);
    } else {
      fillCards(data.cards || []);
    }
    fillPaper(data.paper || {});
    drawChart(sel.series || []);
    $("arb-desk-meta").textContent =
      "Котировки " + (data.dataSource || "T-Invest") +
      (data.tokenPresent === false ? " · нет токена" : " · H1 брокера") +
      (sel.bars ? (" · баров " + sel.bars) : "") +
      (warming ? " · загрузка…" : "") +
      (data.stale ? " · stale" : "");
  }

  function fillCardsSkeleton(families) {
    const host = $("arb-cards");
    if (!host) return;
    const list = families.length ? families : [{ code: "BR" }, { code: "SI" }, { code: "RI" }, { code: "GD" }, { code: "NG" }];
    host.innerHTML = list.map(function (f) {
      return '<button type="button" class="arb-card is-skeleton" disabled>' +
        "<strong>" + (f.code || f.name || "?") + "</strong> …" +
        "<span>загрузка кривой…</span></button>";
    }).join("");
  }

  function fillFamilySelect(families, current) {
    const el = $("arb-family");
    if (!el) return;
    const cur = current || el.value;
    if (el.options.length !== families.length) {
      el.innerHTML = "";
      families.forEach(function (f) {
        const o = document.createElement("option");
        o.value = f.code;
        o.textContent = f.name || f.code;
        el.appendChild(o);
      });
    }
    if (cur) el.value = cur;
    lastFamily = el.value;
  }

  function fillCards(cards) {
    const host = $("arb-cards");
    if (!host) return;
    host.innerHTML = cards.map(function (c) {
      const z = c.z == null ? "—" : Number(c.z).toFixed(2);
      const st = c.structure || "CALENDAR";
      return '<button type="button" class="arb-card" data-family="' + (c.family || "") +
        '" data-structure="' + st + '">' +
        "<strong>" + (c.family || "") + " " + (st === "FLY" ? "FLY" : "") + "</strong> " + (c.pair || "—") +
        "<span>z=" + z + " · " + (c.action || "") + "</span></button>";
    }).join("");
    host.querySelectorAll(".arb-card").forEach(function (btn) {
      btn.addEventListener("click", function () {
        const fam = btn.getAttribute("data-family");
        const st = btn.getAttribute("data-structure") || "";
        if ($("arb-family")) $("arb-family").value = fam;
        window.__arbStructure = st;
        refresh().catch(function (e) { console.warn(e); });
      });
    });
  }

  function fillPaper(paper) {
    const st = paper.statement || {};
    $("arb-paper-today").textContent = st.todayPnlRub == null ? "—" : (Math.round(st.todayPnlRub) + " ₽");
    const rows = paper.todayTrades || [];
    const panel = $("arb-paper-panel");
    const body = $("arb-paper-body");
    if (!rows.length) {
      panel.hidden = true;
      return;
    }
    panel.hidden = false;
    body.innerHTML = rows.map(function (t) {
      const pnl = t.pnlRub == null ? 0 : t.pnlRub;
      const cls = pnl > 0 ? "is-buy" : (pnl < 0 ? "is-sell" : "");
      return "<tr><td>" + (t.closedAt || "") + "</td><td>" + (t.pair || "") + "</td><td>" +
        (t.side || "") + "</td><td>" + fmt(t.entryPrice, 3) + "</td><td>" + fmt(t.exitPrice, 3) +
        "</td><td class=\"" + cls + "\">" + (pnl > 0 ? "+" : "") + Math.round(pnl) + " ₽</td></tr>";
    }).join("");
  }

  function drawChart(points) {
    const el = $("arb-chart");
    if (!el || typeof LightweightCharts === "undefined") return;
    if (!chart) {
      chart = LightweightCharts.createChart(el, {
        layout: { backgroundColor: "#0f1418", textColor: "#c9d1d9" },
        grid: { vertLines: { color: "#1c242c" }, horzLines: { color: "#1c242c" } },
        rightPriceScale: { borderColor: "#2a343c" },
        timeScale: { borderColor: "#2a343c", timeVisible: true }
      });
      series = chart.addLineSeries({ color: "#c4a35a", lineWidth: 2 });
    }
    const data = (points || []).map(function (p) {
      const ts = Date.parse(p.t);
      return { time: Math.floor(ts / 1000), value: p.spread };
    }).filter(function (p) { return isFinite(p.time) && isFinite(p.value); });
    if (!data.length) {
      return;
    }
    series.setData(data);
    chart.timeScale().fitContent();
  }

  function bind() {
    if ($("arb-desk-refresh")) {
      $("arb-desk-refresh").addEventListener("click", function () {
        refresh().catch(function (e) { $("arb-desk-meta").textContent = String(e); });
      });
    }
    if ($("arb-family")) {
      $("arb-family").addEventListener("change", function () {
        window.__arbStructure = "";
        refresh().catch(function (e) { console.warn(e); });
      });
    }
    refresh().catch(function (e) {
      $("arb-desk-meta").textContent = "Desk: " + (e.message || e);
    });
    setTimeout(function () {
      if (!everReady) refresh().catch(function () {});
    }, 2500);
    pollTimer = setInterval(function () {
      refresh().catch(function () {});
    }, POLL_MS);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", bind);
  } else {
    bind();
  }
})();
