(function () {
  const POLL_MS = 12000;
  /** Align with TrinityFastBoot: abort hung desk, keep cache paint snappy. */
  const DESK_TIMEOUT_MS = (window.TrinityFastBoot && TrinityFastBoot.DESK_MS) || 25000;
  const DEFAULT_FAMS = [
    { code: "BR", name: "Нефть (BR)" },
    { code: "SI", name: "Si (USD/RUB)" },
    { code: "RI", name: "RTS (RI)" },
    { code: "GD", name: "GOLD (GD)" }
  ];
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
  let lastSpreadData = [];
  let lastNearData = [];
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
      return warming
        ? "Стакан ещё подгружается. Пока не видно, дорого ли собрать сделку по живым ценам."
        : "В стакане пока нет обеих ног. Робот не входит вслепую: без двух цен нельзя понять, сколько съест исполнение.";
    }
    const cost = q.roundTripRub == null ? null : Math.round(Number(q.roundTripRub));
    const edge = q.edgeToMeanRub == null ? null : Math.round(Number(q.edgeToMeanRub));
    let s = "Это не сигнал входить — только проверка стакана. ";
    if (cost != null && edge != null) {
      s += "Собрать сделку по текущим ценам (купить одну ногу, продать другую и потом закрыть) стоило бы около "
        + cost + " ₽. Если разница месяцев вернётся к своему среднему, это было бы около "
        + edge + " ₽. ";
      s += q.edgeCoversCross
        ? "Издержки меньше потенциального хода: стакан сделку не съел бы."
        : "Издержки больше потенциального хода: даже удачный возврат к среднему мог бы уйти в ноль на спреде стакана.";
    } else {
      s += "Средняя разница месяцев сейчас " + fmt(q.midSpread, 3) + ".";
    }
    return s;
  }

  function skipActionRu(act) {
    if (act === "SKIP_MACRO") return "пауза: рынок нефти неспокойный";
    if (act === "SKIP_REGIME") return "пауза: разница месяцев едет трендом";
    if (act === "SKIP_EVENT") return "пауза из‑за новости по запасам";
    if (act === "SKIP_ROLL") return "пауза: близко окончание контракта";
    if (act === "SKIP_COST") return "пауза: ход не окупит стакан";
    if (act === "SKIP_BOOK") return "пауза: стакан тонкий";
    if (act === "SKIP_THIN") return "пауза: мало сделок в ногах";
    if (act === "SKIP_GO") return "пауза: слишком большое гарантийное обеспечение";
    if (act === "SKIP_LATE") return "пауза: уже слишком далеко от среднего";
    if (act === "SKIP_NO_CURVE") return "ждём цены двух месяцев у брокера";
    if (act === "SKIP_SQUEEZE") return "пауза: не шортим ближний месяц у экспирации";
    if (act && String(act).indexOf("SKIP_") === 0) return "пауза";
    return act || "";
  }

  function escapeHtml(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function uniqueNames(rows) {
    const out = [];
    rows.forEach(function (row) {
      const n = row.name || row.family || "";
      if (n && out.indexOf(n) < 0) out.push(n);
    });
    return out;
  }

  function paintSkipExplain(el, skips, warming) {
    if (!el) return;
    if (warming && (!skips || !skips.length)) {
      el.innerHTML = "<p>Считаем рынки. Через несколько секунд здесь будет обычным языком, почему робот входит или ждёт.</p>";
      return;
    }
    if (!skips || !skips.length) {
      el.innerHTML = "<p><strong>Сделок нет, потому что ещё рано</strong></p>"
        + "<p>Робот здоров и ждёт. Ему нужна необычно дешёвая или дорогая разница между месяцами, которая начинает возвращаться к привычной. Сейчас такого нет — это нормально, не поломка.</p>";
      return;
    }
    const macro = skips.filter(function (s) { return s.action === "SKIP_MACRO"; });
    const regime = skips.filter(function (s) { return s.action === "SKIP_REGIME"; });
    const other = skips.filter(function (s) {
      return s.action !== "SKIP_MACRO" && s.action !== "SKIP_REGIME";
    });
    let html = "<p><strong>Почему робот включён, а сделок нет</strong></p>";
    html += "<p>Это не зависание. Стратегия зарабатывает только в спокойной ситуации: разница цен двух месяцев уехала от обычной и должна вернуться. Сейчас такой ситуации нет ни на одном рынке ниже — робот специально стоит.</p>";
    if (macro.length) {
      html += "<p><strong>" + escapeHtml(uniqueNames(macro).join(", ") || "Нефть") + "</strong></p>";
      html += "<p>" + escapeHtml(macro[0].reason || skipActionRu("SKIP_MACRO")) + "</p>";
    }
    if (regime.length) {
      html += "<p><strong>" + escapeHtml(uniqueNames(regime).join(", ")) + "</strong></p>";
      html += "<p>На этих рынках та же идея: купить дешёвую разницу месяцев, продать дорогую и ждать возврата к среднему. "
        + "Сейчас разница уверенно едет в одну сторону. Если встать против этого, можно долго сидеть в минусе. Поэтому тоже пауза.</p>";
    }
    if (other.length) {
      uniqueNames(other).forEach(function (name) {
        const row = other.find(function (s) { return (s.name || s.family) === name; });
        html += "<p><strong>" + escapeHtml(name) + "</strong></p>";
        html += "<p>" + escapeHtml((row && row.reason) || skipActionRu(row && row.action)) + "</p>";
      });
    }
    el.innerHTML = html;
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

  function hasPaint(data) {
    const s = data && data.selected && data.selected.series;
    return Array.isArray(s) && s.length > 0;
  }

  function seriesCacheId(fam, st, pair) {
    return "ARB-" + String(fam || "").toUpperCase()
      + "-" + String(st || "CALENDAR").toUpperCase()
      + "-" + String(pair || "_").toUpperCase();
  }

  async function cachePutSeries(sel) {
    if (!sel || !window.TrinityChartKit || typeof TrinityChartKit.barCachePut !== "function") return;
    const series = sel.series;
    if (!Array.isArray(series) || !series.length) return;
    const payload = {
      bars: series,
      family: sel.family,
      structure: sel.structure,
      pair: sel.pair,
      near: sel.near,
      next: sel.next,
      wing: sel.wing
    };
    const kit = TrinityChartKit;
    await kit.barCachePut(seriesCacheId(sel.family, sel.structure, sel.pair), "ARB", payload);
    await kit.barCachePut(seriesCacheId(sel.family, sel.structure, "_"), "ARB", payload);
  }

  async function paintCachedSeries(fam, st, pair) {
    if (!window.TrinityChartKit || typeof TrinityChartKit.barCacheGet !== "function") return false;
    const ids = [];
    if (pair) ids.push(seriesCacheId(fam, st, pair));
    ids.push(seriesCacheId(fam, st, "_"));
    let row = null;
    for (let i = 0; i < ids.length; i++) {
      const hit = await TrinityChartKit.barCacheGet(ids[i], "ARB");
      if (!hit || !hit.bars || !hit.bars.length) continue;
      const tf = String(hit.tf || "").toUpperCase();
      const inst = String(hit.instrument || "").toUpperCase();
      if (tf !== "ARB" || inst !== ids[i].toUpperCase()) continue;
      row = hit;
      break;
    }
    if (!row) return false;
    const sel = {
      family: row.family || fam,
      structure: row.structure || st,
      pair: row.pair || pair,
      near: row.near,
      next: row.next,
      series: row.bars
    };
    arbScaleLocked = false;
    drawCharts(row.bars, sel);
    if ($("arb-pair") && sel.pair) $("arb-pair").textContent = sel.pair;
    if ($("arb-desk-meta")) {
      $("arb-desk-meta").textContent = "локальный архив · " + row.bars.length + " баров · ждём сервер…";
    }
    if ($("arb-chart-label")) {
      $("arb-chart-label").textContent = (sel.structure === "FLY"
        ? "Бабочка: ближний − 2×середина + дальний"
        : "Разница дальнего и ближнего месяца") + " · локальный архив";
    }
    if ($("arb-legs-chart-label") && (sel.near || sel.next)) {
      $("arb-legs-chart-label").textContent = "Ноги · "
        + (sel.near || "ближний") + " / " + (sel.next || "дальний")
        + (sel.structure === "FLY" ? " (середина)" : "")
        + " · локальный архив";
    }
    return true;
  }

  async function fetchJson(url, ms) {
    if (window.TrinityFastBoot && typeof TrinityFastBoot.fetchJson === "function") {
      return TrinityFastBoot.fetchJson(url, { ms: ms || DESK_TIMEOUT_MS });
    }
    const ac = new AbortController();
    const t = setTimeout(function () { ac.abort(); }, ms || DESK_TIMEOUT_MS);
    try {
      const res = await fetch(url, { headers: { Accept: "application/json" }, signal: ac.signal });
      if (!res.ok) throw new Error("HTTP " + res.status);
      return await res.json();
    } finally {
      clearTimeout(t);
    }
  }

  function liveBrokerNote(fp) {
    if (!fp || !fp.liveBroker) return "";
    if (fp.open) return " · брокер · в сделке";
    if (fp.liveArmed) {
      const pause = String(fp.lastAction || "").indexOf("SKIP_") === 0;
      return pause ? " · живые заявки включены, ордеров нет" : " · живые заявки готовы";
    }
    return "";
  }

  function autoOn(data) {
    const settings = (data && data.settings) || data || {};
    const fp = (data && data.fairPaper) || {};
    if (settings.autoExecution != null) return !!settings.autoExecution;
    if (data && data.autoExecution != null) return !!data.autoExecution;
    if (fp.autoExecution != null) return !!fp.autoExecution;
    return true;
  }

  function deliveryRu(code, data) {
    if (!autoOn(data)) return "наблюдение";
    const c = String(code || "");
    if (c === "LIVE_FORTS") return "авто · живые заявки";
    if (c === "SANDBOX_FAIR" || c === "AUTO") return "авто · журнал";
    if (c === "SIGNAL_ONLY") return "наблюдение";
    return c || "авто";
  }

  function syncArbAutoSwitch(data) {
    const tog = $("desk-arb-auto-execution");
    if (!tog || tog.disabled) return;
    const on = autoOn(data);
    tog.checked = on;
    tog.setAttribute("aria-checked", on ? "true" : "false");
    const sw = tog.closest(".mode-switch");
    if (sw) {
      sw.classList.toggle("is-auto", on);
      sw.classList.toggle("is-signal", !on);
    }
  }

  function deskAuthHeaders(extra) {
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

  function bindArbAutoSwitch() {
    const tog = $("desk-arb-auto-execution");
    if (!tog || tog.dataset.bound === "1") return;
    tog.dataset.bound = "1";
    tog.addEventListener("change", function () {
      setArbAutoFromDesk(tog.checked);
    });
  }

  async function setArbAutoFromDesk(enabled) {
    const tog = $("desk-arb-auto-execution");
    if (tog) tog.disabled = true;
    try {
      const res = await fetch("/api/calendar-arb/settings/auto-execution", {
        method: "POST",
        headers: deskAuthHeaders({ "Content-Type": "application/json", Accept: "application/json" }),
        body: JSON.stringify({ enabled: !!enabled })
      });
      if (!res.ok) {
        const errBody = await res.json().catch(function () { return {}; });
        throw new Error(errBody.message || errBody.error || ("HTTP " + res.status));
      }
      const view = await res.json();
      syncArbAutoSwitch(view);
      if ($("arb-delivery")) $("arb-delivery").textContent = deliveryRu(view.delivery, view);
      if ($("arb-robot") && !autoOn(view)) $("arb-robot").textContent = "Наблюдение";
      refreshStatus().catch(function () {});
    } catch (err) {
      if (tog) tog.checked = !enabled;
      if ($("arb-desk-meta")) {
        $("arb-desk-meta").textContent = "Не удалось переключить: " + (err.message || err);
      }
    } finally {
      if (tog) tog.disabled = false;
    }
  }

  function robotLine(data) {
    const fp = (data && data.fairPaper) || {};
    if (fp.open) {
      return "В сделке " + (fp.open.pair || "");
    }
    if (!autoOn(data)) {
      return "Наблюдение";
    }
    const act = fp.lastAction || "";
    const live = fp.liveArmed ? "живые заявки" : "журнал";
    if (act.indexOf("SKIP_") === 0) {
      return "Не торгует · " + skipActionRu(act);
    }
    if (act === "NONE" || act === "") {
      return "Ждёт перекос · " + live;
    }
    return skipActionRu(act) || (act + " · " + live);
  }

  function paintStatus(data) {
    if (!data) return;
    const settings = data.settings || data;
    const fp = data.fairPaper || {};
    const st = data.statement || {};
    if ($("arb-robot")) $("arb-robot").textContent = robotLine(data);
    if ($("arb-source")) $("arb-source").textContent = data.dataSource || settings.dataSource || "брокер";
    if ($("arb-delivery")) $("arb-delivery").textContent = deliveryRu(settings.delivery, data);
    syncArbAutoSwitch(data);
    if ($("arb-action") && ($("arb-action").textContent === "—" || $("arb-action").textContent === "…")) {
      $("arb-action").textContent = fp.open ? "OPEN" : (fp.lastAction || "—");
    }
    if ($("arb-reason") && ($("arb-reason").textContent === "—" || $("arb-reason").textContent.indexOf("ждём") === 0
        || $("arb-reason").textContent.indexOf("Загрузка") === 0)) {
      if (fp.lastReason) $("arb-reason").textContent = fp.lastReason;
    }
    if ($("arb-fair")) {
      $("arb-fair").textContent = fp.open
        ? ("OPEN " + fp.open.side + " " + fp.open.pair)
        : ((fp.lastAction || "idle") + (fp.lastReason ? " · " + fp.lastReason : ""));
      if (fp.liveBroker) {
        $("arb-fair").textContent += liveBrokerNote(fp);
      }
    }
    if ($("arb-paper-today") && st.todayPnlRub != null) {
      $("arb-paper-today").textContent = Number(st.todayPnlRub).toFixed(0) + " ₽";
    }
    fillFamilySelect(data.families && data.families.length ? data.families : DEFAULT_FAMS, settings.family);
    if ($("arb-desk-meta")) {
      const armed = fp.open
        ? "есть позиция"
        : (!autoOn(data)
          ? "наблюдение"
          : (fp.liveArmed ? "авто · живые заявки, ордеров нет" : "авто · журнал, ордеров нет"));
      $("arb-desk-meta").textContent = "Котировки брокера · " + armed;
    }
  }

  async function refreshStatus() {
    const data = await fetchJson("/api/calendar-arb/status", 8000);
    paintStatus(data);
    return data;
  }

  async function refresh() {
    const fam = ($("arb-family") && $("arb-family").value) || loadFamilyFromUrl();
    const st = window.__arbStructure || loadStructureFromUrl();
    let qs = fam ? ("?family=" + encodeURIComponent(fam)) : "";
    if (st) qs += (qs ? "&" : "?") + "structure=" + encodeURIComponent(st);
    let data = await fetchJson("/api/calendar-arb/desk" + qs, DESK_TIMEOUT_MS);
    const wantFam = (fam || "").toUpperCase();
    const wantSt = (st || "").toUpperCase();
    const goodSel = (lastGood && lastGood.selected) || {};
    const sameInstrument = !!(lastGood && (goodSel.family || "").toUpperCase() === wantFam
      && (!wantSt || (goodSel.structure || "").toUpperCase() === wantSt));
    // Cold poll / H1 still on disk fetch: keep last good paint for this tile, never wipe charts.
    if (!hasPaint(data) && sameInstrument && hasPaint(lastGood)) {
      data = Object.assign({}, lastGood, {
        stale: true,
        warming: false,
        warning: data.warning || lastGood.warning
      });
    }
    if (hasPaint(data)) {
      lastGood = data;
      everReady = true;
      cachePutSeries(data.selected).catch(function () {});
    }
    render(data);
  }

  function render(data) {
    const warming = !!data.warming && !everReady;
    const desk = $("calendar-arb-desk");
    if (desk) desk.classList.toggle("is-warming", warming);

    const sel = data.selected || {};
    const settings = data.settings || {};
    $("arb-source").textContent = data.dataSource || "брокер";
    $("arb-delivery").textContent = deliveryRu(settings.delivery, data);
    syncArbAutoSwitch(data);
    $("arb-pair").textContent = sel.pair || (warming ? "…" : "—");
    if ($("arb-structure")) {
      $("arb-structure").textContent = sel.structure === "FLY"
        ? "три месяца"
        : (sel.structure ? "два месяца" : (warming ? "…" : "—"));
    }
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
      let note = "Дневник сделок — песочница по реальным ценам стакана брокера. Это основной счёт, чтобы видеть, как робот вёл бы себя без живых денег. "
        + "Прогон старой истории ниже — только справка, не обещание, что так будет завтра.";
      if (sel.oos && sel.oos.histDomBars) {
        note += " Для этой пары в архиве есть стакан на " + sel.oos.histDomBars + " часовых свечах.";
      }
      $("arb-oos-note").textContent = note;
    }
    if ($("arb-fill-quality")) {
      $("arb-fill-quality").textContent = fillQualityLine(sel.fillQuality, warming);
    }
    if ($("arb-near-locked")) {
      const nl = !!sel.nearLocked;
      $("arb-near-locked").hidden = !nl;
      if (nl) {
        $("arb-near-locked").textContent = "Цены в стакане пересеклись (редкий сбой котировок). Это пометка для разбора, робот из‑за этого ордер сам не шлёт.";
      }
    }
    if ($("arb-session-skips")) {
      paintSkipExplain($("arb-session-skips"), data.sessionSkips, warming);
    }
    if ($("arb-chart-label")) {
      $("arb-chart-label").textContent = sel.structure === "FLY"
        ? "Бабочка: ближний − 2×середина + дальний (час брокера)"
        : "Разница дальнего и ближнего месяца (час брокера)";
    }
    if ($("arb-legs-chart-label")) {
      const nearName = sel.near || "near";
      const nextName = sel.next || "next";
      $("arb-legs-chart-label").textContent = sel.structure === "FLY"
        ? ("Ноги · " + nearName + " / " + nextName + " (середина)")
        : ("Ноги · " + nearName + " / " + nextName);
    }
    window.__arbStructure = sel.structure || window.__arbStructure || "";
    $("arb-spread").textContent = warming && sel.spread == null ? "…" : fmt(sel.spread, 3);
    $("arb-z").textContent = warming && sel.z == null ? "…" : fmt(sel.z, 2);
    $("arb-action").textContent = warming && !sel.action
      ? "загрузка"
      : (String(sel.action || "").indexOf("SKIP_") === 0
        ? skipActionRu(sel.action)
        : (sel.action === "NONE" ? "ждёт перекос" : (sel.action || "—")));
    $("arb-reason").textContent = warming
      ? "Загрузка ближнего/дальнего контракта у T-Invest (первый запрос часто пустой, пока прогреется gRPC)…"
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
    if ($("arb-robot")) $("arb-robot").textContent = robotLine(data);
    $("arb-fair").textContent = fp.open
      ? ("OPEN " + fp.open.side + " " + fp.open.pair)
      : ((fp.lastAction || "idle") + (fp.lastReason ? " · " + fp.lastReason : ""));
    if (fp.liveBroker) {
      $("arb-fair").textContent += liveBrokerNote(fp);
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
    const series = sel.series || [];
    if (series.length) {
      drawCharts(series, sel);
    }
    $("arb-desk-meta").textContent =
      "Котировки брокера" +
      (data.tokenPresent === false ? " · нет токена" : " · час") +
      (series.length ? (" · баров " + series.length) : (sel.bars ? (" · баров " + sel.bars) : "")) +
      (warming ? " · загрузка…" : "") +
      (data.stale ? " · локальный снимок" : "") +
      (fp.open ? " · есть позиция"
        : (!autoOn(data) ? " · наблюдение"
          : (fp.liveArmed ? " · авто · живые заявки, ордеров нет" : " · авто · журнал, ордеров нет")));
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
        '" data-structure="' + cst +
        '" data-pair="' + (c.pair || "") + '">' +
        "<strong>" + (c.family || "") + " " + (cst === "FLY" ? "FLY" : "") + "</strong> " + (c.pair || "—") +
        "<span>z=" + z + " · " + (c.action || "") + "</span></button>";
    }).join("");
    host.querySelectorAll(".arb-card").forEach(function (btn) {
      btn.addEventListener("click", function () {
        const fam = btn.getAttribute("data-family");
        const st = btn.getAttribute("data-structure") || "";
        const pair = btn.getAttribute("data-pair") || "";
        if ($("arb-family")) $("arb-family").value = fam;
        window.__arbStructure = st;
        arbScaleLocked = false;
        paintCachedSeries(fam, st, pair).finally(function () {
          refresh().catch(function (e) { console.warn(e); });
        });
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
      timeScale: { borderColor: "#d5dde2", timeVisible: true, secondsVisible: false },
      handleScroll: { mouseWheel: false, pressedMouseMove: true, horzTouchDrag: true, vertTouchDrag: false },
      handleScale: { axisPressedMouseMove: true, mouseWheel: false, pinch: true }
    };
  }

  function bindArbChartScale(el, chartInst, seriesInst, getBars, pointSize) {
    if (!el || !chartInst || !seriesInst) return;
    const kit = window.TrinityChartKit;
    if (kit && typeof kit.attachFriendlyNav === "function") {
      kit.attachFriendlyNav({
        chart: chartInst,
        series: seriesInst,
        hostEl: el,
        barSec: 3600,
        pointSize: pointSize > 0 ? pointSize : 0.01,
        getBars: getBars,
        onTimeGesture: function () { arbScaleLocked = true; }
      });
      return;
    }
    if (!kit || typeof kit.bindScaleOverlayFollow !== "function") return;
    if (el._trinityOverlayFollowBound) return;
    kit.bindScaleOverlayFollow(el, {
      chart: chartInst,
      series: seriesInst,
      freezePrice: false,
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

  function bindChartSync() {
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
      bindArbChartScale(spreadEl, chart, series, function () { return lastSpreadData; }, 0.01);
    }
    if (legsEl && !legsChart) {
      legsChart = LightweightCharts.createChart(legsEl, whiteChartOpts(legsEl, legsEl.clientHeight || 280));
      nearSeries = legsChart.addLineSeries({ color: "#15803d", lineWidth: 2, title: "near" });
      nextSeries = legsChart.addLineSeries({ color: "#b45309", lineWidth: 2, title: "next" });
      bindArbChartScale(legsEl, legsChart, nearSeries, function () { return lastNearData; }, 0.01);
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
    const spread = toLine(points, "spread");
    if (!spread.length) return;
    const key = (sel.family || "") + "|" + (sel.structure || "") + "|" + (sel.pair || "");
    const pairChanged = key !== lastSeriesKey;
    lastSeriesKey = key;
    if (pairChanged) arbScaleLocked = false;
    const near = toLine(points, "near");
    const next = toLine(points, "far");
    lastSpreadData = spread;
    lastNearData = near;
    if (series) setLineDataKeep(chart, series, spread);
    if (nearSeries) setLineDataKeep(legsChart, nearSeries, near);
    if (nextSeries) setLineDataKeep(legsChart, nextSeries, next);
    if (pairChanged) {
      if (chart) chart.timeScale().fitContent();
      if (legsChart) legsChart.timeScale().fitContent();
    }
    function paintNav(el) {
      const api = el && el._trinityFriendlyNav;
      if (api && api.ohlcTip && typeof api.ohlcTip.paintLast === "function") api.ohlcTip.paintLast();
      if (api && api.syncGoLive) api.syncGoLive();
    }
    paintNav($("arb-chart"));
    paintNav($("arb-legs-chart"));
  }

  function bindArbGuide() {
    let lastFocus = null;
    function openGuide() {
      const gate = $("arb-guide-modal");
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
      const gate = $("arb-guide-modal");
      if (!gate || gate.hidden) return;
      gate.classList.remove("is-open");
      gate.setAttribute("aria-hidden", "true");
      window.setTimeout(function () {
        gate.hidden = true;
        if (lastFocus && typeof lastFocus.focus === "function") lastFocus.focus();
        lastFocus = null;
      }, 220);
    }
    const openBtn = $("arb-guide-open");
    if (openBtn) openBtn.addEventListener("click", openGuide);
    const gate = $("arb-guide-modal");
    if (gate) {
      gate.querySelectorAll("[data-arb-guide-close]").forEach(function (el) {
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
      const g = $("arb-guide-modal");
      if (g && !g.hidden) {
        closeGuide();
        ev.preventDefault();
      }
    });
  }

  function bind() {
    fillFamilySelect(DEFAULT_FAMS, loadFamilyFromUrl());
    bindArbAutoSwitch();
    bindArbGuide();
    if ($("arb-desk-refresh")) {
      $("arb-desk-refresh").addEventListener("click", function () {
        refreshStatus().catch(function () {});
        refresh().catch(function (e) { $("arb-desk-meta").textContent = String(e); });
      });
    }
    if ($("arb-family")) {
      $("arb-family").addEventListener("change", function () {
        window.__arbStructure = "";
        arbScaleLocked = false;
        const fam = $("arb-family").value;
        paintCachedSeries(fam, "", "").finally(function () {
          refresh().catch(function (e) { console.warn(e); });
        });
      });
    }
    refreshStatus().catch(function () {});
    const fam0 = ($("arb-family") && $("arb-family").value) || loadFamilyFromUrl();
    const st0 = window.__arbStructure || loadStructureFromUrl();
    paintCachedSeries(fam0, st0, "").finally(function () {
      refresh().catch(function (e) {
        $("arb-desk-meta").textContent = "Desk: " + (e.message || e);
      });
    });
    setTimeout(function () {
      if (!everReady) refresh().catch(function () {});
    }, 2500);
    pollTimer = setInterval(function () {
      refreshStatus().catch(function () {});
      refresh().catch(function () {});
    }, POLL_MS);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", bind);
  } else {
    bind();
  }
})();
