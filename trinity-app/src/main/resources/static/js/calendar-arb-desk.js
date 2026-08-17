(function () {
  const POLL_MS = 12000;
  let chart;
  let series;
  let legsChart;
  let nearSeries;
  let nextSeries;
  let pollTimer;
  let lastFamily = "";
  let lastGood = null;
  let everReady = false;
  let lastSeriesKey = "";
  let arbScaleLocked = false;
  let syncingRange = false;
  let resizeBound = false;

  function $(id) { return document.getElementById(id); }

  function fmt(v, d) {
    if (v == null || !isFinite(Number(v))) return "—";
    return Number(v).toFixed(d == null ? 2 : d);
  }

  function fillQualityLine(q, warming) {
    if (!q || !q.bookOk) {
      return warming ? "Качество fill: ждём стакан…" : "Качество fill: нет двух стаканов — mid vs executable недоступно.";
    }
    const mid = "mid " + fmt(q.midSpread, 3);
    const vs = "LONG +" + fmt(q.longVsMid, 3) + " / SHORT +" + fmt(q.shortVsMid, 3) + " к mid";
    const rt = "крест " + (q.roundTripRub == null ? "—" : Math.round(Number(q.roundTripRub)) + " ₽");
    const edge = "ход к среднему " + (q.edgeToMeanRub == null ? "—" : Math.round(Number(q.edgeToMeanRub)) + " ₽");
    const cover = q.edgeCoversCross ? "край кроет крест" : "крест ≥ хода к среднему";
    return "Качество fill: " + mid + " · " + vs + " · " + rt + " vs " + edge + " · " + cover;
  }

  function sessionSkipsLine(skips, warming) {
    if (!skips || !skips.length) {
      return warming ? "Скипы сессии: …" : "Скипы сессии: нет SKIP_* на карточках.";
    }
    return "Скипы сессии: " + skips.map(function (row) {
      const fam = row.family || "?";
      const st = row.structure ? ("/" + row.structure) : "";
      const act = row.action || "SKIP";
      const why = row.reason ? (" — " + row.reason) : "";
      return fam + st + " " + act + why;
    }).join(" · ");
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
    const wantFam = (fam || "").toUpperCase();
    const wantSt = (st || "").toUpperCase();
    const goodSel = (lastGood && lastGood.selected) || {};
    const sameInstrument = !!(lastGood && (goodSel.family || "").toUpperCase() === wantFam
      && (!wantSt || (goodSel.structure || "").toUpperCase() === wantSt));
    // First cold poll often returns SKIP_NO_CURVE while T-Invest gRPC warms — keep last good / loading
    // only for the same family+structure, otherwise charts/books stay on the previous instrument.
    if (!hasCurve(data) && sameInstrument) {
      data = Object.assign({}, lastGood, {
        stale: true,
        warming: false,
        warning: data.warning || lastGood.warning
      });
    }
    if (hasCurve(data)) {
      lastGood = data;
      everReady = true;
    } else if (!sameInstrument) {
      lastGood = null;
      lastSeriesKey = "";
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
      if (sel.goSource && $("arb-go").title !== undefined) {
        $("arb-go").title = sel.goSource;
      }
    }
    if ($("arb-dom-arch")) {
      const d = sel.domArchive || {};
      $("arb-dom-arch").textContent = warming ? "…"
        : (d.ok
          ? ("сегодня " + (d.nearLinesToday || 0) + "/" + (d.nextLinesToday || 0))
          : ("копится " + (d.nearLinesToday || 0) + "/" + (d.nextLinesToday || 0)));
    }
    if ($("arb-book")) {
      const b = sel.book || {};
      $("arb-book").textContent = b.ok
        ? ("long " + fmt(b.longSpread, 3) + " / short " + fmt(b.shortSpread, 3)
          + " · " + (b.minTopLots == null ? "—" : b.minTopLots) + " лот")
        : (warming ? "…" : "нет DOM");
    }
    renderBooks(sel.book || {}, sel, warming);
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
    if ($("arb-journal")) {
      const j = sel.journalOos || {};
      $("arb-journal").textContent = warming && j.n == null
        ? "…"
        : ((j.family ? j.family + " · " : "")
          + "n=" + (j.n == null ? "0" : j.n)
          + " · " + (j.pnl == null ? "—" : Math.round(Number(j.pnl)) + " ₽"));
    }
    if ($("arb-oos")) {
      const o = sel.oos || {};
      $("arb-oos").textContent = o.oosN == null
        ? (warming ? "…" : "—")
        : ("n=" + o.oosN + " · " + (o.oosPnl == null ? "—" : Math.round(o.oosPnl) + " ₽")
          + (o.histDomBars ? (" · DOM " + o.histDomBars) : ""));
    }
    if ($("arb-oos-note")) {
      $("arb-oos-note").textContent = (sel.oos && sel.oos.note)
        ? sel.oos.note
        : "Journal sandbox со стаканом — главный счёт. Replay — сноска. Research, не обещание доходности.";
    }
    if ($("arb-fill-quality")) {
      $("arb-fill-quality").textContent = fillQualityLine(sel.fillQuality, warming);
    }
    if ($("arb-near-locked")) {
      const nl = !!sel.nearLocked;
      $("arb-near-locked").hidden = !nl;
      if (nl) {
        $("arb-near-locked").textContent = "Near-locked: скрещённый стакан (edge "
          + fmt(sel.lockedEdgePoints, 4) + ") — research flag, не авто-ордер.";
      }
    }
    if ($("arb-session-skips")) {
      $("arb-session-skips").textContent = sessionSkipsLine(data.sessionSkips, warming);
    }
    if ($("arb-chart-label")) {
      $("arb-chart-label").textContent = sel.structure === "FLY"
        ? "Fly near − 2·mid + far (H1 брокера)"
        : "Спред far − near (H1 брокера)";
    }
    if ($("arb-legs-chart-label")) {
      const nearName = sel.near || "near";
      const nextName = sel.next || "next";
      $("arb-legs-chart-label").textContent = sel.structure === "FLY"
        ? ("Ноги H1 · " + nearName + " / " + nextName + " (mid)")
        : ("Ноги H1 · " + nearName + " / " + nextName);
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
      fillCards(data.cards || [], sel);
    }
    fillPaper(data.paper || {});
    drawCharts(sel.series || [], sel);
    $("arb-desk-meta").textContent =
      "Котировки " + (data.dataSource || "T-Invest") +
      (data.tokenPresent === false ? " · нет токена" : " · H1 брокера") +
      (sel.bars ? (" · баров " + sel.bars) : "") +
      (warming ? " · загрузка…" : "") +
      (data.stale ? " · stale" : "");
  }

  function fmtPx(p, pointSize) {
    const n = Number(p);
    if (!isFinite(n)) return "—";
    const step = Number(pointSize);
    if (isFinite(step) && step > 0) {
      if (step >= 1) return n.toFixed(0);
      if (step >= 0.1) return n.toFixed(1);
      if (step >= 0.01) return n.toFixed(2);
      return n.toFixed(3);
    }
    if (Math.abs(n) >= 1000) return n.toFixed(1);
    if (Math.abs(n) >= 100) return n.toFixed(2);
    return n.toFixed(3);
  }

  function samePx(a, b) {
    return isFinite(Number(a)) && isFinite(Number(b)) && Math.abs(Number(a) - Number(b)) < 1e-8;
  }

  function renderBooks(book, sel, warming) {
    const detail = $("arb-book-detail");
    if (detail) {
      if (book.ok) {
        const rtRub = book.roundTripRub;
        detail.textContent = "исполняемый LONG " + fmt(book.longSpread, 3)
          + " · SHORT " + fmt(book.shortSpread, 3)
          + " · крест " + fmt(book.roundTripPoints, 3)
          + (rtRub != null && isFinite(Number(rtRub)) ? (" (~" + Math.round(Number(rtRub)) + " ₽)") : "")
          + " · топ " + (book.minTopLots == null ? "—" : book.minTopLots) + " лот";
      } else {
        detail.textContent = warming
          ? "ждём кривую и DOM у T-Invest…"
          : "два стакана T-Invest ещё не пришли — paper не ставит вслепую";
      }
    }
    const isFly = (sel.structure || book.structure) === "FLY";
    const nextRole = isFly ? "Mid" : "Next";
    const near = book.near || {};
    const next = book.next || {};
    if ($("arb-book-near-name")) {
      $("arb-book-near-name").textContent = near.ticker || sel.near || "Near";
    }
    if ($("arb-book-next-name")) {
      $("arb-book-next-name").textContent = (next.ticker || sel.next || nextRole)
        + (isFly ? " · mid" : "");
    }
    const emptyNear = warming ? "ждём DOM…" : "нет стакана near";
    const emptyNext = warming ? "ждём DOM…" : "нет стакана next";
    renderLadder("arb-book-near-body", "arb-book-near-meta", near, {
      longOnBid: !isFly,
      shortOnAsk: !isFly,
      empty: emptyNear,
      pointSize: book.pointSize
    });
    renderLadder("arb-book-next-body", "arb-book-next-meta", next, {
      longOnAsk: !isFly,
      shortOnBid: !isFly,
      empty: emptyNext,
      pointSize: book.pointSize
    });
  }

  function renderLadder(bodyId, metaId, ladder, opt) {
    const body = $(bodyId);
    const meta = $(metaId);
    if (!body) return;
    const bids = (ladder && ladder.bids) || [];
    const asks = (ladder && ladder.asks) || [];
    if (!ladder || !ladder.ok || (!bids.length && !asks.length)) {
      body.innerHTML = "<div class=\"signal-dom-empty\">" + (opt.empty || "нет DOM") + "</div>";
      if (meta) meta.textContent = "—";
      return;
    }
    const bestBid = Number(ladder.bestBid);
    const bestAsk = Number(ladder.bestAsk);
    let maxQ = 1;
    bids.forEach(function (b) { maxQ = Math.max(maxQ, Number(b.q) || 0); });
    asks.forEach(function (a) { maxQ = Math.max(maxQ, Number(a.q) || 0); });
    const barW = function (q) {
      return Math.max(6, Math.round(100 * (Number(q) || 0) / maxQ));
    };
    const px = function (p) { return fmtPx(p, opt.pointSize); };
    let html = "";
    for (let i = asks.length - 1; i >= 0; i--) {
      const a = asks[i];
      const p = Number(a.p);
      const q = Number(a.q) || 0;
      const isBest = samePx(p, bestAsk);
      const isLong = isBest && opt.longOnAsk;
      const isShort = isBest && opt.shortOnAsk;
      html += ladderRow("ask", p, q, barW(q), px(p), isBest, isLong, isShort);
    }
    if (isFinite(bestBid) && isFinite(bestAsk)) {
      html += "<div class=\"arb-row arb-spread\">"
        + "<span>" + px(bestAsk - bestBid) + "</span></div>";
    }
    for (let i = 0; i < bids.length; i++) {
      const b = bids[i];
      const p = Number(b.p);
      const q = Number(b.q) || 0;
      const isBest = samePx(p, bestBid);
      const isLong = isBest && opt.longOnBid;
      const isShort = isBest && opt.shortOnBid;
      html += ladderRow("bid", p, q, barW(q), px(p), isBest, isLong, isShort);
    }
    body.innerHTML = html;
    if (meta) {
      const age = ladder.asOf ? new Date(ladder.asOf).toLocaleTimeString("ru-RU") : "";
      meta.textContent = (isFinite(bestBid) && isFinite(bestAsk)
        ? ("top " + (ladder.topBidLots || "—") + "/" + (ladder.topAskLots || "—"))
        : "—")
        + (age ? (" · " + age) : "");
    }
  }

  function ladderRow(side, p, q, w, pxLabel, isBest, isLong, isShort) {
    const cls = ["arb-row", "arb-" + side];
    if (isBest) cls.push("is-best");
    if (isLong) cls.push("is-cross-long");
    if (isShort) cls.push("is-cross-short");
    const tag = isLong
      ? "<b class=\"arb-cross-tag is-long\" title=\"нога LONG\">L</b>"
      : (isShort ? "<b class=\"arb-cross-tag is-short\" title=\"нога SHORT\">S</b>" : "");
    const bidCell = side === "bid"
      ? ("<span class=\"arb-q arb-q-bid\"><i style=\"width:" + w + "%\"></i><em>" + q + "</em></span>")
      : "<span class=\"arb-q\"></span>";
    const askCell = side === "ask"
      ? ("<span class=\"arb-q arb-q-ask\"><i style=\"width:" + w + "%\"></i><em>" + q + "</em></span>")
      : "<span class=\"arb-q\"></span>";
    const title = isLong
      ? (side === "bid" ? "SELL · нога LONG" : "BUY · нога LONG")
      : (isShort ? (side === "ask" ? "BUY · нога SHORT" : "SELL · нога SHORT") : "");
    return "<div class=\"" + cls.join(" ") + "\"" + (title ? (" title=\"" + title + "\"") : "") + ">"
      + bidCell
      + "<span class=\"arb-px\">" + tag + pxLabel + "</span>"
      + askCell
      + "</div>";
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

  function fillCards(cards, sel) {
    const host = $("arb-cards");
    if (!host) return;
    const fam = (sel && sel.family) || "";
    const st = (sel && sel.structure) || "";
    host.innerHTML = cards.map(function (c) {
      const z = c.z == null ? "—" : Number(c.z).toFixed(2);
      const cst = c.structure || "CALENDAR";
      const on = (c.family || "") === fam && (!st || cst === st);
      return '<button type="button" class="arb-card' + (on ? " is-active" : "") +
        '" data-family="' + (c.family || "") +
        '" data-structure="' + cst + '">' +
        "<strong>" + (c.family || "") + " " + (cst === "FLY" ? "FLY" : "") + "</strong> " + (c.pair || "—") +
        "<span>z=" + z + " · " + (c.action || "") + "</span></button>";
    }).join("");
    host.querySelectorAll(".arb-card").forEach(function (btn) {
      btn.addEventListener("click", function () {
        const fam = btn.getAttribute("data-family");
        const st = btn.getAttribute("data-structure") || "";
        if ($("arb-family")) $("arb-family").value = fam;
        window.__arbStructure = st;
        lastGood = null;
        lastSeriesKey = "";
        arbScaleLocked = false;
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

  function whiteChartOpts(el, height) {
    return {
      width: el.clientWidth || el.offsetWidth || 600,
      height: height || el.clientHeight || 320,
      layout: { backgroundColor: "#ffffff", textColor: "#1a2228" },
      grid: {
        vertLines: { color: "#eef1f3" },
        horzLines: { color: "#eef1f3" }
      },
      crosshair: {
        vertLine: { color: "rgba(30,42,50,0.45)", labelBackgroundColor: "#1a2228", width: 1 },
        horzLine: { color: "rgba(30,42,50,0.45)", labelBackgroundColor: "#1a2228", width: 1 }
      },
      rightPriceScale: { borderColor: "#d5dde2" },
      timeScale: { borderColor: "#d5dde2", timeVisible: true, secondsVisible: false }
    };
  }

  function bindArbChartScale(el, chartInst, seriesInst) {
    if (!el || !chartInst || !seriesInst) return;
    if (el._trinityOverlayFollowBound) return;
    const kit = window.TrinityChartKit;
    if (!kit || typeof kit.bindScaleOverlayFollow !== "function") return;
    kit.bindScaleOverlayFollow(el, {
      chart: chartInst,
      series: seriesInst,
      freezePrice: true,
      onLayout: function () {
        arbScaleLocked = true;
      }
    });
    el.addEventListener("wheel", function () { arbScaleLocked = true; }, { passive: true });
  }

  function setLineDataKeep(chartInst, seriesInst, data) {
    const kit = window.TrinityChartKit;
    if (arbScaleLocked && kit && typeof kit.setSeriesDataKeepView === "function") {
      kit.setSeriesDataKeepView(chartInst, seriesInst, data);
    } else {
      seriesInst.setData(data);
    }
  }

    if (!chart || !legsChart) return;
    function copy(from, to) {
      from.timeScale().subscribeVisibleTimeRangeChange(function (range) {
        if (syncingRange || !range) return;
        syncingRange = true;
        try { to.timeScale().setVisibleRange(range); } catch (_) {}
        syncingRange = false;
      });
    }
    copy(chart, legsChart);
    copy(legsChart, chart);
  }

  function resizeCharts() {
    [[chart, $("arb-chart")], [legsChart, $("arb-legs-chart")]].forEach(function (pair) {
      const c = pair[0];
      const el = pair[1];
      if (!c || !el) return;
      c.resize(el.clientWidth || 600, el.clientHeight || 280);
    });
  }

  function ensureCharts() {
    if (typeof LightweightCharts === "undefined") return;
    const spreadEl = $("arb-chart");
    const legsEl = $("arb-legs-chart");
    if (spreadEl && !chart) {
      chart = LightweightCharts.createChart(spreadEl, whiteChartOpts(spreadEl, spreadEl.clientHeight || 360));
      series = chart.addLineSeries({ color: "#0b7a66", lineWidth: 2 });
      bindArbChartScale(spreadEl, chart, series);
    }
    if (legsEl && !legsChart) {
      legsChart = LightweightCharts.createChart(legsEl, whiteChartOpts(legsEl, legsEl.clientHeight || 280));
      nearSeries = legsChart.addLineSeries({ color: "#15803d", lineWidth: 2, title: "near" });
      nextSeries = legsChart.addLineSeries({ color: "#b45309", lineWidth: 2, title: "next" });
      bindArbChartScale(legsEl, legsChart, nearSeries);
      bindChartSync();
    }
    if (!resizeBound) {
      resizeBound = true;
      window.addEventListener("resize", resizeCharts);
    }
  }

  function toLine(points, field) {
    return (points || []).map(function (p) {
      const ts = Date.parse(p.t);
      return { time: Math.floor(ts / 1000), value: Number(p[field]) };
    }).filter(function (p) { return isFinite(p.time) && isFinite(p.value); });
  }

  function drawCharts(points, sel) {
    ensureCharts();
    const key = (sel.family || "") + "|" + (sel.structure || "") + "|" + (sel.pair || "");
    const pairChanged = key !== lastSeriesKey;
    lastSeriesKey = key;
    const spread = toLine(points, "spread");
    const near = toLine(points, "near");
    const next = toLine(points, "far");
    if (series) setLineDataKeep(chart, series, spread);
    if (nearSeries) setLineDataKeep(legsChart, nearSeries, near);
    if (nextSeries) setLineDataKeep(legsChart, nextSeries, next);
    if (pairChanged) {
      arbScaleLocked = false;
      if (chart) chart.timeScale().fitContent();
      if (legsChart) legsChart.timeScale().fitContent();
    }
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
        lastGood = null;
        lastSeriesKey = "";
        arbScaleLocked = false;
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
