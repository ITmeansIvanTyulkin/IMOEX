(function () {
  let chart = null;
  let volumeChart = null;
  let candleSeries = null;
  let volumeSeries = null;
  let priceLines = [];
  let lastOverlayKey = "";
  let overlayStructure = {};
  let lastCandleTime = null;
  let lastBarsRaw = [];
  let lastSanitizedCandles = [];
  let chartNeedsFit = false;
  let userPinned = false;
  let followLive = true;
  let applyingScale = false;
  let scaleLocked = false;
  let lockedBarSpacing = null;
  let lockedLogical = null;
  let lastCandlesLen = 0;
  let priceScaleLocked = false;
  let scaleRememberTimer = null;
  let userScaleGesture = false;
  let chartNav = null;
  const SCALE_STORE = "trinity.trend.desk.scale";
  const INST_STORE = "trinity.trend.desk.instrument";
  const PB_STORE = "trinity.trend.desk.playbook";
  let deskInstrumentPinned = null;
  let rightPadOn = true;
  let showVolume = false;
  let showProfile = true;
  let showMacd = false;
  let fpToolActive = false;
  let fpPinned = [];
  let fpHoverTime = null;
  let fpAnchor = null;
  let fpRangeFrom = null;
  let fpRangeTo = null;
  let fpSelected = false;
  let fpDragEnd = null; // 'from' | 'to'
  let footprintByTime = {};
  let lastProfile = [];
  let lastFootprint = [];
  let lastChartTf = "M5";
  let fpHostBound = false;
  let macdChart = null;
  let macdHistSeries = null;
  let macdLineSeries = null;
  let macdSignalSeries = null;
  let macdSynced = false;
  let lastDivMarkers = [];
  let lastSignalMarkers = [];
  let lastTimelineMarkers = [];
  let lastDivMarkersKey = "";
  let lastDeskInstrument = "";
  let deskFetchGen = 0;
  let deskQueuedForceFit = false;
  let chartTools = null;
  let deskLayoutDoc = null;
  let deskLayoutTimer = null;
  let domFollowMid = true;
  let domScrollBound = false;
  const DESK_MS = 12000;
  const BOOK_MS = 8000;
  const FP_PIN_MAX = 8;
  const MACD_FAST = 12;
  const MACD_SLOW = 26;
  const MACD_SIGNAL = 9;
  const RIGHT_PAD_ON = 22;
  const RIGHT_PAD_OFF = 4;
  const HI_LO_COLOR = "#b91c1c";
  const ZONE_EDGE = "#6d28d9";
  const DESK_LIVE_MS = 2000;
  const BOOK_LIVE_MS = 1500;
  let lastWorkingOpen = null;
  let lastOverlayPlan = {};
  let lastOverlaySig = {};
  let lastOverlayCandles = [];
  let lastDeskSnapshot = null;
  let liveFlatUntil = 0;
  let liveTp1Until = 0;

  function $(id) { return document.getElementById(id); }
  function deskScope() {
    const root = $("trend-signal-desk");
    const s = root && root.getAttribute("data-desk-scope");
    return s === "positional" ? "positional" : "range";
  }
  function viewPlaybookId() {
    return deskScope() === "positional" ? "positional-volume-h1" : "levels-profile-br-m5";
  }
  function deskBars(data) {
    if (deskScope() === "positional" || (data && data.deskScope === "positional")) {
      if (data && Array.isArray(data.barsH1) && data.barsH1.length) return data.barsH1;
    }
    return (data && data.bars) || [];
  }
  function instrumentTitle(data) {
    if (!data) return "—";
    if (data.instrumentName) return data.instrumentName + " · " + (data.instrument || "");
    return data.instrument || "—";
  }
  function applyDeskChrome() {
    const pos = deskScope() === "positional";
    const volLab = $("signal-desk-volume-label");
    if (volLab) volLab.textContent = pos ? "Объём H1" : "Объём M5";
    const hint = $("signal-chart-hint");
    if (hint && pos) {
      hint.innerHTML = "Часовой график · след / MACD / профиль / линии · "
        + "<a href=\"/view/trend-charts\">терминал графиков</a> · "
        + "вход в промежуточную полку объёма · сетка 1:1:2:4 · стоп и тейк";
    }
    const oilBan = $("us-oil-banner");
    if (oilBan) oilBan.hidden = pos;
    const lead = $("signal-guide-lead");
    const gtitle = $("signal-guide-title");
    if (pos) {
      if (gtitle) gtitle.textContent = "Как работает позиционная";
      if (lead) lead.textContent = "Часовой тренд, средняя полка объёма, сетка 1:1:2:4, охота до входа и трейл за закрытой свечой. «Сканирует» — робот включён, входа сейчас нет.";
    }
    const kick = $("sig-kick-btn");
    if (kick) kick.hidden = pos;
    const wrap = $("positional-auto-wrap");
    if (wrap) wrap.hidden = !pos;
    const modeLink = $("signal-desk-mode-link");
    if (modeLink) {
      modeLink.setAttribute("href", pos
        ? "/view/settings#positional-playbook-settings"
        : "/view/settings#trend-playbook-settings");
    }
    document.querySelectorAll("[data-guide-scope]").forEach(function (el) {
      const want = el.getAttribute("data-guide-scope");
      el.hidden = !!(want && want !== "both" && want !== deskScope());
    });
  }
  function paintRobotChip(data) {
    const chip = $("sig-robot-chip");
    const el = $("sig-robot-status");
    if (!chip || !el) return;
    const sit = (data && data.situation) || {};
    const posture = sit.posture || "";
    if (deskScope() === "positional" && data && data.positionalAutoExecution === false) {
      el.textContent = "Пауза";
      const sub = $("sig-robot-detail");
      if (sub) {
        sub.textContent = "Робот выключен тумблером — график смотрим, paper-входов нет";
        sub.hidden = false;
      }
      chip.classList.remove("is-trade", "is-armed", "is-watch", "is-scan");
      chip.classList.add("is-scan");
      chip.title = "Позиционный робот выключен";
      return;
    }
    const copy = buildRobotFabCopy(data);
    const status = (copy && copy.status) ? copy.status : "Сканирует";
    const detail = (copy && copy.detail) ? copy.detail : "";
    el.textContent = status;
    const sub = $("sig-robot-detail");
    if (sub) {
      sub.textContent = detail;
      sub.hidden = !detail;
    }
    chip.classList.remove("is-trade", "is-armed", "is-watch", "is-scan");
    if (posture === "IN_TRADE") chip.classList.add("is-trade");
    else if (posture === "WAITING_FILL") chip.classList.add("is-armed");
    else if (posture === "WATCHING_ZONE") chip.classList.add("is-watch");
    else chip.classList.add("is-scan");
    chip.title = detail ? (status + " · " + detail) : status;
  }
  function syncPositionalAutoSwitch(data) {
    const wrap = $("positional-auto-wrap");
    const tog = $("desk-positional-auto-execution");
    const pos = deskScope() === "positional";
    if (wrap) wrap.hidden = !pos;
    if (!tog) return;
    const on = !!(data && data.positionalAutoExecution);
    tog.checked = on;
    tog.setAttribute("aria-checked", on ? "true" : "false");
    const sw = tog.closest(".mode-switch");
    if (sw) {
      sw.classList.toggle("is-auto", on);
      sw.classList.toggle("is-signal", !on);
    }
  }
  function bindPositionalAutoSwitch() {
    const tog = $("desk-positional-auto-execution");
    if (!tog || tog.dataset.bound === "1") return;
    tog.dataset.bound = "1";
    tog.addEventListener("change", function () {
      setPositionalAutoFromDesk(tog.checked);
    });
  }
  async function setPositionalAutoFromDesk(enabled) {
    const tog = $("desk-positional-auto-execution");
    if (tog) tog.disabled = true;
    try {
      const res = await fetch("/api/trend/settings/positional-auto-execution", {
        method: "POST",
        headers: deskAuthHeaders({ "Content-Type": "application/json", Accept: "application/json" }),
        body: JSON.stringify({ enabled: !!enabled })
      });
      if (!res.ok) {
        const errBody = await res.json().catch(function () { return {}; });
        throw new Error(errBody.message || errBody.error || ("HTTP " + res.status));
      }
      const view = await res.json();
      syncPositionalAutoSwitch({ positionalAutoExecution: !!view.positionalAutoExecution });
      if (window.TrinityPlaques && typeof window.TrinityPlaques.refresh === "function") {
        window.TrinityPlaques.refresh();
      }
      await loadDesk(true);
    } catch (e) {
      alert("Не удалось переключить позиционного робота: " + (e && e.message ? e.message : e)
        + "\nНужен вход в кабинет.");
      if (tog) tog.checked = !enabled;
    } finally {
      if (tog) tog.disabled = false;
    }
  }
  function deMark(s) {
    return String(s == null ? "" : s)
      .replace(/кроме\s*§\s*8\b/gi, "кроме ретеста")
      .replace(/сценари[йя]\s*§\s*8\b/gi, "ретест")
      .replace(/§\s*8\b/gi, "ретест")
      .replace(/§\s*14\b/gi, "")
      .replace(/§\s*\d+(?:\s*[\/–-]\s*\d+)*/g, "")
      .replace(/§\s*/g, "")
      .replace(/\s+/g, " ")
      .trim();
  }
  function ruDeals(n) {
    const x = Math.abs(Number(n) || 0);
    const n10 = x % 10;
    const n100 = x % 100;
    if (n10 === 1 && n100 !== 11) return x + " сделка";
    if (n10 >= 2 && n10 <= 4 && (n100 < 12 || n100 > 14)) return x + " сделки";
    return x + " сделок";
  }
  function userFacingStory(raw) {
    const src = String(raw == null ? "" : raw).trim();
    if (!src) return "";
    const engineLead = /§/.test(src) || looksTechnicalStatus(src)
      || /\bTREND_HI\b|\bTREND_LO\b|\bwaiting:\s/i.test(src);
    if (engineLead) {
      const ru = src.split(/(?<=[.!?])\s+/).filter(function (x) {
        return /[А-Яа-яЁё]/.test(x) && x.indexOf("§") < 0 && !looksTechnicalStatus(x);
      });
      if (ru.length) return ru.join(" ").replace(/\s+/g, " ").trim();
      return humanizeDeskReason(src);
    }
    const out = humanizeDeskReason(src);
    return looksTechnicalStatus(out) ? "" : out;
  }
  function escHtml(t) {
    return String(t == null ? "" : t)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
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
  function hasDeskWriteAuth() {
    try {
      if (localStorage.getItem("trinity.supabase.access_token")) return true;
      const user = (localStorage.getItem("imoex.ops.user") || "").trim();
      const pass = localStorage.getItem("imoex.ops.pass") || "";
      return !!(user && pass && user.indexOf("@") < 0);
    } catch (_) {
      return false;
    }
  }
  function readStoredInstrument() {
    try { return (localStorage.getItem(INST_STORE) || "").trim(); } catch (_) { return ""; }
  }
  function writeStoredInstrument(v) {
    try {
      if (v) localStorage.setItem(INST_STORE, String(v).trim());
    } catch (_) {}
  }
  function readStoredPlaybook() {
    try { return (localStorage.getItem(PB_STORE) || "").trim(); } catch (_) { return ""; }
  }
  function instrumentFamily(secid) {
    const u = String(secid || "").trim();
    if (!u) return "";
    const up = u.toUpperCase();
    if (up === "GOLD" || up.indexOf("GD") === 0) return "GD";
    if (up === "MIX" || up.indexOf("MX") === 0) return "MX";
    if (up === "SI" || up === "USD" || up.indexOf("SI") === 0
        || (u.length >= 2 && u.charAt(0) === "S" && u.charAt(1) === "i")) {
      return "SI";
    }
    if (up === "RTS" || up === "RT" || up.indexOf("RI") === 0
        || (u.length >= 2 && u.charAt(0) === "R" && u.charAt(1) === "i")) {
      return "RI";
    }
    if (up.indexOf("NG") === 0) return "NG";
    if (up.indexOf("BR") === 0) return "BR";
    return up.slice(0, 2);
  }
  function sameInstrumentFamily(a, b) {
    const fa = instrumentFamily(a);
    const fb = instrumentFamily(b);
    return !!(fa && fb && fa === fb);
  }
  function wantedDeskInstrument() {
    const instSel = $("sig-instrument");
    const fromSel = (instSel && instSel.value) ? instSel.value.trim() : "";
    return (deskInstrumentPinned || fromSel || "").trim();
  }
  function invalidateDeskFetch() {
    deskFetchGen += 1;
  }
  function writeStoredPlaybook(v) {
    try {
      if (v) localStorage.setItem(PB_STORE, String(v).trim());
    } catch (_) {}
  }
  function matchInstrumentOption(sel, secid) {
    if (!sel || !secid) return false;
    for (let i = 0; i < sel.options.length; i++) {
      if (sel.options[i].value === secid) {
        sel.value = secid;
        return true;
      }
    }
    const want = String(secid).toUpperCase();
    const fam = instrumentFamily(secid);
    for (let i = 0; i < sel.options.length; i++) {
      const v = String(sel.options[i].value || "").toUpperCase();
      if (fam && instrumentFamily(sel.options[i].value) === fam) {
        if (v !== want) {
          sel.options[i].value = secid;
          const label = sel.options[i].textContent || "";
          sel.options[i].textContent = label.replace(/\s·\s\S+$/, " · " + secid);
        }
        sel.value = secid;
        return true;
      }
    }
    return false;
  }
  function applyBootInstrumentFromStorage() {
    const instSel = $("sig-instrument");
    if (!instSel || instSel.options.length === 0) return;
    const fromLayout = deskLayoutDoc && deskLayoutDoc.desk && deskLayoutDoc.desk.instrument;
    const pick = deskInstrumentPinned || fromLayout || readStoredInstrument();
    if (pick && matchInstrumentOption(instSel, pick)) {
      deskInstrumentPinned = instSel.value;
    }
    const pbSel = $("sig-playbook");
    const pbPick = (deskLayoutDoc && deskLayoutDoc.desk && deskLayoutDoc.desk.playbookId)
      || readStoredPlaybook();
    if (pbSel && pbPick && pbSel.options.length) {
      for (let i = 0; i < pbSel.options.length; i++) {
        if (pbSel.options[i].value === pbPick) {
          pbSel.value = pbPick;
          break;
        }
      }
    }
  }
  function applyUrlInstrumentOnce() {
    try {
      const inst = (new URLSearchParams(location.search).get("instrument") || "").trim();
      if (!inst) return;
      const instSel = $("sig-instrument");
      if (instSel && matchInstrumentOption(instSel, inst)) {
        deskInstrumentPinned = instSel.value;
        writeStoredInstrument(instSel.value);
        scheduleSaveDeskLayout();
      }
    } catch (_) {}
  }
  function fmtPot(v) {
    if (v == null || typeof v !== "number" || !isFinite(v)) return "—";
    return "~" + (v >= 0 ? "+" : "") + Math.round(v).toLocaleString("ru-RU") + " ₽";
  }
  function fmtPnl(v) {
    if (v == null || typeof v !== "number" || !isFinite(v)) return "—";
    const s = (v >= 0 ? "+" : "") + Math.round(v).toLocaleString("ru-RU") + " ₽";
    return s;
  }
  function shortTime(iso) {
    if (!iso) return "—";
    const m = String(iso).match(/T(\d{2}:\d{2})/);
    return m ? m[1] : iso;
  }
  function clockMeta(sit) {
    if (!sit) return "";
    const rb = sit.robotBar ? shortTime(sit.robotBar) : "";
    const cb = sit.chartBar ? shortTime(sit.chartBar) : "";
    if (rb && cb && rb !== cb) {
      return " · робот " + rb + " · график " + cb + " · SL по ходу бара";
    }
    if (rb) return " · робот " + rb + " · SL по ходу бара";
    if (sit.fillModeRu) return " · " + sit.fillModeRu;
    return "";
  }
  function lastCloseBit(sit, fp, pbId) {
    const lane = fp && fp.lanes && pbId ? fp.lanes[pbId] : null;
    const lc = (lane && lane.lastClose) || (sit && sit.lastClose) || null;
    if (!lc || !lc.exitReason) return "";
    const t = shortTime(lc.closedAt);
    const pnl = typeof lc.pnlRub === "number" ? (" " + fmtPnl(lc.pnlRub)) : "";
    return "Последняя: " + lc.exitReason
      + (lc.slKind === "SWEEP" ? " sweep" : "")
      + pnl + (t && t !== "—" ? (" в " + t) : "");
  }
  function renderPaper(paper, desk) {
    const st = (paper && paper.statement) || {};
    const todayEl = $("sig-paper-today");
    const totalEl = $("sig-paper-total");
    if (todayEl) {
      todayEl.textContent = fmtPnl(st.todayPnlRub);
      todayEl.classList.toggle("is-buy", (st.todayPnlRub || 0) > 0);
      todayEl.classList.toggle("is-sell", (st.todayPnlRub || 0) < 0);
    }
    if (totalEl) {
      const w = (st.wins || 0) + "/" + (st.losses || 0);
      totalEl.textContent = fmtPnl(st.realizedPnlRub) + " · " + w;
      totalEl.classList.toggle("is-buy", (st.realizedPnlRub || 0) > 0);
      totalEl.classList.toggle("is-sell", (st.realizedPnlRub || 0) < 0);
    }
    const panel = $("signal-paper-panel");
    const body = $("signal-paper-body");
    const meta = $("signal-paper-meta");
    const raw = (paper && (paper.todayTrades || paper.recentTrades)) || [];
    const today = mskTodayYmd();
    const wantInst = desk && desk.instrument;
    const wantPb = viewPlaybookId();
    const rows = raw.filter(function (t) {
      if (!isSameMskDay(t && (t.closedAt || t.openedAt), today)) return false;
      if (wantInst && t.instrument && !sameInstrumentFamily(t.instrument, wantInst)) return false;
      if (wantPb && wantPb !== "both") {
        const pb = playbookFromTrade(t);
        if (!pb || pb !== wantPb) return false;
      }
      return true;
    });
    const latest = paper && paper.latestClose;
    if (latest && latest.id && isSameMskDay(latest.closedAt, today)) {
      const latestPb = playbookFromTrade(latest);
      const samePb = !wantPb || wantPb === "both" || latestPb === wantPb;
      const has = rows.some(function (t) { return t && t.id === latest.id; });
      if (samePb && !has) {
        rows.unshift(latest);
      }
    }
    if (!panel || !body) return;
    if (!rows.length) {
      panel.hidden = true;
      body.innerHTML = "";
      if (meta) meta.textContent = "";
      return;
    }
    panel.hidden = false;
    if (meta) {
      const rowPnl = rows.reduce(function (sum, t) {
        const n = t && t.pnlRub;
        return sum + (typeof n === "number" ? n : 0);
      }, 0);
      meta.innerHTML = "Сегодня · " + ruDeals(rows.length)
        + " · PnL " + fmtPnl(rowPnl)
        + " · <a href=\"/view/statement\">Statement</a>";
    }
    body.innerHTML = rows.map(function (t) {
      const pnl = t.pnlRub;
      const cls = pnl > 0 ? "is-buy" : (pnl < 0 ? "is-sell" : "");
      return "<tr>"
        + "<td>" + shortDate(t.closedAt || t.openedAt) + "</td>"
        + "<td>" + shortTime(t.openedAt)
        + (t.entryPrice != null ? " · " + fmtPx(t.entryPrice) : "") + "</td>"
        + "<td>" + shortTime(t.closedAt)
        + (t.exitPrice != null ? " · " + fmtPx(t.exitPrice) : "") + "</td>"
        + "<td>" + (t.side || "—") + "</td>"
        + "<td>" + fmtQtyFilledPlanned(t) + "</td>"
        + "<td>" + (t.exitReason || "—") + "</td>"
        + "<td class='" + cls + "'>" + fmtPnl(pnl) + "</td>"
        + "<td>" + (t.tag || "—") + "</td>"
        + "</tr>";
    }).join("");
  }
  function sameInstrumentFamily(a, b) {
    if (!a || !b) return !a && !b;
    if (String(a).toUpperCase() === String(b).toUpperCase()) return true;
    function fam(x) {
      const s = String(x).toUpperCase();
      if (s.indexOf("BR") === 0) return "BR";
      if (s.indexOf("RI") === 0 || s.indexOf("RTS") === 0) return "RI";
      if (s.indexOf("SI") === 0) return "SI";
      if (s.indexOf("NG") === 0) return "NG";
      if (s.indexOf("GD") === 0) return "GD";
      if (s.indexOf("MX") === 0 || s.indexOf("MIX") === 0) return "MX";
      return s.replace(/\d+$/, "");
    }
    return fam(a) === fam(b);
  }
  function playbookFromTrade(t) {
    const id = (t && t.id) ? String(t.id) : "";
    if (id.indexOf("positional-volume-h1") >= 0) return "positional-volume-h1";
    if (id.indexOf("levels-profile-br-m5") >= 0) return "levels-profile-br-m5";
    const notes = (t && t.notes) ? String(t.notes) : "";
    const m = notes.match(/playbook=([^\s.;]+)/);
    return m ? m[1] : null;
  }
  /** Filled lots vs armed grid — e.g. 1/3 when only one limit filled. */
  function fmtQtyFilledPlanned(t) {
    if (!t || t.qty == null) return "—";
    const filled = Number(t.qty);
    const planned = t.plannedQty != null ? Number(t.plannedQty) : NaN;
    if (Number.isFinite(planned) && planned > 0 && planned !== filled) {
      return filled + "/" + planned;
    }
    const notes = (t.notes && String(t.notes)) || "";
    const m = notes.match(/filled\s+(\d+)\s*\/\s*(\d+)/i);
    if (m) return m[1] + "/" + m[2];
    return String(filled);
  }
  function shortDate(iso) {
    if (!iso) return "—";
    const m = String(iso).match(/(\d{4})-(\d{2})-(\d{2})/);
    if (m) return m[3] + "." + m[2] + "." + m[1];
    return String(iso).slice(0, 10);
  }
  function mskTodayYmd() {
    try {
      return new Intl.DateTimeFormat("en-CA", {
        timeZone: "Europe/Moscow",
        year: "numeric", month: "2-digit", day: "2-digit"
      }).format(new Date());
    } catch (_) {
      return new Date().toISOString().slice(0, 10);
    }
  }
  function isSameMskDay(iso, ymd) {
    if (!iso || !ymd) return false;
    const s = String(iso);
    if (s.length >= 10 && s.slice(0, 10) === ymd) return true;
    try {
      const d = new Date(s);
      if (isNaN(d.getTime())) return false;
      const fmt = new Intl.DateTimeFormat("en-CA", {
        timeZone: "Europe/Moscow",
        year: "numeric", month: "2-digit", day: "2-digit"
      });
      return fmt.format(d) === ymd;
    } catch (_) {
      return false;
    }
  }
  function fmtPx(v) {
    if (v == null || typeof v !== "number" || !isFinite(v)) return "—";
    return v.toFixed(2);
  }
  function commentaryHtml(data) {
    const c = (data && data.commentary) || ((data && data.situation) ? data.situation.commentary : null);
    if (!c || !c.headline) return "";
    const story = Array.isArray(c.story) ? c.story : [];
    let html = "<div class='signal-narration'>";
    html += "<p class='signal-brief-kicker signal-brief-kicker--gold'>Разбор · как видит робот</p>";
    html += "<p class='signal-narration-head'><strong>" + escHtml(c.verdict || "") + "</strong> — "
      + escHtml(userFacingStory(c.headline) || c.headline) + "</p>";
    story.forEach(function (p) {
      if (!p) return;
      const text = userFacingStory(p);
      if (!text) return;
      const u = text.toLowerCase();
      if (u.indexOf("очк") >= 0 || u.indexOf("чек-лист") >= 0 || u.indexOf("playbook") >= 0) return;
      html += "<p class='signal-narration-p'>" + escHtml(text) + "</p>";
    });
    if (c.disclaimer) {
      html += "<p class='signal-narration-disc'>" + escHtml(c.disclaimer) + "</p>";
    }
    html += "</div>";
    return html;
  }
  function contractExpiryBriefHtml(sit, esc) {
    const ex = sit && sit.contractExpiry;
    if (!ex || !ex.headline) return "";
    const cls = ex.isToday ? "signal-brief-expiry signal-brief-expiry--today" : "signal-brief-expiry";
    let html = "<p class='" + cls + "'><strong>" + esc(ex.headline) + "</strong>";
    if (ex.detail) html += " — " + esc(ex.detail);
    html += "</p>";
    return html;
  }
  function isDeskEventUpcoming(e) {
    return e && (e.status === "UPCOMING" || e.status === "TODAY");
  }
  function buildPositionalBrief(data) {
    const esc = escHtml;
    const bars = deskBars(data);
    const last = bars.length ? bars[bars.length - 1] : null;
    const close = last && typeof last.close === "number" ? last.close : null;
    const sit = data.situation || {};
    const plan = data.plan || {};
    const st = data.structure || {};
    const sig = data.signal || {};
    const paperSt = (data.paper && data.paper.statement) || {};
    const events = data.events || [];
    const title = instrumentTitle(data);
    const tf = data.timeframe || "H1";
    const posture = sit.posture || "SCANNING";
    const postureRu = ({
      IN_TRADE: "В СДЕЛКЕ",
      WAITING_FILL: "ЖДЁТ ИСПОЛНЕНИЯ",
      WATCHING_ZONE: "СМОТРИТ ЗОНУ",
      NOT_IN_TRADE: "НЕ В СДЕЛКЕ",
      SCANNING: "СКАНИРУЕТ"
    })[posture] || posture;
    const reason = humanizeDeskReason(sit.why || data.summary || plan.rationale || "");
    const hunt = data.positionalHunt || sit.positionalHunt || {};
    const htf = sit.htf || st.htf || "?";
    const range = plan.range || {};
    const grid = plan.grid || {};

    let html = "<p class='signal-brief-kicker signal-brief-kicker--gold'>Рынок · позиционная H1</p>";
    html += "<ul class='signal-brief-list'>";
    html += "<li>" + esc(title) + " · <strong>" + (close != null ? fmtPx(close) : "—")
      + "</strong> · " + (tf === "H1" ? "час" : esc(tf)) + "</li>";
    if (htf === "UP") {
      html += "<li>Тренд на часе <strong>вверх</strong>: хаи и лои растут лесенкой. Сторона только лонг, шорт против тренда не берём.</li>";
    } else if (htf === "DOWN") {
      html += "<li>Тренд на часе <strong>вниз</strong>: хаи и лои снижаются. Сторона только шорт, лонг против тренда не берём.</li>";
    } else {
      html += "<li>На часе нет явного тренда — ни восходящей лесенки (хаи/лои выше), ни нисходящей. Флэт или пила: в лонг и в шорт не лезем, ждём структуру.</li>";
    }
    if (range.low != null && range.high != null) {
      html += "<li>Зона входа — промежуточная полка объёма: " + fmtPx(range.low) + "–" + fmtPx(range.high)
        + (range.valid === false ? " · пока не рабочая" : "") + "</li>";
    }
    const levels = st.checklistLevels || [];
    if (levels.length) {
      html += "<li>Полки объёма: " + levels.map(function (l) {
        const role = l.role === "ENTRY_ZONE" ? "вход" : ("полка " + String(l.role || "").replace(/^HVN_?/i, ""));
        return esc(role) + " " + (l.rangeLow != null ? fmtPx(l.rangeLow) + "–" + fmtPx(l.rangeHigh) : fmtPx(l.price));
      }).join("; ") + "</li>";
    }
    const noteRu = humanizeDeskReason(st.note || "");
    if (noteRu && noteRu.indexOf("positional") < 0 && /[А-Яа-я]/.test(noteRu)) {
      html += "<li>" + esc(noteRu) + "</li>";
    }
    const srcRu = h1SourceRu(data.h1Source);
    html += "<li>На графике " + (data.barCount || bars.length) + " часовых свечей"
      + (srcRu ? (" · " + esc(srcRu)) : "") + "</li>";
    html += "</ul>";

    const sel = data.instrumentSelect || {};
    const cands = sel.candidates || [];
    if (cands.length) {
      const reasons = cands.map(function (c) { return humanizeDeskReason(c.reason || ""); });
      const allFail = cands.every(function (c) { return !c.pass; });
      const allSame = reasons.length > 1 && reasons.every(function (r) { return r && r === reasons[0]; });
      if (allFail && allSame) {
        html += "<p class='signal-brief-note'><strong>Выбор инструмента.</strong> "
          + esc(reasons[0])
          + " Смотрели все семьи: "
          + cands.map(function (c) { return familyRu(c.family); }).join(", ")
          + ".</p>";
      } else {
        html += "<p class='signal-brief-note'><strong>Выбор инструмента</strong> — в работу берём одну семью:</p>";
        html += "<ul class='signal-brief-list'>";
        cands.forEach(function (c) {
          const why = c.pass ? "проходит" : humanizeDeskReason(c.reason || "нет сетапа");
          html += "<li>" + esc(familyRu(c.family)) + (c.pass ? " — " : " — не берём: ")
            + esc(why) + "</li>";
        });
        html += "</ul>";
      }
    }
    if (data.robotInstrument && data.instrument
        && String(data.robotInstrument).toUpperCase() !== String(data.instrument).toUpperCase()) {
      html += "<p class='signal-brief-note'>На графике " + esc(data.instrument)
        + ", робот #2 выбрал " + esc(data.robotInstrument)
        + " — смените инструмент в селекте, чтобы смотреть его H1.</p>";
    }

    if (hunt.blocksNewArm) {
      html += "<p class='signal-brief-kicker signal-brief-kicker--gold'>Перед входом · фундамент и охота</p>";
      html += "<p class='signal-brief-note'>" + esc(hunt.ru
        || "Новый вход откладываем. Сторону часа не меняем.") + "</p>";
      html += "<p class='signal-brief-note'><strong>Новый вход отложен</strong> — охота против стороны часа, сторону не переворачиваем.</p>";
    } else {
      html += "<p class='signal-brief-note'>" + esc(hunt.ru
        || "Охота молчит. Если сетап валидный — вход по чек-листу.") + "</p>";
    }

    html += "<p class='signal-brief-kicker signal-brief-kicker--robot"
      + (posture === "IN_TRADE" ? " is-in-trade" : "")
      + "'>Робот · " + esc(postureRu) + "</p>";
    html += "<p>" + esc(engineStateRu(plan.state || sit.engineState || data.engineState || "SCAN"));
    const side = (sit.setupLevels && sit.setupLevels.side) || plan.side || sig.side || "";
    if (side === "BUY") html += " · лонг";
    else if (side === "SELL") html += " · шорт";
    html += " · "
      + (sit.liveExecution || data.liveExecution ? "боевой счёт"
        : ((deskScope() === "positional"
            ? data.positionalAutoExecution
            : (sit.autoExecution || data.autoExecution)) ? "песочница (бумага на H1)" : "только сигнал"))
      + ".</p>";
    html += "<p>" + esc(reason || "Робот включён и смотрит час. По чеклисту входа сейчас нет.") + "</p>";
    html += "<p class='signal-brief-note'>" + esc(sit.fillModeRu || "Стоп и тейк — по ходу бара, как у брокера.") + "</p>";
    if (grid && grid.totalQty) {
      html += "<p class='signal-brief-note'>Сетка усреднения 1:1:2:4, всего " + grid.totalQty + " лот."
        + (grid.avg != null ? (" Средняя " + fmtPx(grid.avg) + ".") : "")
        + (plan.stopLoss != null ? (" Стоп " + fmtPx(plan.stopLoss) + ".") : "")
        + (plan.tp1 != null ? (" Тейк " + fmtPx(plan.tp1) + ".") : "")
        + "</p>";
    }
    const fp = sit.fairPaper || {};
    if (fp.open) {
      const os = fp.open.side === "BUY" ? "лонг" : (fp.open.side === "SELL" ? "шорт" : (fp.open.side || ""));
      html += "<p class='signal-brief-note'><strong>В бумаге открыто:</strong> "
        + esc(os) + " по " + fmtPx(fp.open.avg) + ", " + fp.open.qty + " лот."
        + (fp.open.sl != null ? (" Стоп " + fmtPx(fp.open.sl) + ".") : "")
        + (fp.open.candleTrail
          ? " Стоп за закрытой свечой: с нами подтягиваем, против нас стоит."
          : " Пока сетка добирается, стоп в полке объёма.")
        + "</p>";
    } else if (fp.pending) {
      html += "<p class='signal-brief-note'>Лимитки выставлены, ждём исполнение"
        + (fp.pending.side ? (" (" + (fp.pending.side === "BUY" ? "лонг" : "шорт") + ")") : "")
        + ".</p>";
    }
    if (fp.lastClose && fp.lastClose.pnlRub != null) {
      html += "<p class='signal-brief-note'>Последнее закрытие: "
        + esc(({ SL: "стоп", TP: "тейк", TP1: "тейк-1", TP2: "тейк-2", BE: "безубыток", TIME: "по времени" })[fp.lastClose.exitReason]
          || humanizeDeskReason(fp.lastClose.exitReason || "выход"))
        + " · "
        + (fp.lastClose.pnlRub >= 0 ? "+" : "") + Math.round(fp.lastClose.pnlRub) + " ₽.</p>";
    }

    html += "<p class='signal-brief-kicker signal-brief-kicker--gold'>Новости и сессия</p>";
    html += contractExpiryBriefHtml(sit, esc);
    const upcoming = events.filter(isDeskEventUpcoming).slice(0, 3);
    if (upcoming.length) {
      html += "<p>Скоро: " + upcoming.map(function (e) {
        return "<strong>" + esc(e.title) + "</strong> " + esc(e.date || "") + " " + esc(e.time || "");
      }).join("; ") + ".</p>";
    } else {
      html += "<p class='signal-brief-note'>Календарь этого инструмента пуст в горизонте desk — это не Exclusive EIA по нефти.</p>";
    }
    if (sit.sessionBlock) {
      html += "<p class='signal-brief-note'>Сессия: " + esc(humanizeDeskReason(sit.sessionBlock)) + "</p>";
    }
    if (sit.newsDisclaimer) {
      html += "<p class='signal-brief-note'>" + esc(sit.newsDisclaimer) + "</p>";
    }

    if (paperSt && typeof paperSt.todayPnlRub === "number") {
      html += "<p class='signal-brief-kicker signal-brief-kicker--gold'>Счёт бумаги · позиционная</p>"
        + "<p>Сегодня <strong>" + ((paperSt.todayPnlRub >= 0 ? "+" : "")
          + Math.round(paperSt.todayPnlRub).toLocaleString("ru-RU")) + " ₽</strong>"
        + " · " + (paperSt.wins || 0) + "/" + (paperSt.losses || 0)
        + " по этому инструменту / плейбуку.</p>";
    }
    return html;
  }
  function buildOperatorBrief(data) {
    const bars = deskBars(data);
    const last = bars.length ? bars[bars.length - 1] : null;
    const close = last && typeof last.close === "number" ? last.close : null;
    const look1h = bars.slice(Math.max(0, bars.length - 12));
    const lookSession = bars.slice(Math.max(0, bars.length - 80));
    let peak1h = null, trough1h = null;
    look1h.forEach(function (b) {
      if (!b) return;
      if (typeof b.high === "number") peak1h = peak1h == null ? b.high : Math.max(peak1h, b.high);
      if (typeof b.low === "number") trough1h = trough1h == null ? b.low : Math.min(trough1h, b.low);
    });
    let peakS = null, troughS = null;
    lookSession.forEach(function (b) {
      if (!b) return;
      if (typeof b.high === "number") peakS = peakS == null ? b.high : Math.max(peakS, b.high);
      if (typeof b.low === "number") troughS = troughS == null ? b.low : Math.min(troughS, b.low);
    });
    const pts = function (a, b) {
      if (a == null || b == null || !isFinite(a) || !isFinite(b)) return null;
      return Math.round(Math.abs(a - b) / 0.01);
    };
    const signedPts = function (from, to) {
      if (from == null || to == null || !isFinite(from) || !isFinite(to)) return null;
      return Math.round((to - from) / 0.01);
    };
    const nearZone = function (z, px) {
      if (!z || px == null) return false;
      const pad = 0.12;
      return px >= (z.low - pad) && px <= (z.high + pad);
    };
    const relZone = function (z, px, name) {
      if (!z || px == null) return "";
      if (px > z.high + 0.05) return "выше " + name + " (+" + pts(px, z.high) + "п)";
      if (px < z.low - 0.05) return "ниже " + name + " (−" + pts(z.low, px) + "п)";
      return "в полосе " + name + " (" + fmtPx(z.low) + "–" + fmtPx(z.high) + ")";
    };
    const esc = function (t) {
      return String(t == null ? "" : t)
        .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    };
    const minsLabel = function (m) {
      if (m == null || !isFinite(m)) return "";
      const abs = Math.abs(Math.round(m));
      if (abs < 60) return (m >= 0 ? "через " : "") + abs + " мин" + (m < 0 ? " назад" : "");
      const h = Math.floor(abs / 60);
      const mm = abs % 60;
      const core = h + "ч" + (mm ? " " + mm + "м" : "");
      return m >= 0 ? "через " + core : core + " назад";
    };

    const sit = data.situation || {};
    const sig = data.signal || {};
    const plan = data.plan || {};
    const st = data.structure || {};
    const paperSt = (data.paper && data.paper.statement) || {};
    const events = data.events || [];
    const manage = data.manage || {};
    const posture = sit.posture || "SCANNING";
    const state = plan.state || sig.state || data.engineState || sit.engineState || "—";
    const reason = sit.why || data.summary || plan.rationale || sig.summary || "";
    const side = (sit.setupLevels && sit.setupLevels.side)
      || sig.side || plan.side || "NONE";
    const mode = (sit.setupLevels && sit.setupLevels.mode) || plan.mode || sig.mode || "";
    const htf = sit.htf || st.htf || "?";
    const htfSource = sit.htfSource || st.htfSource || "";
    const bias = sit.bias || st.bias || "?";
    const mkt = sit.marketState || st.marketState || "?";
    const tapeLive = (data.barsSource === "tape")
      || String(data.barsSource || "").indexOf("tape") >= 0;
    const liveBroker = !!sit.liveExecution || !!data.liveExecution;
    const autoJ = !!sit.autoExecution || !!data.autoExecution;

    if (deskScope() === "positional" || data.deskScope === "positional") {
      return buildPositionalBrief(data);
    }

    // ——— 1. Рынок сейчас ———
    const marketItems = [];
    if (sit.contractExpiry && sit.contractExpiry.headline) {
      marketItems.push("<strong class='signal-brief-expiry-inline'>"
        + esc(sit.contractExpiry.headline) + "</strong>"
        + (sit.contractExpiry.detail ? (" — " + esc(sit.contractExpiry.detail)) : ""));
    }
    let priceLine = esc(instrumentTitle(data)) + " <strong>" + (close != null ? fmtPx(close) : "—") + "</strong>";
    const topRel = relZone(st.zoneTop, close, "TOP");
    const botRel = relZone(st.zoneBottom, close, "BOT");
    if (topRel && nearZone(st.zoneTop, close)) priceLine += " — " + topRel;
    else if (botRel && nearZone(st.zoneBottom, close)) priceLine += " — " + botRel;
    else if (topRel && botRel) priceLine += " — между зонами: " + topRel + ", " + botRel;
    else if (topRel || botRel) priceLine += " — " + (topRel || botRel);
    marketItems.push(priceLine);

    const impulse = sit.impulse || {};
    if (impulse.active && impulse.headline && !impulse.headline.match(/EIA|API|CL\s/i)) {
      marketItems.push("<strong>" + esc(impulse.headline) + "</strong>");
    }

    const gap = sit.gapFill || {};
    if (gap.present) {
      const gapTitle = gap.title || (gap.filled ? "Ночной гэп — закрыт" : "Ночной (утренний) гэп");
      const gapNote = deMark(gap.note || "");
      if (gapNote) {
        marketItems.push("<strong>" + esc(gapTitle) + "</strong>. " + esc(gapNote));
      } else {
        marketItems.push("<strong>" + esc(gapTitle) + "</strong>.");
      }
    }

    if (sit.dayMovePoints != null) {
      const dm = sit.dayMovePoints;
      let dayLine = "День " + (dm >= 0 ? "+" : "") + dm + "п от открытия сессии";
      if (dm <= -80) dayLine += " — сильный слив, день медвежий";
      else if (dm >= 80) dayLine += " — сильный разгон, день бычий";
      marketItems.push(dayLine);
    }
    const drop1 = (peak1h != null && close != null && peak1h > close + 0.08) ? pts(peak1h, close) : null;
    const rally1 = (trough1h != null && close != null && close > trough1h + 0.08) ? pts(close, trough1h) : null;
    if (drop1 != null && (rally1 == null || drop1 >= rally1)) {
      marketItems.push("За ~1ч срыв с " + fmtPx(peak1h) + " (−" + drop1 + "п)");
    } else if (rally1 != null) {
      marketItems.push("За ~1ч отскок от " + fmtPx(trough1h) + " (+" + rally1 + "п)");
    }
    if (peakS != null && troughS != null && close != null) {
      const span = pts(peakS, troughS);
      if (span != null && span >= 40) {
        marketItems.push("В сессии диапазон ~" + span + "п ("
          + fmtPx(troughS) + "–" + fmtPx(peakS) + ")");
      }
    }
    let mktRu = "в диапазоне";
    if (mkt === "TREND_UP") mktRu = "тренд вверх";
    else if (mkt === "TREND_DOWN") mktRu = "тренд вниз";
    let htfRu = "Час без явного направления";
    if (htf === "UP") htfRu = "Час смотрит вверх";
    else if (htf === "DOWN") htfRu = "Час смотрит вниз";
    marketItems.push("Рынок " + mktRu + ". " + htfRu
      + (htfSource === "H1" ? " (по закрытым часовым)" : (htfSource === "M15" ? " (по 15-минуткам)" : ""))
      + ".");
    marketItems.push("Лента " + (tapeLive ? "живая" : "из архива")
      + ", пять минуток с открытия сессии.");
    const oil = sit.usOil || {};
    if (oil.brief) {
      marketItems.push(esc(String(oil.brief)
        .replace(/\bprev close\b/gi, "вчерашнего закрытия")
        .replace(/\bUP\b/g, "вверх")
        .replace(/\bDOWN\b/g, "вниз")
        .replace(/\bExclusive\b/g, "робот")
        .replace(/\s+/g, " ").trim()));
    }

    let marketHtml = "<p class='signal-brief-kicker signal-brief-kicker--gold'>Рынок сейчас</p>"
      + "<ul class='signal-brief-list'>";
    marketItems.forEach(function (item) {
      marketHtml += "<li>" + item + "</li>";
    });
    marketHtml += "</ul>";

    if (st.zoneTop || st.zoneBottom) {
      marketHtml += "<p class='signal-brief-note'>Зоны дня: ";
      if (st.zoneTop) {
        marketHtml += "<span class='lg-zone'>TOP</span> "
          + fmtPx(st.zoneTop.low) + "–" + fmtPx(st.zoneTop.high);
      }
      if (st.zoneBottom) {
        marketHtml += (st.zoneTop ? ", " : "")
          + "<span class='lg-zone-bot'>BOT</span> "
          + fmtPx(st.zoneBottom.low) + "–" + fmtPx(st.zoneBottom.high);
      }
      marketHtml += ". Хай/лой дня " + fmtPx(st.lookbackHigh) + " / " + fmtPx(st.lookbackLow) + ".";
      if (sit.hiAboveTopPts != null && sit.hiAboveTopPts > 0) {
        marketHtml += " <span class='signal-daylock-gap'>Хай дня выше верхней полки на "
          + sit.hiAboveTopPts + "п — полку дня не двигаем за хаем.</span>";
      }
      if (sit.loBelowBotPts != null && sit.loBelowBotPts > 0) {
        marketHtml += " <span class='signal-daylock-gap'>Лой дня ниже нижней полки на "
          + sit.loBelowBotPts + "п.</span>";
      }
      if (st.previousZeroPoint != null) {
        marketHtml += " Ноль дня " + fmtPx(st.previousZeroPoint)
          + (st.zeroPointBroken ? " — пробит." : " — держится.");
      }
      marketHtml += "</p>";
    }

    if (sit.domBidLots5 != null) {
      const skew = sit.domSkew || 0;
      let pressure = "баланс";
      let pressureCls = "is-flat";
      if (skew > 40) {
        pressure = "давление покупателей";
        pressureCls = "is-bid";
      } else if (skew < -40) {
        pressure = "давление продавцов";
        pressureCls = "is-ask";
      }
      marketHtml += "<p class='signal-dom-pressure " + pressureCls + "' id='signal-dom-pressure'>"
        + "Стакан (топ-5): bid <strong>" + Math.round(sit.domBidLots5)
        + "</strong> / ask <strong>" + Math.round(sit.domAskLots5)
        + "</strong> лотов — <span class='signal-dom-pressure-label'>" + pressure + "</span>.</p>";
    }

    // ——— 2. Робот ———
    const postureTitle = ({
      IN_TRADE: "Робот в сделке",
      WAITING_FILL: "Робот ждёт исполнения",
      WATCHING_ZONE: "Робот смотрит зону",
      NOT_IN_TRADE: "Робот вне сделки",
      SCANNING: "Робот сканирует"
    })[posture] || "Робот";
    const robotInTrade = posture === "IN_TRADE";
    let robotHtml = "<p class='signal-brief-kicker signal-brief-kicker--robot"
      + (robotInTrade ? " is-in-trade" : "")
      + "'>" + esc(postureTitle) + "</p>";

    const channelRu = liveBroker
      ? "живой счёт — осторожно"
      : (autoJ ? "учебный счёт, без реальных денег" : "только подсказки, заявок нет");
    const stateRu = engineStateRu(state);
    robotHtml += "<p>" + esc(stateRu);
    if (side && side !== "NONE") {
      const sideRu = side === "BUY" ? "покупка" : (side === "SELL" ? "продажа" : side);
      const modeRu = mode === "BOUNCE" ? "отбой" : (mode === "RETEST" ? "ретест после пробоя" : mode);
      robotHtml += " · " + esc(sideRu) + (modeRu ? (" (" + esc(modeRu) + ")") : "");
    }
    robotHtml += " · " + channelRu + ".</p>";

    // Senior TF wind — always visible in robot block
    const srcLabel = htfSource === "H1"
      ? "по закрытым часовым"
      : (htfSource === "M15"
        ? "по 15-минуткам"
        : (htfSource === "M5_PROXY"
          ? "час пока без явного хода — смотрим пятиминутки"
          : (htfSource || "старший таймфрейм")));
    let windLine;
    if (htf === "UP") {
      windLine = "Ветер часа: <strong>вверх</strong> (" + esc(srcLabel)
        + ") — покупки с ветром, продажи только после сильного отбоя у верхней полки, меньшим размером.";
    } else if (htf === "DOWN") {
      windLine = "Ветер часа: <strong>вниз</strong> (" + esc(srcLabel)
        + ") — продажи с ветром, покупки только после сильного отбоя у нижней полки, меньшим размером.";
    } else {
      windLine = "Ветер часа: <strong>боковик</strong> (" + esc(srcLabel)
        + ") — приоритет отбоя у верхней и нижней полки дня; ретест — только после пробоя и закрепления.";
    }
    robotHtml += "<p class='signal-brief-note signal-brief-htf'>" + windLine + "</p>";

    if (sit.sessionPhaseRu) {
      robotHtml += "<p class='signal-brief-note'>" + esc(sit.sessionPhaseRu);
      if (sit.shelfLocal) robotHtml += " · смотрим ближнюю полку";
      if (sit.touchQ != null && Number(sit.touchQ) < 3) {
        robotHtml += " · касание полки пока слабое — ждём нормальный отбой";
      } else if (sit.touchQ != null && Number(sit.touchQ) >= 3) {
        robotHtml += " · касание полки качественное";
      }
      if (sit.cluster && sit.cluster.points > 0) {
        robotHtml += " · у полки виден жирный объём";
      }
      robotHtml += ".</p>";
    }

    if (sit.fairPaper && sit.fairPaper.enabled) {
      const fp = sit.fairPaper;
      if (fp.open) {
        const fpSide = fp.open.side === "BUY" ? "покупка" : (fp.open.side === "SELL" ? "продажа" : fp.open.side);
        robotHtml += "<p class='signal-brief-note'><strong>Учебная сделка открыта</strong>: "
          + esc(fpSide)
          + " по " + fmtPx(fp.open.avg) + ", " + fp.open.qty + " лот."
          + " Стоп " + fmtPx(fp.open.sl)
          + (fp.open.tp1 != null ? (", цель " + fmtPx(fp.open.tp1)) : "")
          + ".</p>";
      } else if (fp.pending) {
        const fpSide = fp.pending.side === "BUY" ? "покупка" : (fp.pending.side === "SELL" ? "продажа" : fp.pending.side);
        robotHtml += "<p class='signal-brief-note'>Лимиты выставлены (" + esc(fpSide)
          + ") — ждём касание цены.</p>";
      }
      if (fp.lastClose && fp.lastClose.pnlRub != null) {
        robotHtml += "<p class='signal-brief-note'>Последняя учебная сделка: "
          + (fp.lastClose.pnlRub >= 0 ? "+" : "")
          + Math.round(fp.lastClose.pnlRub) + " ₽.</p>";
      }
    }

    const whyHuman = humanizeDeskReason(reason);
    if (posture === "IN_TRADE") {
      robotHtml += "<p><strong>Почему в сделке:</strong> " + esc(whyHuman) + "</p>";
      if (sit.setupLevels) {
        const lv = sit.setupLevels;
        robotHtml += "<p class='signal-brief-note'>Вход "
          + fmtPx(lv.entry) + " · стоп " + fmtPx(lv.stop)
          + " · цель 1 " + fmtPx(lv.tp1) + " · цель 2 " + fmtPx(lv.tp2)
          + (lv.qty != null ? (" · " + lv.qty + " лот.") : ".") + "</p>";
      }
      if (manage.note) {
        robotHtml += "<p class='signal-brief-note'>" + esc(humanizeDeskReason(manage.note))
          + (manage.movedToBe ? " · стоп уже в безубыток" : "")
          + (manage.trailing ? " · стоп тянется за ценой" : "") + ".</p>";
      }
    } else if (posture === "WAITING_FILL") {
      robotHtml += "<p><strong>Почему ждёт исполнения:</strong> " + esc(whyHuman) + "</p>";
      if (sit.activeLock) {
        const lk = sit.activeLock;
        robotHtml += "<p class='signal-brief-note'>Зона "
          + fmtPx(lk.low) + "–" + fmtPx(lk.high)
          + " — снимем, если цена уйдёт далеко или начнётся новый день.</p>";
      }
      if (sit.setupLevels) {
        const lv = sit.setupLevels;
        robotHtml += "<p class='signal-brief-note'>Сетка: средняя "
          + fmtPx(lv.entry) + " · стоп " + fmtPx(lv.stop)
          + " · цель " + fmtPx(lv.tp1) + ".</p>";
      }
      robotHtml += "<p class='signal-brief-note'>Дальше: дождаться касания лимитов. "
        + "Если цена уйдёт далеко — снимем заявки и будем искать заново.</p>";
    } else {
      robotHtml += "<p><strong>Почему не в сделке:</strong> " + esc(whyHuman) + "</p>";
      const r = String(reason).toUpperCase();
      let next = "Наблюдаем. Вход появится, когда цена подойдёт к полке дня и даст закрытый отбой.";
      if (r.indexOf("MAX FILLS") >= 0 || r.indexOf("MAX SETUPS") >= 0) {
        next = "Дневной лимит сделок исчерпан — новых входов сегодня не будет.";
      } else if (r.indexOf("MAX DAY LOSS") >= 0) {
        next = "Сработал дневной лимит убытка — робот на паузе до завтра.";
      } else if (r.indexOf("EVENT") >= 0) {
        next = "Окно вокруг важного события — ждём, пока пройдёт.";
      } else if (r.indexOf("SESSION") >= 0) {
        next = "Сейчас вне торгового окна — входы откроются в сессии.";
      } else if (r.indexOf("TOUCH") >= 0 || r.indexOf("QUALITY") >= 0) {
        next = "Касание полки слабое — нужен фитиль в зону и закрытие обратно. Тогда это отбой, а не прокол.";
      } else if (r.indexOf("HTF UP") >= 0 && (r.indexOf("ШОРТ") >= 0 || r.indexOf("TOP") >= 0)) {
        next = "Час вверх — продавать рано. Нужна свеча, которая зашла в верхнюю полку и закрылась обратно ниже неё.";
      } else if (r.indexOf("HTF DOWN") >= 0 && (r.indexOf("ЛОНГ") >= 0 || r.indexOf("BOT") >= 0)) {
        next = "Час вниз — покупать рано. Нужна свеча, которая зашла в нижнюю полку и закрылась обратно выше неё.";
      } else if (r.indexOf("MACRO") >= 0 || r.indexOf("KNIFE") >= 0 || r.indexOf("FA/") >= 0) {
        next = "Не ловим нож. Покупка от низа — только после сильного отбоя, не в середине слива.";
      } else if (posture === "WATCHING_ZONE") {
        next = "Зоны дня на месте. Ждём, пока цена придёт к полке и закроется отбоем.";
      } else if (r.indexOf("COOLDOWN") >= 0) {
        next = "Пауза после стопа — не мстим рынку сразу.";
      }
      robotHtml += "<p class='signal-brief-note'>Что делать: " + next + "</p>";
    }

    if (sit.setupsToday != null) {
      robotHtml += "<p class='signal-brief-note'>Сегодня сделок: "
        + sit.setupsToday
        + (sit.maxSetupsPerDay > 0 ? (" из " + sit.maxSetupsPerDay) : "")
        + (sit.realizedDayPnlRub != null
          ? (" · результат "
            + (sit.realizedDayPnlRub >= 0 ? "+" : "")
            + Math.round(sit.realizedDayPnlRub) + " ₽")
          : "")
        + (sit.maxDayLossRub > 0 ? (" · стоп по дню −" + sit.maxDayLossRub + " ₽") : "")
        + ".</p>";
    }

    // ——— 3. Новости / календарь ———
    let newsHtml = "<p class='signal-brief-kicker signal-brief-kicker--gold'>Новости и события</p>";
    newsHtml += contractExpiryBriefHtml(sit, esc);
    const upcoming = events.filter(isDeskEventUpcoming).slice(0, 3);
    const recent = events.filter(function (e) { return e.status === "PAST"; }).slice(0, 2);

    if (!events.length) {
      newsHtml += "<p class='signal-brief-note'>В календаре BR рядом нет EIA/API окон. "
        + "Живой RSS сюда не подмешивается (только event-calendar).</p>";
    } else {
      if (sit.eventBlackout) {
        newsHtml += "<p><strong>Blackout сейчас:</strong> " + esc(sit.eventBlock) + "</p>";
      }
      if (upcoming.length) {
        newsHtml += "<p>Скоро: ";
        newsHtml += upcoming.map(function (e) {
          return "<strong>" + esc(e.title) + "</strong> (" + esc(e.type) + ") "
            + esc(e.date) + " " + esc(e.time) + " MSK — " + minsLabel(e.minutesTo)
            + (e.inBlackout ? " · уже в блоке" : "");
        }).join("; ") + ".</p>";
      }
      if (recent.length) {
        newsHtml += "<p class='signal-brief-note'>Недавно: ";
        newsHtml += recent.map(function (e) {
          return esc(e.title) + " " + minsLabel(e.minutesTo);
        }).join("; ") + ".";
        // price reaction proxy
        if (sit.dayMovePoints != null && Math.abs(sit.dayMovePoints) >= 40) {
          newsHtml += " В цене дня уже виден импульс "
            + (sit.dayMovePoints >= 0 ? "+" : "") + sit.dayMovePoints
            + "п — возможная реакция на фон/событие (прокси, не факт surprise).";
        } else {
          newsHtml += " Явной «реакции дня» по импульсу не видно (день спокойный).";
        }
        newsHtml += "</p>";
      }
      if (!upcoming.length && !recent.length && !sit.eventBlackout) {
        const next = events[0];
        newsHtml += "<p>Ближайшее в горизонте: <strong>" + esc(next.title) + "</strong> "
          + esc(next.date) + " " + esc(next.time) + " — " + minsLabel(next.minutesTo) + ".</p>";
      }
      newsHtml += "<p class='signal-brief-note'>" + esc(sit.newsDisclaimer
        || "FA = календарь + macro-proxy по цене, не лента новостей.") + "</p>";
    }

    if (sit.sessionBlock) {
      newsHtml += "<p class='signal-brief-note'>Сессия: " + esc(sit.sessionBlock) + "</p>";
    } else if (sit.sessionTradable === false) {
      newsHtml += "<p class='signal-brief-note'>Сессия: вне окна входов.</p>";
    } else {
      newsHtml += "<p class='signal-brief-note'>Сессия открыта для входов "
        + esc(sit.sessionOpen || "09:00") + "–" + esc(sit.sessionClose || "23:50")
        + ".</p>";
    }

    // ——— 4. Paper ———
    let paperHtml = "";
    if (paperSt && typeof paperSt.todayPnlRub === "number") {
      const tag = (paperSt.todayPnlRub >= 0 ? "+" : "")
        + Math.round(paperSt.todayPnlRub).toLocaleString("ru-RU") + " ₽";
      const total = ((paperSt.realizedPnlRub >= 0 ? "+" : "")
        + Math.round(paperSt.realizedPnlRub || 0).toLocaleString("ru-RU") + " ₽");
      paperHtml = "<p class='signal-brief-kicker signal-brief-kicker--gold'>Счёт (paper)</p>"
        + "<p>Сегодня <strong>" + tag + "</strong>"
        + " · побед/убытков <strong>" + (paperSt.wins || 0) + "/" + (paperSt.losses || 0) + "</strong>"
        + " · всего на statement <strong>" + total + "</strong>.</p>";
    }

    return commentaryHtml(data) + marketHtml + robotHtml + newsHtml + paperHtml;
  }
  function paintImpulseBanner(impulse) {
    const el = $("signal-impulse-banner");
    if (!el) return;
    if (!impulse || !impulse.active || !impulse.headline) {
      el.hidden = true;
      el.textContent = "";
      return;
    }
    el.hidden = false;
    el.classList.toggle("is-spike", impulse.direction === "SPIKE" || impulse.pattern === "ROCKET");
    el.innerHTML = "<strong>" + escHtml(impulse.headline) + "</strong>"
      + escHtml(impulse.body || "")
      + (impulse.wait ? ("<em>" + escHtml(impulse.wait) + "</em>") : "");
  }
  function refreshImpulseUi(candles) {
    const kit = window.TrinityChartKit;
    if (!kit || typeof kit.classifyImpulseSeries !== "function") return;
    const r = kit.classifyImpulseSeries(candles || [], {
      pointSize: deskPointSize(lastDeskInstrument, 0),
      structure: overlayStructure || {},
      sessionOpenHour: 10,
      positional: deskScope() === "positional",
      getFootprint: function (t) {
        return lookupFootprint(typeof t === "number" ? t : toChartTime(t));
      }
    });
    if (chartTools && typeof chartTools.setImpulseNotes === "function") {
      chartTools.setImpulseNotes(r.notes || {});
    }
    if (!r.latest) {
      paintImpulseBanner({ active: false });
      return;
    }
    paintImpulseBanner({
      active: true,
      direction: r.latest.direction,
      pattern: r.latest.pattern,
      headline: (r.fresh ? "Сейчас: " : "Недавно: ") + r.latest.headline,
      body: r.latest.why || r.latest.banner || r.latest.hover || "",
      wait: r.latest.wait || ""
    });
  }
  function familyRu(code) {
    const u = String(code || "").toUpperCase();
    if (u === "BR") return "нефть (BR)";
    if (u === "RI") return "RTS (RI)";
    if (u === "NG") return "газ (NG)";
    if (u === "SI") return "Si (доллар)";
    if (u === "GD") return "золото (GD)";
    if (u === "MX") return "MIX (MX)";
    return code || "?";
  }
  function h1SourceRu(src) {
    const s = String(src || "").trim();
    if (!s) return "";
    return s
      .replace(/disk-archive/gi, "архив")
      .replace(/broker-h1/gi, "брокерский H1")
      .replace(/iss-h1/gi, "ISS H1")
      .replace(/\biss\b/gi, "ISS")
      .replace(/interval-guard/gi, "проверка шага")
      .replace(/aggregate/gi, "сборка из M5")
      .replace(/\+/g, " + ");
  }
  function engineStateRu(st) {
    const u = String(st || "").toUpperCase();
    if (u === "NO_TRADE") return "без входа";
    if (u === "SCAN") return "сканирует";
    if (u === "ARMED_BOUNCE") return "сетка выставлена";
    if (u === "ZONE_READY") return "зона готова — ждём подход цены";
    if (u === "IN_POSITION" || u === "IN_TRADE") return "в позиции";
    if (u === "ABORT") return "сброс сетапа";
    return st || "";
  }
  function humanizeDeskReason(raw) {
    const s = deMark(raw || "");
    if (!s) return "";
    const u = s.toUpperCase();
    if (u.indexOf("GAP-FILL") >= 0 || u.indexOf("GAP FILL") >= 0 || u.indexOf("НОЧН") >= 0 && u.indexOf("ГЭП") >= 0) {
      if (u.indexOf("AGAINST") >= 0 || u.indexOf("NO BUY") >= 0 || u.indexOf("NO SELL") >= 0
          || u.indexOf("ПРОТИВ ЗАПОЛН") >= 0) {
        return "Открыт ночной (утренний) гэп: сделку против его закрытия сейчас не берём. "
          + "Ждём касание вчерашнего закрытия или ретест после пробоя и закрепления полки.";
      }
      if (u.indexOf("HTF") >= 0 || u.indexOf("ПРОПУЩЕН") >= 0 || u.indexOf("SKIP") >= 0) {
        return "Ночной гэп ещё открыт, но час смотрит не в сторону закрытия — "
          + "отдельную сделку «на закрытие гэпа» пропускаем. Отскок от полок и ретест после пробоя живут по своим правилам.";
      }
      return "Сработал фильтр ночного гэпа — смотри блок «Ночной гэп» в разделе «Рынок сейчас».";
    }
    if (u.indexOf("LATE H1") >= 0 || u.indexOf("OVERNIGHT GAP") >= 0 || u.indexOf("NO NEW ENTRY") >= 0) {
      return "После 16:00 новый вход не ставим — чтобы не ловить гэп на ночь. Если пирамида уже открыта, добор по часовым барам идёт дальше. Свежий вход — завтра до 16:00.";
    }
    if (u.indexOf("POSITIONAL HUNT") >= 0 || u.indexOf("HUNT:") >= 0) {
      if (u.indexOf("СНЯТ") >= 0 || u.indexOf("СТЕН") >= 0 || u.indexOf("СПОФ") >= 0) {
        return "У полки поставили крупную заявку и сняли — ложная стена. Якорем не считаем, новый вход откладываем. Сторону часа не меняем.";
      }
      if (u.indexOf("КИТ") >= 0 || u.indexOf("КРУПНЫЙ ОБЪЁМ") >= 0 || u.indexOf("КРУПНЫЙ ОБЪЕМ") >= 0) {
        return "В ленте крупный объём против стороны часа — кита не догоняем, ждём. Сторону не переворачиваем.";
      }
      return "Перед входом сверили фундамент, толпу в стакане и крупный объём. Сейчас они против стороны часа — новый вход не ставим, сторону не переворачиваем.";
    }
    if (u.indexOf("SIZE=") >= 0 || (u.indexOf("NEED") >= 0 && u.indexOf("4 LOT") >= 0)
        || (u.indexOf("1:1:2:4") >= 0 && u.indexOf("< 4") >= 0)) {
      return "Рукав 1% не тянет сетку 1:1:2:4 (нужно минимум 4 лота). На этом стопе/инструменте объём не набирается — не режем сетку до одного лота.";
    }
    const narrow = s.match(/too narrow\s+([\d.]+)\s*pts\s*<\s*([\d.]+)/i);
    if (narrow) {
      return "Промежуточная полка объёма слишком узкая (" + narrow[1]
        + " п., нужно от " + narrow[2] + " п.) — сетку ставить некуда. Ждём нормальный диапазон, не вход в тик.";
    }
    const wide = s.match(/too wide\s+([\d.]+)\s*pts\s*>\s*([\d.]+)/i);
    if (wide) {
      return "Промежуточная полка слишком широкая (" + wide[1]
        + " п., потолок " + wide[2] + " п.) — это уже не зона входа, а каша. Ждём более собранный объём.";
    }
    if (u.indexOf("ZERO-WIDTH") >= 0 || u.indexOf("ZERO WIDTH") >= 0) {
      return "Полка объёма схлопнулась в одну цену — диапазона нет, сетку не ставим.";
    }
    if (u.indexOf("NO VOLUME SHELF") >= 0 || u.indexOf("SHELF FOR SL") >= 0) {
      return "Нет соседней полки объёма под стоп — стоп в пустоту по плейбуку не ставим.";
    }
    if (u.indexOf("RISK") >= 0 && u.indexOf("BUDGET") >= 0) {
      return "Риск сделки больше выделенного 1% рукава — объём не проходит, вход не берём.";
    }
    if (u.indexOf("FALLBACK RR") >= 0 || (u.indexOf("RR") >= 0 && u.indexOf("MIN") >= 0)) {
      return "До следующего экстремума далеко, запасной тейк даёт слабый RR — сделку пропускаем.";
    }
    if (u.indexOf("BOOK GUARD") >= 0 || u.indexOf("STREAK PAUSE") >= 0) {
      return "Книга сделок на паузе: серия стопов или просадка. Новых входов нет, открытое не режем.";
    }
    if (u.indexOf("COOLDOWN UNTIL") >= 0) {
      const d = s.match(/until\s+(\d{4}-\d{2}-\d{2})/i);
      return "После стопа по этой семье пауза"
        + (d ? (" до " + d[1]) : "") + " — не мстим рынку сразу тем же инструментом.";
    }
    if (u.indexOf("STRUCTURE") >= 0 && u.indexOf("MIN") >= 0) {
      return "Тренд на часе слабоват (лесенка хаёв/лоёв нечёткая) — в ротацию эту семью не берём.";
    }
    if (u.indexOf("BEST SCORE") >= 0 || (u.indexOf("SCORE") >= 0 && u.indexOf("NO ARM") >= 0)) {
      return "Даже лучшая семья не дотянула по качеству сетапа — сегодня без нового входа.";
    }
    if (u.indexOf("OUTRANKED") >= 0) {
      const m = s.match(/outranked by\s+([A-Z]{2})/i);
      return "Сетап есть, но слабее " + familyRu(m ? m[1] : "")
        + " — в работу берём одну семью, эту пропускаем.";
    }
    if (u.indexOf("INSUFFICIENT") >= 0) {
      return "На часе мало структуры: нет явного тренда и/или трёх полок объёма. Сторону не выдумываем, ждём лесенку хаёв и лоёв.";
    }
    if (u.indexOf("EMPTY") >= 0 && u.indexOf("PYRAMID") >= 0) {
      return "Сетка 1:1:2:4 не собралась — входа нет.";
    }
    if (u.indexOf("SL COINCIDES") >= 0) {
      return "Стоп совпал со входом — так не торгуем.";
    }
    if (u === "PASS" || u.indexOf("PASS") === 0) {
      return "проходит — кандидат на вход";
    }
    if (u.indexOf("HH/HL") >= 0 || u.indexOf("LH/LL") >= 0 || u.indexOf("NEED CLEAR H1") >= 0) {
      return "На часе нет явного тренда: ни восходящей лесенки (хаи и лои выше), ни нисходящей. Флэт или пила — в лонг/шорт не лезем.";
    }
    if (u.indexOf("VOLUME RANGE") >= 0 || (u.indexOf("POSITIONAL") >= 0 && u.indexOf("NEED") >= 0)) {
      return "Нужен явный тренд на часе и минимум три полки объёма. Пока этого нет — сетапа нет.";
    }
    const exclusiveDesk = deskScope() !== "positional";
    if (exclusiveDesk && (u.indexOf("TOP+BOT") >= 0 || u.indexOf("IN PLAY") >= 0)) {
      if (u.indexOf("TREND_HI") >= 0 || (u.indexOf("FOCUS") >= 0 && u.indexOf("TOP") >= 0) || u.indexOf("TREND_HI") >= 0) {
        if (u.indexOf("MID-ZONE") >= 0 || u.indexOf("NO BOUNCE") >= 0) {
          return "Робот смотрит обе полки дня. Сейчас цена у верхней — ждём закрытый отбой, не вход с касания.";
        }
        if (u.indexOf("PRICE BELOW TOP") >= 0 || u.indexOf("WAITING RETURN") >= 0) {
          return "Робот смотрит обе полки. Цена под верхней зоной — продажа только если вернётся и отобьётся закрытой свечой.";
        }
        return "Робот смотрит обе полки дня, сейчас внимание на верхней.";
      }
      if (u.indexOf("TREND_LO") >= 0 || u.indexOf("BOT") >= 0) {
        if (u.indexOf("PRICE ABOVE BOT") >= 0 || u.indexOf("WAITING RETURN") >= 0) {
          return "Робот смотрит обе полки. Цена над нижней зоной — покупка только если вернётся и отобьётся закрытой свечой.";
        }
        return "Робот смотрит обе полки дня, сейчас внимание на нижней.";
      }
      return "Робот смотрит обе полки дня — верхнюю и нижнюю.";
    }
    if (exclusiveDesk && u.indexOf("HTF UP") >= 0 && (u.indexOf("ШОРТ") >= 0 || u.indexOf("ВЫНОСА ИЗ ЗОНЫ") >= 0)) {
      return "Час смотрит вверх — продавать от верха можно только после сильного отбоя: свеча закрылась обратно ниже зоны.";
    }
    if (exclusiveDesk && u.indexOf("HTF DOWN") >= 0 && (u.indexOf("ЛОНГ") >= 0 || u.indexOf("ВЫНОСА ИЗ ЗОНЫ") >= 0)) {
      return "Час смотрит вниз — покупать от низа можно только после сильного отбоя: свеча закрылась обратно выше зоны.";
    }
    if (exclusiveDesk && (u.indexOf("WAITING RETEST FROM BELOW") >= 0 || (u.indexOf("TREND_HI") >= 0 && u.indexOf("RETEST") >= 0))) {
      const m = s.match(/(\d+[.,]\d+)\s*[–-]\s*(\d+[.,]\d+)/);
      const zone = m ? ("TOP " + m[1] + "–" + m[2]) : "верхней зоне дня (TOP)";
      return "Цена под верхней зоной (" + zone + "). Ждём возврат снизу к TOP после пробоя низа — без касания зоны новый вход не ставим.";
    }
    if (exclusiveDesk && (u.indexOf("WAITING RETEST FROM ABOVE") >= 0 || (u.indexOf("TREND_LO") >= 0 && u.indexOf("RETEST") >= 0))) {
      const m = s.match(/(\d+[.,]\d+)\s*[–-]\s*(\d+[.,]\d+)/);
      const zone = m ? ("BOT " + m[1] + "–" + m[2]) : "нижней зоне дня (BOT)";
      return "Цена над нижней зоной (" + zone + "). Ждём возврат сверху к BOT после пробоя верха.";
    }
    if (exclusiveDesk && u.indexOf("BOUNCE") >= 0 && u.indexOf("WAITING") >= 0 && u.indexOf("REJECTION") >= 0) {
      return "Цена у зоны — ждём закрытую свечу-отбой (rejection), чтобы подтвердить bounce.";
    }
    if (exclusiveDesk && (u.indexOf("WAITING RETURN TO SHELF") >= 0 || u.indexOf("PRICE ABOVE BOT") >= 0)) {
      return "Цена ещё не в зоне BOT — ждём возврат к полке для входа.";
    }
    if (exclusiveDesk && u.indexOf("PRICE BELOW TOP") >= 0) {
      return "Цена ещё не в зоне TOP — ждём возврат к полке для входа.";
    }
    if (u.indexOf("MAX SETUPS") >= 0 || u.indexOf("MAX FILLS") >= 0) {
      return "Дневной лимит сделок исчерпан — новых входов сегодня не будет.";
    }
    if (u.indexOf("MAX DAY LOSS") >= 0) {
      return "Сработал лимит убытка за день — робот на паузе до завтра.";
    }
    if (u.indexOf("NO VALID PROFILE") >= 0) {
      return exclusiveDesk
        ? "Нет рабочего объёмного профиля на активном уровне — ждём касание TOP/BOT с объёмом."
        : "Нет трёх полок объёма на часе — позиционный вход без них не ставим.";
    }
    if (u.indexOf("MACRO") >= 0 || u.indexOf("KNIFE") >= 0 || u.indexOf("NO BUY") >= 0) {
      return exclusiveDesk
        ? "Фильтр дня/тренда режет покупку против сильного дампа (не ловим нож)."
        : "Сильный дамп против тренда H1 — не ловим нож, ждём полку по стороне часа.";
    }
    if (u.indexOf("SESSION") >= 0) {
      return exclusiveDesk
        ? "Вне торгового окна — новые входы закрыты."
        : "После 16:00 МСК новых входов нет — уже открытую пирамиду не рвём.";
    }
    if (u.indexOf("EVENT") >= 0 || u.indexOf("BLACKOUT") >= 0) {
      return exclusiveDesk
        ? "Календарный blackout вокруг события — ждём окончания окна."
        : "Календарь Exclusive (EIA и т.п.) на позиционную пирамиду не действует.";
    }
    if (u.indexOf("COOLDOWN") >= 0) {
      return "Пауза после стопа (cooldown) — ждём таймер.";
    }
    if (u.indexOf("ZONE_READY") >= 0 || u.indexOf("WAITING") >= 0) {
      return "Зона размечена, сетап ещё не подтверждён — наблюдаем, без входа.";
    }
    // fallback: strip jargon tokens, keep readable chunk
    if (exclusiveDesk) {
      return s
        .replace(/\bTREND_HI\b/gi, "верхняя полка")
        .replace(/\bTREND_LO\b/gi, "нижняя полка")
        .replace(/\bACCUM\b/gi, "накопление")
        .replace(/\bNO_TRADE\b/gi, "без входа")
        .replace(/\bZONE_READY\b/gi, "зона готова")
        .replace(/\bwaiting:\s*/gi, "ждём: ")
        .replace(/\bmid-zone\b/gi, "середина зоны")
        .replace(/\bno bounce confirm yet\b/gi, "отбоя ещё нет")
        .replace(/\bin play\b/gi, "")
        .replace(/\bfocus\b/gi, "смотрим")
        .replace(/\s*\|\s*/g, ". ")
        .replace(/\s+/g, " ")
        .slice(0, 220);
    }
    return s
      .replace(/\bACCUM\b/gi, "накопление")
      .replace(/\bNO_TRADE\b/gi, "без входа")
      .replace(/\bZONE_READY\b/gi, "зона готова")
      .replace(/\s*\|\s*/g, ". ")
      .slice(0, 220);
  }
  function complianceRows(data) {
    const cc = data && data.checklistCompliance;
    if (Array.isArray(cc)) return cc;
    if (cc && Array.isArray(cc.items)) return cc.items;
    return [];
  }
  function renderCompliance(data) {
    const el = $("signal-compliance-meta");
    if (!el) return;
    const rows = complianceRows(data);
    if (!rows || typeof rows.forEach !== "function") return;
    let core = 0, ext = 0;
    rows.forEach(function (r) {
      if (!r) return;
      if (r.status === "IMPLEMENTED") core++;
      else if (r.status === "EXTENSION") ext++;
    });
    const cc = data.checklistCompliance;
    if (cc && !Array.isArray(cc) && cc.implemented != null && cc.total != null) {
      core = Number(cc.implemented) || core;
    }
    const lines = [];
    lines.push("Правила: вход только от полок дня. Против часа — только после сильного отбоя.");
    const fills = data.setupsToday != null ? data.setupsToday : (data.situation && data.situation.setupsToday);
    if (fills != null) {
      const max = (data.situation && data.situation.maxSetupsPerDay) || 0;
      lines.push(max > 0
        ? ("Сделок сегодня: " + fills + " из " + max + ".")
        : ("Сделок сегодня: " + fills + "."));
    }
    const block = data.blockReason || (data.situation && data.situation.why) || data.summary;
    if (block && data.actionable === false) {
      lines.push("Почему без входа: " + humanizeDeskReason(block));
    } else if (data.actionable) {
      lines.push("Есть рабочий сигнал — смотрите сторону и зону выше.");
    }
    el.innerHTML = lines.map(function (line) {
      return "<span class='signal-compliance-line'>" + escHtml(line) + "</span>";
    }).join("");
  }
  function clearLines() {
    if (!candleSeries) return;
    priceLines.forEach(function (l) { try { candleSeries.removePriceLine(l); } catch (_) {} });
    priceLines = [];
  }
  function addLine(price, color, title, opts) {
    if (!candleSeries || !(price > 0)) return;
    const o = opts || {};
    const line = candleSeries.createPriceLine({
      price: price,
      color: color,
      lineWidth: o.lineWidth != null ? o.lineWidth : 1,
      lineStyle: o.lineStyle != null ? o.lineStyle : 2,
      axisLabelVisible: o.axisLabelVisible !== false,
      title: title
    });
    priceLines.push(line);
  }
  function finitePrice(v) {
    return typeof v === "number" && isFinite(v) && v > 0;
  }
  function isPositionalChart() {
    return deskScope() === "positional"
      || (lastDeskSnapshot && lastDeskSnapshot.deskScope === "positional");
  }
  function lastCloseOf(candles) {
    if (!candles || !candles.length) return NaN;
    return Number(candles[candles.length - 1].close);
  }
  function candleSpanOf(candles) {
    let lo = Infinity;
    let hi = -Infinity;
    (candles || []).forEach(function (c) {
      if (!c) return;
      const l = Number(c.low);
      const h = Number(c.high);
      if (l < lo) lo = l;
      if (h > hi) hi = h;
    });
    if (!isFinite(lo) || !isFinite(hi) || hi < lo) return null;
    return { lo: lo, hi: hi };
  }
  function visibleCandleSpan() {
    const all = lastSanitizedCandles;
    if (!all || !all.length) return null;
    if (!chart) return candleSpanOf(all);
    try {
      const vr = chart.timeScale().getVisibleLogicalRange();
      if (!vr) return candleSpanOf(all);
      const from = Math.max(0, Math.floor(vr.from));
      const to = Math.min(all.length - 1, Math.ceil(vr.to));
      if (to < from) return candleSpanOf(all);
      return candleSpanOf(all.slice(from, to + 1)) || candleSpanOf(all);
    } catch (_) {
      return candleSpanOf(all);
    }
  }
  /** Overlay is on-screen if it sits near visible candles — not 2-month GOLD extrema. */
  function nearVisiblePrice(px, candles, slack) {
    if (!finitePrice(px)) return false;
    const span = candleSpanOf(candles) || visibleCandleSpan();
    const last = lastCloseOf(candles);
    if (!span && !finitePrice(last)) return true;
    const ref = finitePrice(last) ? last : (span ? (span.lo + span.hi) / 2 : px);
    const width = span ? Math.max(span.hi - span.lo, Math.abs(ref) * 0.002) : Math.abs(ref) * 0.02;
    const pad = Math.max(width * (slack || 3), Math.abs(ref) * 0.06, 1);
    const lo = span ? span.lo - pad : ref - pad;
    const hi = span ? span.hi + pad : ref + pad;
    return px >= lo && px <= hi;
  }
  function pricesNearlyEqual(a, b, ref) {
    const x = Number(a);
    const y = Number(b);
    if (!finitePrice(x) || !finitePrice(y)) return false;
    const scale = Math.abs(ref || x) || 1;
    return Math.abs(x - y) <= Math.max(scale * 0.0002, 0.05);
  }
  function plausibleLivePx(ref, px) {
    if (!(px > 0)) return false;
    if (!(ref > 0)) return true;
    return Math.abs(px - ref) <= Math.max(Math.abs(ref) * 0.02, 0.5);
  }
  function fitPriceToVisibleCandles() {
    if (!candleSeries || priceScaleLocked) return;
    const span = visibleCandleSpan();
    if (!span) return;
    const pad = Math.max((span.hi - span.lo) * 0.12, Math.abs(span.hi) * 0.003, 0.01);
    const minV = span.lo - pad;
    const maxV = span.hi + pad;
    if (!(maxV > minV)) return;
    try {
      candleSeries.applyOptions({
        autoscaleInfoProvider: function () {
          return { priceRange: { minValue: minV, maxValue: maxV } };
        }
      });
      candleSeries.priceScale().applyOptions({ autoScale: true });
    } catch (_) {}
  }
  /** Desk band title: day-lock vs soft map-only (must match engine shelves, not HI/HIST). */
  function zoneBandTitle(role, z) {
    if (!z) return role;
    const src = String(z.source || "");
    const soft = z.validForEntry === false || /SOFT/i.test(src);
    if (soft) return role + "·карта";
    if (/\+DAY|\bDAY\b|PRIOR/i.test(src)) return role + "·день";
    return role;
  }
  function ensureZoneOverlay() {
    const el = $("signal-chart");
    if (!el) return null;
    let ov = $("signal-zone-overlay");
    if (!ov) {
      ov = document.createElement("div");
      ov.id = "signal-zone-overlay";
      ov.className = "signal-zone-overlay";
      el.appendChild(ov);
    }
    return ov;
  }
  function layoutZoneBands() {
    const ov = ensureZoneOverlay();
    if (!ov || !candleSeries) return;
    const st = overlayStructure || {};
    const positional = isPositionalChart();
    const items = [];
    if (positional) {
      const hvn = st.checklistLevels || [];
      hvn.forEach(function (l, i) {
        if (!l) return;
        const lo = Number(l.rangeLow);
        const hi = Number(l.rangeHigh);
        if (!finitePrice(lo) || !finitePrice(hi)) return;
        if (pricesNearlyEqual(lo, hi, hi)) return;
        const tag = l.role === "ENTRY_ZONE" ? "HVN вход" : (l.role || "HVN");
        items.push({
          z: { low: Math.min(lo, hi), high: Math.max(lo, hi) },
          role: "hvn-" + i,
          kind: "hvn",
          title: tag
        });
      });
    } else {
      if (st.zoneTop) {
        items.push({
          z: st.zoneTop,
          role: "top",
          kind: "top",
          title: zoneBandTitle("TOP", st.zoneTop)
        });
      }
      if (st.zoneBottom) {
        items.push({
          z: st.zoneBottom,
          role: "bot",
          kind: "bot",
          title: zoneBandTitle("BOT", st.zoneBottom)
        });
      }
    }
    const chartEl = $("signal-chart");
    const chartH = chartEl ? (chartEl.clientHeight || 0) : 0;
    const seen = {};
    items.forEach(function (item) {
      if (!finitePrice(item.z.high) || !finitePrice(item.z.low)) return;
      let y1 = candleSeries.priceToCoordinate(item.z.high);
      let y2 = candleSeries.priceToCoordinate(item.z.low);
      if (positional && (y1 == null || y2 == null)) return;
      // Off-scale Exclusive zone (zoom missed morning BOT) — clamp so the band never vanishes
      if (y1 == null && y2 == null && chartH > 0) {
        const mid = (Number(item.z.high) + Number(item.z.low)) / 2;
        const yMid = candleSeries.priceToCoordinate(mid);
        if (yMid == null) {
          const last = candleSeries.priceToCoordinate(
            finitePrice(st.lookbackLow) ? st.lookbackLow : Number(item.z.low)
          );
          if (last == null) return;
          y1 = Math.max(0, Math.min(chartH - 8, last - 4));
          y2 = y1 + 8;
        } else {
          y1 = 0;
          y2 = chartH;
        }
      } else {
        if (y1 == null) y1 = Number(item.z.high) > Number(item.z.low) ? 0 : chartH;
        if (y2 == null) y2 = Number(item.z.high) > Number(item.z.low) ? chartH : 0;
      }
      const top = Math.min(y1, y2);
      const height = Math.max(4, Math.abs(y2 - y1));
      seen[item.role] = true;
      let band = ov.querySelector('.signal-zone-band[data-zone="' + item.role + '"]');
      if (!band) {
        band = document.createElement("div");
        band.className = "signal-zone-band is-" + (item.kind || item.role);
        band.dataset.zone = item.role;
        const label = document.createElement("span");
        label.className = "signal-zone-label";
        band.appendChild(label);
        ov.appendChild(band);
      }
      band.style.top = top + "px";
      band.style.height = height + "px";
      const labelEl = band.querySelector(".signal-zone-label");
      if (labelEl) {
        labelEl.textContent = item.title + " "
          + Number(item.z.low).toFixed(2) + "–" + Number(item.z.high).toFixed(2);
        labelEl.hidden = height < 10;
      }
    });
    Array.prototype.slice.call(ov.querySelectorAll(".signal-zone-band")).forEach(function (el) {
      const role = el.dataset.zone || "";
      if (!seen[role]) el.parentNode.removeChild(el);
    });
  }
  function ensureProfileOverlay() {
    const el = $("signal-chart");
    if (!el) return null;
    let ov = $("signal-profile-overlay");
    if (!ov) {
      ov = document.createElement("div");
      ov.id = "signal-profile-overlay";
      ov.className = "signal-profile-overlay";
      el.appendChild(ov);
    }
    return ov;
  }
  function layoutProfile(levels) {
    const ov = ensureProfileOverlay();
    if (!ov || !candleSeries) return;
    ov.innerHTML = "";
    ov.hidden = !showProfile;
    if (!showProfile || !levels || !levels.length) return;
    const maxW = 72;
    levels.forEach(function (lvl) {
      if (!finitePrice(lvl.price) || !(lvl.volume > 0)) return;
      const y = candleSeries.priceToCoordinate(lvl.price);
      if (y == null) return;
      const bar = document.createElement("div");
      bar.className = "signal-vap-bar";
      const w = Math.max(2, Math.round((lvl.strength || 0) * maxW));
      bar.style.top = (y - 1) + "px";
      bar.style.width = w + "px";
      bar.title = Number(lvl.price).toFixed(2) + " · vol " + Math.round(lvl.volume);
      ov.appendChild(bar);
    });
  }
  function ensureFootprintOverlay() {
    const el = $("signal-chart");
    if (!el) return null;
    let ov = $("signal-footprint-overlay");
    if (!ov) {
      ov = document.createElement("div");
      ov.id = "signal-footprint-overlay";
      ov.className = "signal-footprint-overlay";
      el.appendChild(ov);
    }
    return ov;
  }
  function indexFootprints(fps) {
    footprintByTime = {};
    (fps || []).forEach(function (fb) {
      const t = toChartTime(fb.time);
      if (t != null) footprintByTime[t] = fb;
    });
  }
  function fpSnapTolSec() {
    return lastChartTf === "H1" ? 1800 : 150;
  }
  function lookupFootprint(timeSec) {
    if (timeSec == null) return null;
    if (footprintByTime[timeSec]) return footprintByTime[timeSec];
    let best = null;
    let bestD = Infinity;
    Object.keys(footprintByTime).forEach(function (k) {
      const d = Math.abs(Number(k) - timeSec);
      if (d < bestD) {
        bestD = d;
        best = footprintByTime[k];
      }
    });
    return bestD <= fpSnapTolSec() ? best : null;
  }
  function barTimesSorted() {
    const out = [];
    (lastBarsRaw || []).forEach(function (b) {
      const t = toChartTime(b.time);
      if (t != null) out.push(t);
    });
    out.sort(function (a, b) { return a - b; });
    return out;
  }
  function nearestBarTime(timeSec) {
    const times = barTimesSorted();
    if (!times.length || timeSec == null) return timeSec;
    let best = times[0];
    let bestD = Math.abs(times[0] - timeSec);
    for (let i = 1; i < times.length; i++) {
      const d = Math.abs(times[i] - timeSec);
      if (d < bestD) {
        bestD = d;
        best = times[i];
      }
    }
    return best;
  }
  function timesInFpRange(fromSec, toSec) {
    if (fromSec == null || toSec == null) return [];
    const a = Math.min(fromSec, toSec);
    const b = Math.max(fromSec, toSec);
    return barTimesSorted().filter(function (t) { return t >= a && t <= b; });
  }
  function applyFpRange(fromSec, toSec) {
    const a = nearestBarTime(fromSec);
    const b = nearestBarTime(toSec);
    fpRangeFrom = Math.min(a, b);
    fpRangeTo = Math.max(a, b);
    fpPinned = timesInFpRange(fpRangeFrom, fpRangeTo);
    if (fpPinned.length > FP_PIN_MAX) {
      // Keep evenly spaced sample so overlay stays readable.
      const step = Math.ceil(fpPinned.length / FP_PIN_MAX);
      const kept = [];
      for (let i = 0; i < fpPinned.length; i += step) kept.push(fpPinned[i]);
      const last = fpPinned[fpPinned.length - 1];
      if (kept[kept.length - 1] !== last) kept.push(last);
      fpPinned = kept.slice(0, FP_PIN_MAX);
    }
    fpAnchor = null;
    fpHoverTime = null;
    fpSelected = true;
    layoutFootprint();
  }
  function setToolPressed(id, on) {
    const btn = $(id);
    if (!btn) return;
    btn.classList.toggle("is-active", !!on);
    btn.setAttribute("aria-pressed", on ? "true" : "false");
  }
  function syncChartCursor() {
    const el = $("signal-chart");
    if (el) el.classList.toggle("is-fp-tool", fpToolActive);
  }
  function toggleFpTool() {
    fpToolActive = !fpToolActive;
    if (!fpToolActive) {
      fpHoverTime = null;
      fpAnchor = null;
      fpDragEnd = null;
    }
    setToolPressed("tool-footprint", fpToolActive);
    syncChartCursor();
    layoutFootprint();
    if (fpToolActive && chartNav && typeof chartNav.setMeasureMode === "function") {
      chartNav.setMeasureMode(false);
      setToolPressed("tool-measure", false);
    }
  }
  function clearFpPins() {
    fpPinned = [];
    fpHoverTime = null;
    fpAnchor = null;
    fpRangeFrom = null;
    fpRangeTo = null;
    fpSelected = false;
    fpDragEnd = null;
    layoutFootprint();
  }
  function bindFpHostHandlers() {
    const el = $("signal-chart");
    if (!el || fpHostBound) return;
    fpHostBound = true;
    el.addEventListener("pointerdown", function (ev) {
      if (!fpSelected || fpRangeFrom == null || fpRangeTo == null || !chart) return;
      if (ev.button != null && ev.button !== 0) return;
      if (fpToolActive) return; // drawing uses clicks
      const rect = el.getBoundingClientRect();
      const x = ev.clientX - rect.left;
      const xs = fpRangeXs();
      if (!xs) return;
      if (Math.abs(x - xs.left) <= 8) fpDragEnd = "from";
      else if (Math.abs(x - xs.right) <= 8) fpDragEnd = "to";
      else return;
      try { el.setPointerCapture(ev.pointerId); } catch (_) {}
      ev.preventDefault();
      ev.stopPropagation();
    });
    el.addEventListener("pointermove", function (ev) {
      if (!fpDragEnd || !chart) return;
      const rect = el.getBoundingClientRect();
      const x = ev.clientX - rect.left;
      let t = null;
      try { t = chart.timeScale().coordinateToTime(x); } catch (_) {}
      if (typeof t !== "number") return;
      t = nearestBarTime(t);
      if (fpDragEnd === "from") fpRangeFrom = t;
      else fpRangeTo = t;
      applyFpRange(fpRangeFrom, fpRangeTo);
      ev.preventDefault();
    });
    el.addEventListener("pointerup", function (ev) {
      if (!fpDragEnd) return;
      fpDragEnd = null;
      try { el.releasePointerCapture(ev.pointerId); } catch (_) {}
    });
  }
  function fpRangeXs() {
    if (fpRangeFrom == null || fpRangeTo == null || !chart) return null;
    let x1 = null;
    let x2 = null;
    try {
      x1 = chart.timeScale().timeToCoordinate(fpRangeFrom);
      x2 = chart.timeScale().timeToCoordinate(fpRangeTo);
    } catch (_) {}
    if (x1 == null || x2 == null) return null;
    let barW = 8;
    try {
      const spacing = chart.timeScale().options().barSpacing;
      if (spacing > 0) barW = spacing;
    } catch (_) {}
    return {
      left: Math.min(x1, x2) - barW * 0.35,
      right: Math.max(x1, x2) + barW * 0.55
    };
  }
  function emaSeries(values, period) {
    const out = new Array(values.length).fill(null);
    const k = 2 / (period + 1);
    let prev = null;
    for (let i = 0; i < values.length; i++) {
      const v = values[i];
      if (v == null || !isFinite(v)) continue;
      if (prev == null) {
        if (i < period - 1) continue;
        let s = 0;
        let ok = true;
        for (let j = i - period + 1; j <= i; j++) {
          if (values[j] == null || !isFinite(values[j])) { ok = false; break; }
          s += values[j];
        }
        if (!ok) continue;
        prev = s / period;
      } else {
        prev = v * k + prev * (1 - k);
      }
      out[i] = prev;
    }
    return out;
  }
  function computeMacd(bars) {
                    const closes = (bars || []).map(function (b) { return b.close; });
                    const times = (bars || []).map(function (b) { return toChartTime(b.time); });
                    const emaFast = emaSeries(closes, MACD_FAST);
                    const emaSlow = emaSeries(closes, MACD_SLOW);
                    const macd = closes.map(function (_, i) {
                      if (emaFast[i] == null || emaSlow[i] == null) return null;
                      return emaFast[i] - emaSlow[i];
                    });
                    const signal = emaSeries(macd, MACD_SIGNAL);
                    const hist = [];
                    const line = [];
                    const sig = [];
                    for (let i = 0; i < closes.length; i++) {
                      if (times[i] == null) continue;
                      if (macd[i] != null && signal[i] != null) {
                        const h = macd[i] - signal[i];
                        hist.push({
                          time: times[i],
                          value: h,
                          color: h >= 0 ? "rgba(22, 163, 74, 0.55)" : "rgba(220, 38, 38, 0.5)"
                        });
                        line.push({ time: times[i], value: macd[i] });
                        sig.push({ time: times[i], value: signal[i] });
                      } else {
                        hist.push({ time: times[i], value: 0, color: "rgba(0,0,0,0)" });
                        line.push({ time: times[i] });
                        sig.push({ time: times[i] });
                      }
                    }
                    return { hist: hist, line: line, signal: sig, macdRaw: macd, times: times, closes: closes };
                  }
  function findDivergences(bars, macdRaw, times) {
    const markers = [];
    const n = bars.length;
    const pivot = 3;
    const highs = [];
    const lows = [];
    for (let i = pivot; i < n - pivot; i++) {
      let isHi = true, isLo = true;
      for (let k = 1; k <= pivot; k++) {
        if (!(bars[i].high > bars[i - k].high && bars[i].high >= bars[i + k].high)) isHi = false;
        if (!(bars[i].low < bars[i - k].low && bars[i].low <= bars[i + k].low)) isLo = false;
      }
      if (isHi && macdRaw[i] != null) highs.push(i);
      if (isLo && macdRaw[i] != null) lows.push(i);
    }
    for (let j = 1; j < highs.length; j++) {
      const a = highs[j - 1], b = highs[j];
      if (b - a < 5 || b - a > 80) continue;
      if (bars[b].high > bars[a].high && macdRaw[b] < macdRaw[a] && times[b] != null) {
        markers.push({
          time: times[b],
          position: "aboveBar",
          color: "#c4a35a",
          shape: "arrowDown",
          text: "Bear Div"
        });
      }
    }
    for (let j = 1; j < lows.length; j++) {
      const a = lows[j - 1], b = lows[j];
      if (b - a < 5 || b - a > 80) continue;
      if (bars[b].low < bars[a].low && macdRaw[b] > macdRaw[a] && times[b] != null) {
        markers.push({
          time: times[b],
          position: "belowBar",
          color: "#16a34a",
          shape: "arrowUp",
          text: "Bull Div"
        });
      }
    }
    return markers.slice(-12);
  }
  function timelineMarkersFromDesk(data) {
    const raw = (data && data.chartMarkers) || [];
    const out = [];
    for (let i = 0; i < raw.length; i++) {
      const m = raw[i];
      if (!m) continue;
      const t = toChartTime(m.time);
      if (t == null) continue;
      out.push({
        time: t,
        position: m.position || "belowBar",
        color: m.color || "#6366f1",
        shape: m.shape || "circle",
        text: m.text || m.kind || "•",
        _kind: m.kind || "",
        _title: m.title || "",
        _detail: m.detail || ""
      });
    }
    out.sort(function (a, b) { return a.time - b.time; });
    return out.slice(-48);
  }
  function ensureTimelineOverlay() {
    const host = document.querySelector(".signal-chart-main") || $("signal-chart");
    if (!host) return null;
    let ov = $("signal-timeline-rail");
    if (!ov) {
      ov = document.createElement("div");
      ov.id = "signal-timeline-rail";
      ov.className = "signal-timeline-rail";
      ov.setAttribute("aria-hidden", "true");
      host.appendChild(ov);
    }
    return ov;
  }
  function layoutTimelineMarkers() {
    const ov = ensureTimelineOverlay();
    if (!ov || !chart) return;
    ov.innerHTML = "";
    const chartEl = $("signal-chart");
    const chartW = chartEl ? chartEl.clientWidth : 0;
    const ts = chart.timeScale();
    const items = lastTimelineMarkers || [];
    if (!items.length) {
      ov.hidden = true;
      return;
    }
    ov.hidden = false;
    items.forEach(function (m) {
      if (!m || m.time == null) return;
      let x = null;
      try { x = ts.timeToCoordinate(m.time); } catch (_) {}
      if (x == null) {
        const t = nearestBarTime(m.time);
        if (t != null) {
          try { x = ts.timeToCoordinate(t); } catch (_) {}
        }
      }
      if (x == null || x < -16 || x > chartW + 16) return;
      const chip = document.createElement("div");
      chip.className = "signal-timeline-chip";
      chip.style.left = Math.round(x) + "px";
      const title = m._title || m.text || m._kind || "";
      const detail = m._detail || "";
      chip.title = detail ? (title + "\n" + detail) : title;
      const dot = document.createElement("span");
      dot.className = "signal-timeline-dot";
      dot.style.background = m.color || "#6366f1";
      const letter = document.createElement("span");
      letter.className = "signal-timeline-letter";
      letter.textContent = (m.text || m._kind || "•").slice(0, 3);
      dot.appendChild(letter);
      chip.appendChild(dot);
      ov.appendChild(chip);
    });
  }
  function lwMarker(m) {
    if (!m || m.time == null) return null;
    const t = nearestBarTime(m.time);
    if (t == null) return null;
    return {
      time: t,
      position: m.position || "belowBar",
      color: m.color || "#6366f1",
      shape: m.shape || "circle",
      text: m.text || ""
    };
  }
  function mergeChartMarkers(groups) {
    // LW 3.x: one marker per bar time — timeline beats MACD div beats live signal.
    const byTime = {};
    const rank = { signal: 1, div: 2, timeline: 3 };
    groups.forEach(function (g) {
      const r = rank[g.kind] || 0;
      (g.items || []).forEach(function (raw) {
        const m = lwMarker(raw);
        if (!m) return;
        const prev = byTime[m.time];
        if (!prev || r >= prev._rank) {
          m._rank = r;
          byTime[m.time] = m;
        }
      });
    });
    return Object.keys(byTime).sort(function (a, b) { return Number(a) - Number(b); })
      .map(function (k) {
        const m = byTime[k];
        return { time: m.time, position: m.position, color: m.color, shape: m.shape, text: m.text };
      })
      .slice(-64);
  }
  function applyCombinedMarkers() {
    if (!candleSeries) return;
    // Timeline (roll/gap/calendar) → top-edge overlay; candles keep signal + MACD div only.
    const all = mergeChartMarkers([
      { kind: "signal", items: lastSignalMarkers || [] },
      { kind: "div", items: showMacd ? (lastDivMarkers || []) : [] }
    ]);
    try { candleSeries.setMarkers(all); } catch (err) {
      if (typeof console !== "undefined") console.warn("setMarkers", err);
    }
    layoutTimelineMarkers();
  }
  let macdRangeSyncing = false;
                  function syncMacdTimeScale() {
                    if (!chart || !macdChart || !showMacd || macdRangeSyncing) return;
                    macdRangeSyncing = true;
                    try {
                      try {
                        macdChart.timeScale().applyOptions({
                          rightOffset: currentRightOffset(),
                          barSpacing: (chart && chart.timeScale().options().barSpacing) || 8
                        });
                      } catch (_) {}
                      const lr = chart.timeScale().getVisibleLogicalRange();
                      if (lr) {
                        macdChart.timeScale().setVisibleLogicalRange(lr);
                      } else {
                        const tr = chart.timeScale().getVisibleRange();
                        if (tr && tr.from != null && tr.to != null) {
                          macdChart.timeScale().setVisibleRange(tr);
                        }
                      }
                    } catch (_) {
                    } finally {
                      macdRangeSyncing = false;
                    }
                  }
                  function ensureMacdChart() {
                    const wrap = $("signal-macd-wrap");
                    const el = $("signal-macd");
                    if (!wrap || !el) return;
                    wrap.hidden = !showMacd;
                    if (!showMacd) return;
                    if (macdChart) {
                      macdChart.applyOptions({ width: el.clientWidth || ($("signal-chart") || {}).clientWidth || 600 });
                      syncMacdTimeScale();
                      bindChartSlavePanes();
                      return;
                    }
                    macdChart = LightweightCharts.createChart(el, {
                      width: el.clientWidth || ($("signal-chart") && $("signal-chart").clientWidth) || 600,
                      height: 120,
                      layout: { backgroundColor: "#ffffff", textColor: "#1a2228" },
                      grid: { vertLines: { color: "#eef1f3" }, horzLines: { color: "#eef1f3" } },
                      rightPriceScale: { borderColor: "#d5dde2" },
                      timeScale: {
                        borderColor: "#d5dde2",
                        visible: false,
                        rightOffset: currentRightOffset(),
                        barSpacing: 8,
                        lockVisibleTimeRangeOnResize: true
                      },
                      handleScroll: false,
                      handleScale: false,
                      crosshair: {
                        mode: (window.LightweightCharts && LightweightCharts.CrosshairMode
                          ? LightweightCharts.CrosshairMode.Normal : 0)
                      }
                    });
                    macdHistSeries = macdChart.addHistogramSeries({
                      priceFormat: { type: "price", precision: 4, minMove: 0.0001 },
                      priceScaleId: "right"
                    });
                    macdLineSeries = macdChart.addLineSeries({
                      color: "#2563eb", lineWidth: 1, title: "MACD"
                    });
                    macdSignalSeries = macdChart.addLineSeries({
                      color: "#c4a35a", lineWidth: 1, title: "Signal"
                    });
                    if (chart && !macdSynced) {
                      macdSynced = true;
                      chart.timeScale().subscribeVisibleLogicalRangeChange(function () {
                        syncMacdTimeScale();
                      });
                    }
                    bindChartSlavePanes();
                  }
                  function updateMacd(bars) {
                    if (!showMacd) {
                      lastDivMarkers = [];
                      lastDivMarkersKey = "";
                      applyCombinedMarkers();
                      return;
                    }
                    ensureMacdChart();
                    if (!macdHistSeries || !bars || !bars.length) return;
                    const m = computeMacd(bars);
                    try { macdHistSeries.setData(m.hist); } catch (_) {}
                    try { macdLineSeries.setData(m.line); } catch (_) {}
                    try { macdSignalSeries.setData(m.signal); } catch (_) {}
                    requestAnimationFrame(function () {
                      syncMacdTimeScale();
                      requestAnimationFrame(syncMacdTimeScale);
                    });
                    const markers = findDivergences(bars, m.macdRaw, m.times);
                    const key = markers.map(function (x) { return x.time + x.text; }).join("|");
                    if (key !== lastDivMarkersKey) {
                      lastDivMarkersKey = key;
                      lastDivMarkers = markers;
                      applyCombinedMarkers();
                    }
                  }
  function layoutFootprint() {
    const ov = ensureFootprintOverlay();
    if (!ov || !candleSeries || !chart) return;
    ov.innerHTML = "";
    bindFpHostHandlers();

    // Range highlight (preview while drawing, or committed selection).
    let rangeA = fpAnchor;
    let rangeB = fpHoverTime;
    if (rangeA == null && fpRangeFrom != null && fpRangeTo != null) {
      rangeA = fpRangeFrom;
      rangeB = fpRangeTo;
    }
    if (rangeA != null && rangeB != null) {
      const xs = (function () {
        let x1 = null;
        let x2 = null;
        try {
          x1 = chart.timeScale().timeToCoordinate(Math.min(rangeA, rangeB));
          x2 = chart.timeScale().timeToCoordinate(Math.max(rangeA, rangeB));
        } catch (_) {}
        if (x1 == null || x2 == null) return null;
        let barW = 8;
        try {
          const spacing = chart.timeScale().options().barSpacing;
          if (spacing > 0) barW = spacing;
        } catch (_) {}
        return {
          left: Math.min(x1, x2) - barW * 0.35,
          right: Math.max(x1, x2) + barW * 0.55
        };
      })();
      if (xs) {
        const band = document.createElement("div");
        band.className = "signal-fp-range" + (fpAnchor != null ? " is-preview" : "")
          + (fpSelected && fpAnchor == null ? " is-selected" : "");
        band.style.left = xs.left + "px";
        band.style.width = Math.max(2, xs.right - xs.left) + "px";
        ov.appendChild(band);
        if (fpSelected && fpAnchor == null) {
          ["from", "to"].forEach(function (end) {
            const h = document.createElement("div");
            h.className = "signal-fp-handle";
            h.dataset.end = end;
            h.style.left = ((end === "from" ? xs.left : xs.right) - 4) + "px";
            ov.appendChild(h);
          });
        }
      }
    } else if (fpAnchor != null) {
      try {
        const ax = chart.timeScale().timeToCoordinate(fpAnchor);
        if (ax != null) {
          const edge = document.createElement("div");
          edge.className = "signal-fp-range-edge";
          edge.style.left = ax + "px";
          ov.appendChild(edge);
        }
      } catch (_) {}
    }

    const times = {};
    fpPinned.forEach(function (t) { times[t] = "pin"; });
    if (fpToolActive && fpHoverTime != null && !times[fpHoverTime] && fpAnchor == null) {
      times[fpHoverTime] = "hover";
    }
    // While drawing preview, also show columns for bars in the tentative range.
    if (fpAnchor != null && fpHoverTime != null) {
      timesInFpRange(fpAnchor, fpHoverTime).forEach(function (t) {
        if (!times[t]) times[t] = "preview";
      });
    }
    const keys = Object.keys(times).map(Number).sort(function (a, b) { return a - b; });
    if (!keys.length && !ov.children.length) {
      ov.hidden = true;
      return;
    }
    ov.hidden = false;
    const ts = chart.timeScale();
    const fpCount = Object.keys(footprintByTime).length;
    keys.forEach(function (t) {
      const fb = lookupFootprint(t);
      const x = ts.timeToCoordinate(t);
      if (x == null) return;
      const col = document.createElement("div");
      col.className = "signal-fp-col"
        + (times[t] === "hover" ? " is-hover" : "")
        + (times[t] === "pin" ? " is-pinned" : "")
        + (times[t] === "preview" ? " is-preview" : "");
      col.style.left = (x - 22) + "px";
      if (!fb || !(fb.levels || []).length) {
        const miss = document.createElement("div");
        miss.className = "signal-fp-miss";
        miss.textContent = fpCount ? "нет уровней" : "нет ленты";
        miss.title = fpCount
          ? "Лента есть, но для этой свечи уровней нет"
          : "В desk нет footprint (лента пуста или не подгружена)";
        col.appendChild(miss);
        ov.appendChild(col);
        return;
      }
      const levels = (fb.levels || []).slice(0, 18);
      levels.forEach(function (lv) {
        if (!finitePrice(lv.price)) return;
        const y = candleSeries.priceToCoordinate(lv.price);
        if (y == null) return;
        const cell = document.createElement("div");
        cell.className = "signal-fp-cell";
        cell.style.top = (y - 6) + "px";
        const buy = lv.buy || 0;
        const sell = lv.sell || 0;
        cell.title = Number(lv.price).toFixed(2) + " buy " + buy + " × sell " + sell;
        cell.innerHTML = "<span class=\"b\">" + buy + "</span>"
          + "<span class=\"x\">×</span>"
          + "<span class=\"s\">" + sell + "</span>";
        col.appendChild(cell);
      });
      ov.appendChild(col);
    });
  }
  let lastChartSize = { w: 0, h: 0 };
  let overlayRaf = 0;
  function layoutMarketOverlaysNow() {
    layoutZoneBands();
    layoutProfile(lastProfile);
    layoutTimelineMarkers();
    if (chartTools) {
      chartTools.layoutStretchedVap();
      if (typeof chartTools.layoutTrendLines === "function") chartTools.layoutTrendLines();
    }
    if (fpToolActive || fpPinned.length || fpRangeFrom != null || fpAnchor != null) {
      layoutFootprint();
    }
  }
  function layoutMarketOverlays() {
    if (overlayRaf) return;
    overlayRaf = requestAnimationFrame(function () {
      overlayRaf = 0;
      layoutMarketOverlaysNow();
    });
  }
  function overlayKey(plan, sig, st, open) {
    const zt = st && st.zoneTop ? (st.zoneTop.low + "/" + st.zoneTop.high) : "";
    const zb = st && st.zoneBottom ? (st.zoneBottom.low + "/" + st.zoneBottom.high) : "";
    const lv = (st && st.checklistLevels || []).map(function (l) {
      return l ? (l.role + ":" + l.rangeLow + "-" + l.rangeHigh) : "";
    }).join(",");
    return [
      st && st.lookbackHigh, st && st.lookbackLow,
      st && st.historicalHigh, st && st.historicalLow, st && st.previousZeroPoint,
      zt, zb, lv,
      plan && plan.side, plan && plan.entry, plan && plan.stopLoss,
      plan && plan.tp1, plan && plan.actionable, sig && sig.side,
      open && open.avg, open && open.sl, open && open.tp1, open && open.tp2,
      open && open.tp1Done, open && open.qty, open && open.side
    ].join("|");
  }
  function applyOverlays(plan, sig, candles, structure, open) {
    const st = structure || {};
    overlayStructure = st;
    const key = overlayKey(plan, sig, st, open);
    if (key !== lastOverlayKey) {
      lastOverlayKey = key;
      clearLines();
      const positionalChart = isPositionalChart();
      // §3 historical — dashed gray. Skip on positional (2-month GOLD extrema crush the pane).
      // Exclusive: only if near visible candles — never the tradable TOP/BOT shelf.
      if (!positionalChart && finitePrice(st.historicalHigh)
          && st.historicalHigh !== st.lookbackHigh
          && nearVisiblePrice(st.historicalHigh, candles)) {
        addLine(st.historicalHigh, "#94a3b8", "HIST↑·серия", { lineWidth: 1, lineStyle: 2 });
      }
      if (!positionalChart && finitePrice(st.historicalLow)
          && st.historicalLow !== st.lookbackLow
          && nearVisiblePrice(st.historicalLow, candles)) {
        addLine(st.historicalLow, "#94a3b8", "HIST↓·серия", { lineWidth: 1, lineStyle: 2 });
      }
      // §4 Exclusive ZERO — playbook #2 has no session zero point
      if (!positionalChart && finitePrice(st.previousZeroPoint) && nearVisiblePrice(st.previousZeroPoint, candles)) {
        addLine(st.previousZeroPoint, "#ca8a04", "ZERO", { lineWidth: 1, lineStyle: 2 });
      }
      // §5 Exclusive: day HI/LO. Playbook #2 has no daily min/max overlay — only HVN.
      if (!positionalChart && finitePrice(st.lookbackHigh)) {
        addLine(st.lookbackHigh, HI_LO_COLOR, "HI", { lineWidth: 2, lineStyle: 0 });
      }
      if (!positionalChart && finitePrice(st.lookbackLow)) {
        addLine(st.lookbackLow, HI_LO_COLOR, "LO", { lineWidth: 2, lineStyle: 0 });
      }
      // Zone edges as thin purple guides (fill = HTML band)
      if (positionalChart) {
        const hvn = st.checklistLevels || [];
        const drawn = [];
        hvn.forEach(function (l) {
          if (!l) return;
          const lo = Number(l.rangeLow);
          const hi = Number(l.rangeHigh);
          const tag = l.role === "ENTRY_ZONE" ? "HVN вход" : (l.role || "HVN");
          if (finitePrice(hi) && finitePrice(lo) && pricesNearlyEqual(lo, hi, hi)) {
            if (nearVisiblePrice(hi, candles)) {
              addLine((lo + hi) / 2, ZONE_EDGE, tag, { lineWidth: 1, lineStyle: 0 });
            }
            drawn.push((lo + hi) / 2);
            return;
          }
          if (finitePrice(hi) && nearVisiblePrice(hi, candles)) {
            addLine(hi, ZONE_EDGE, tag + "↑", { lineWidth: 1, lineStyle: 0 });
            drawn.push(hi);
          }
          if (finitePrice(lo) && !pricesNearlyEqual(lo, hi, hi) && nearVisiblePrice(lo, candles)) {
            addLine(lo, ZONE_EDGE, tag + "↓", { lineWidth: 1, lineStyle: 0 });
            drawn.push(lo);
          }
        });
        if (plan && plan.range) {
          const already = function (px) {
            return drawn.some(function (d) { return pricesNearlyEqual(d, px, px); });
          };
          if (finitePrice(plan.range.high) && !already(plan.range.high)
              && nearVisiblePrice(plan.range.high, candles)) {
            addLine(plan.range.high, ZONE_EDGE, "вход↑", { lineWidth: 1, lineStyle: 2 });
          }
          if (finitePrice(plan.range.low) && !already(plan.range.low)
              && !pricesNearlyEqual(plan.range.low, plan.range.high, plan.range.high)
              && nearVisiblePrice(plan.range.low, candles)) {
            addLine(plan.range.low, ZONE_EDGE, "вход↓", { lineWidth: 1, lineStyle: 2 });
          }
        }
      } else {
      if (st.zoneTop) {
        if (finitePrice(st.zoneTop.high)) {
          addLine(st.zoneTop.high, ZONE_EDGE, "TOP↑", { lineWidth: 1, lineStyle: 0 });
        }
        if (finitePrice(st.zoneTop.low)) {
          addLine(st.zoneTop.low, ZONE_EDGE, "TOP↓", { lineWidth: 1, lineStyle: 0 });
        }
      }
      if (st.zoneBottom) {
        if (finitePrice(st.zoneBottom.high)) {
          addLine(st.zoneBottom.high, ZONE_EDGE, "BOT↑", { lineWidth: 1, lineStyle: 0 });
        }
        if (finitePrice(st.zoneBottom.low)) {
          addLine(st.zoneBottom.low, ZONE_EDGE, "BOT↓", { lineWidth: 1, lineStyle: 0 });
        }
      }
      }
      // Working trade: actual fill levels. Flat: armed plan — label as план so it
      // is not mistaken for the last closed BUY/SELL.
      const working = open && (Number(open.avg) > 0 || Number(open.qty) > 0);
      if (working || (plan && plan.actionable)) {
        const buy = working
          ? open.side === "BUY"
          : (plan.buy === true || (sig && sig.side === "BUY"));
        const entry = working ? Number(open.avg) : (plan.entry || (plan.grid && plan.grid.avg));
        const sl = working ? Number(open.sl) : plan.stopLoss;
        const tp1 = working
          ? (open.tp1Done ? NaN : Number(open.tp1))
          : plan.tp1;
        const tp2 = working ? Number(open.tp2) : plan.tp2;
        const prefix = working ? "" : "план ";
        const sideTag = buy ? "BUY" : "SELL";
        if (finitePrice(entry)) {
          addLine(entry, "#0f766e", working ? ("AVG " + sideTag) : (prefix + sideTag), { lineWidth: 2, lineStyle: 0 });
        }
        if (finitePrice(sl)) addLine(sl, "#b91c1c", prefix + "SL", { lineWidth: 1, lineStyle: 2 });
        if (finitePrice(tp1)) addLine(tp1, "#16a34a", prefix + "TP1", { lineWidth: 1, lineStyle: 2 });
        if (finitePrice(tp2)) addLine(tp2, "#15803d", prefix + "TP2", { lineWidth: 1, lineStyle: 2 });
      }
      if ((working || (plan && plan.actionable)) && candles && candles.length) {
        const last = candles[candles.length - 1];
        const buy = working
          ? open.side === "BUY"
          : (plan.buy === true || (sig && sig.side === "BUY"));
        lastSignalMarkers = [{
          time: last.time,
          position: buy ? "belowBar" : "aboveBar",
          color: buy ? "#16a34a" : "#dc2626",
          shape: buy ? "arrowUp" : "arrowDown",
          text: buy ? "BUY" : "SELL"
        }];
      } else {
        lastSignalMarkers = [];
      }
      applyCombinedMarkers();
    }
    fitPriceToVisibleCandles();
    layoutMarketOverlays();
  }
  function ensureVolumeChart() {
    const wrap = $("signal-volume-wrap");
    const el = $("signal-volume");
    if (!el || !wrap) return;
    wrap.hidden = !showVolume;
    if (!showVolume) return;
    if (volumeChart) {
      volumeChart.applyOptions({ width: el.clientWidth });
      bindChartSlavePanes();
      return;
    }
    volumeChart = LightweightCharts.createChart(el, {
      width: el.clientWidth,
      height: 120,
      layout: { backgroundColor: "#ffffff", textColor: "#1a2228" },
      grid: { vertLines: { color: "#eef1f3" }, horzLines: { color: "#eef1f3" } },
      rightPriceScale: { borderColor: "#d5dde2" },
      timeScale: { borderColor: "#d5dde2", visible: false },
      handleScroll: false,
      handleScale: false
    });
    volumeSeries = volumeChart.addHistogramSeries({
      color: "rgba(2, 132, 199, 0.45)",
      priceFormat: { type: "volume" }
    });
    if (chart) {
      chart.timeScale().subscribeVisibleLogicalRangeChange(function (range) {
        if (range && volumeChart) {
          try {
            volumeChart.timeScale().applyOptions({
              rightOffset: currentRightOffset(),
              barSpacing: (chart.timeScale().options().barSpacing) || 8
            });
            volumeChart.timeScale().setVisibleLogicalRange(range);
          } catch (_) {}
        }
      });
    }
    bindChartSlavePanes();
  }
  function updateVolume(bars) {
    if (!showVolume) return;
    ensureVolumeChart();
    if (!volumeSeries || !bars || !bars.length) return;
    const data = bars.map(function (b) {
      const t = toChartTime(b.time);
      if (t == null) return null;
      const up = b.close >= b.open;
      return {
        time: t,
        value: b.volume || 0,
        color: up ? "rgba(22, 163, 74, 0.45)" : "rgba(220, 38, 38, 0.4)"
      };
    }).filter(Boolean);
    volumeSeries.setData(data);
    if (chart) {
      const range = chart.timeScale().getVisibleLogicalRange();
      if (range) {
        try { volumeChart.timeScale().setVisibleLogicalRange(range); } catch (_) {}
      }
    }
  }
  function atRightEdge() {
    if (!chart) return true;
    try {
      const ts = chart.timeScale();
      const range = ts.getVisibleLogicalRange();
      if (!range) return true;
      const barsInfo = candleSeries.barsInLogicalRange(range);
      if (!barsInfo) return true;
      return barsInfo.barsAfter != null && barsInfo.barsAfter <= currentRightOffset() + 3;
    } catch (_) {
      return true;
    }
  }
  function snapshotTimeScale() {
    if (!chart) return null;
    try {
      const opt = chart.timeScale().options();
      return {
        barSpacing: opt && opt.barSpacing,
        logical: chart.timeScale().getVisibleLogicalRange()
      };
    } catch (_) {
      return null;
    }
  }
  function scaleStoreKey(instrument) {
    return String(instrument || lastDeskInstrument || "_").toUpperCase();
  }
  function loadScaleLocal(instrument) {
    try {
      const all = JSON.parse(localStorage.getItem(SCALE_STORE) || "{}");
      const row = all[scaleStoreKey(instrument)];
      if (row && row.barSpacing > 0) return row;
    } catch (_) {}
    return null;
  }
  function saveScaleLocal(instrument) {
    if (!scaleLocked || !(lockedBarSpacing > 0)) return;
    try {
      const all = JSON.parse(localStorage.getItem(SCALE_STORE) || "{}");
      all[scaleStoreKey(instrument)] = {
        barSpacing: lockedBarSpacing,
        logical: lockedLogical
      };
      localStorage.setItem(SCALE_STORE, JSON.stringify(all));
    } catch (_) {}
  }
  function unlockScale() {
    scaleLocked = false;
    lockedBarSpacing = null;
    lockedLogical = null;
    clearTimeout(scaleRememberTimer);
    userScaleGesture = false;
    unlockPriceScale();
    try {
      const all = JSON.parse(localStorage.getItem(SCALE_STORE) || "{}");
      delete all[scaleStoreKey(lastDeskInstrument)];
      localStorage.setItem(SCALE_STORE, JSON.stringify(all));
    } catch (_) {}
  }
  function adoptSavedScale(instrument) {
    const saved = loadScaleLocal(instrument);
    if (!saved) return false;
    scaleLocked = true;
    lockedBarSpacing = saved.barSpacing;
    lockedLogical = saved.logical || null;
    return true;
  }
  function freezePriceScale() {
    if (!candleSeries) return;
    priceScaleLocked = true;
    try { candleSeries.priceScale().applyOptions({ autoScale: false }); } catch (_) {}
    try {
      if (chart && typeof chart.priceScale === "function") {
        chart.priceScale("right").applyOptions({ autoScale: false });
      }
    } catch (_) {}
    syncPriceLockBtn();
  }
  function unlockPriceScale() {
    priceScaleLocked = false;
    try {
      if (candleSeries) candleSeries.priceScale().applyOptions({ autoScale: true });
    } catch (_) {}
    try {
      if (chart && typeof chart.priceScale === "function") {
        chart.priceScale("right").applyOptions({ autoScale: true });
      }
    } catch (_) {}
    syncPriceLockBtn();
  }
  function rememberUserScale() {
    if (applyingScale || !chart) return;
    const snap = snapshotTimeScale();
    if (!snap || !(snap.barSpacing > 0)) return;
    scaleLocked = true;
    lockedBarSpacing = snap.barSpacing;
    const edge = atRightEdge();
    if (followLive && edge) {
      lockedLogical = null;
      userPinned = false;
    } else {
      lockedLogical = snap.logical;
      userPinned = true;
    }
    saveScaleLocal(lastDeskInstrument);
    scheduleSaveDeskLayout();
    syncGoLiveBtn();
  }
  function scheduleRememberUserScale() {
    if (applyingScale) return;
    clearTimeout(scaleRememberTimer);
    scaleRememberTimer = setTimeout(rememberUserScale, 120);
  }
  function restoreTimeScale(follow) {
    if (!chart) return;
    const nested = applyingScale;
    applyingScale = true;
    try {
      const spacing = lockedBarSpacing;
      if (spacing > 0) {
        chart.timeScale().applyOptions({ barSpacing: spacing });
      }
      if (followLive && !userPinned) {
        chart.timeScale().applyOptions({ rightOffset: currentRightOffset() });
        chart.timeScale().scrollToRealTime();
      } else if (lockedLogical) {
        chart.timeScale().setVisibleLogicalRange(lockedLogical);
      }
      if (priceScaleLocked) freezePriceScale();
    } catch (_) {}
    if (!nested) applyingScale = false;
  }
  function bindUserScaleCapture(el) {
    if (!el || el._trinityScaleBound) return;
    el._trinityScaleBound = true;
    const markTime = function () {
      scheduleRememberUserScale();
    };
    el.addEventListener("wheel", markTime, { passive: true, capture: true });
    el.addEventListener("mousedown", function (ev) {
      userScaleGesture = true;
      const rect = el.getBoundingClientRect();
      const x = ev.clientX - rect.left;
      let priceW = 56;
      try {
        if (chart && typeof chart.priceScale === "function") {
          priceW = chart.priceScale("right").width() || 56;
        }
      } catch (_) {}
      if (x > rect.width - priceW - 4) freezePriceScale();
    });
    el.addEventListener("touchstart", function () { userScaleGesture = true; }, { passive: true });
    const endGesture = function () {
      if (!userScaleGesture) return;
      userScaleGesture = false;
      markTime();
      syncPriceLockBtn();
    };
    window.addEventListener("mouseup", endGesture);
    window.addEventListener("touchend", endGesture, { passive: true });
  }
  function currentRightOffset() {
    return rightPadOn ? RIGHT_PAD_ON : RIGHT_PAD_OFF;
  }
  function syncPadButtons() {
    const deskBtn = $("signal-desk-pad");
    const chartBtn = $("signal-chart-pad");
    [deskBtn, chartBtn].forEach(function (btn) {
      if (!btn) return;
      btn.setAttribute("aria-pressed", rightPadOn ? "true" : "false");
      btn.classList.toggle("is-on", rightPadOn);
      btn.classList.toggle("is-active", rightPadOn);
    });
    if (deskBtn) {
      deskBtn.textContent = rightPadOn ? "Автоотступ · вкл" : "Автоотступ";
    }
  }
  function syncPriceLockBtn() {
    const btn = $("signal-chart-price-lock");
    if (!btn) return;
    btn.hidden = !priceScaleLocked;
    btn.setAttribute("aria-pressed", priceScaleLocked ? "true" : "false");
    btn.classList.toggle("is-on", priceScaleLocked);
  }
  function syncGoLiveBtn() {
    if (chartNav && typeof chartNav.syncGoLive === "function") {
      chartNav.syncGoLive();
      return;
    }
    const btn = $("signal-chart-live");
    if (!btn) return;
    btn.hidden = !chart || atRightEdge();
  }
  function setMeasureTool(on) {
    if (!chartNav || typeof chartNav.setMeasureMode !== "function") return;
    if (on) {
      if (fpToolActive) toggleFpTool();
      if (chartTools && chartTools.getMode()) {
        chartTools.setMode(null);
        syncDrawToolButtons();
      }
    }
    chartNav.setMeasureMode(!!on);
    setToolPressed("tool-measure", !!on);
  }
  function bindChartSlavePanes() {
    if (!chartNav || typeof chartNav.bindSlavePane !== "function") return;
    const macdEl = $("signal-macd");
    const volEl = $("signal-volume");
    if (macdEl && macdChart) chartNav.bindSlavePane(macdEl, macdChart);
    if (volEl && volumeChart) chartNav.bindSlavePane(volEl, volumeChart);
  }
  function goLive() {
    followLive = true;
    userPinned = false;
    const follow = $("signal-desk-follow");
    if (follow) follow.checked = true;
    if (chart) applyRightPad(true);
    syncGoLiveBtn();
  }
  function setChartLegendSymbol(inst, tf) {
    const el = $("signal-legend-sym");
    if (!el) return;
    const tfl = tf === "H1" ? "H1" : (tf || lastChartTf || "M5");
    el.textContent = (inst || lastDeskInstrument || "—") + " · " + tfl;
  }
  function paintChartOhlc() {
    if (chartTools && typeof chartTools.paintOhlc === "function") chartTools.paintOhlc();
    syncGoLiveBtn();
  }
  function applyRightPad(scrollLive) {
    if (!chart) return;
    applyingScale = true;
    try {
      chart.timeScale().applyOptions({ rightOffset: currentRightOffset() });
      if (lockedBarSpacing > 0) {
        chart.timeScale().applyOptions({ barSpacing: lockedBarSpacing });
      }
    } catch (_) {}
    if (scrollLive !== false) {
      try { chart.timeScale().scrollToRealTime(); } catch (_) {}
      if (!scaleLocked) userPinned = false;
    }
    applyingScale = false;
    syncPadButtons();
    syncMacdTimeScale();
  }
  function toggleRightPad() {
    rightPadOn = !rightPadOn;
    applyRightPad(true);
  }
  function ensureChartHud() {
    const main = document.querySelector(".signal-chart-main");
    const host = $("signal-chart");
    if (!main || !host) return;
    if (!$("signal-chart-legend")) {
      const legend = document.createElement("div");
      legend.id = "signal-chart-legend";
      legend.className = "signal-chart-legend";
      legend.innerHTML = '<span class="signal-legend-sym" id="signal-legend-sym">—</span>'
        + '<span class="signal-legend-ohlc" id="signal-legend-ohlc"></span>';
      main.insertBefore(legend, host);
    }
    if (!$("signal-chart-live")) {
      const b = document.createElement("button");
      b.type = "button";
      b.id = "signal-chart-live";
      b.className = "signal-chart-live-btn";
      b.hidden = true;
      b.title = "К последней свече (End)";
      b.setAttribute("aria-label", "К последней свече");
      b.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12h11M12 6l8 6-8 6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
      host.appendChild(b);
    }
    if (!$("signal-chart-price-lock")) {
      const b = document.createElement("button");
      b.type = "button";
      b.id = "signal-chart-price-lock";
      b.className = "signal-chart-lock-btn";
      b.hidden = true;
      b.title = "Цена закреплена — двойной клик по графику вернёт автомасштаб";
      b.setAttribute("aria-label", "Сбросить масштаб цены");
      b.textContent = "🔒";
      host.appendChild(b);
    }
  }
  function deskPointSize(secid, fromApi) {
    if (fromApi > 0) return fromApi;
    if (window.TrinityChartKit && typeof TrinityChartKit.pointSizeFor === "function") {
      return TrinityChartKit.pointSizeFor(secid);
    }
    return 0.01;
  }
  function ensureChart(secid, pointSize) {
    const el = $("signal-chart");
    if (!el) return;
    ensureChartHud();
    if (chart) {
      if (chartTools && typeof chartTools.setPointSize === "function") {
        chartTools.setPointSize(deskPointSize(secid, pointSize));
      }
      return;
    }
    chart = LightweightCharts.createChart(el, {
      width: el.clientWidth || el.offsetWidth || 600,
      height: Math.max(el.clientHeight || 0, 420),
      layout: {
        backgroundColor: "#ffffff",
        textColor: "#1a2228"
      },
      grid: {
        vertLines: { color: "#eef1f3" },
        horzLines: { color: "#eef1f3" }
      },
      localization: {
        locale: "ru-RU",
        timeFormatter: formatChartTimeMsk
      },
      crosshair: {
        // 0 = Normal: lines track the pointer exactly (Magnet=1 snaps to OHLC and drifts)
        mode: (window.LightweightCharts && LightweightCharts.CrosshairMode
          ? LightweightCharts.CrosshairMode.Normal : 0),
        vertLine: {
          color: "rgba(30,42,50,0.45)",
          labelBackgroundColor: "#1a2228",
          width: 1,
          style: 0
        },
        horzLine: {
          color: "rgba(30,42,50,0.45)",
          labelBackgroundColor: "#1a2228",
          width: 1,
          style: 0
        }
      },
      rightPriceScale: { borderColor: "#d5dde2", autoScale: true },
      timeScale: {
        borderColor: "#d5dde2",
        timeVisible: true,
        secondsVisible: false,
        rightOffset: currentRightOffset(),
        barSpacing: 8,
        lockVisibleTimeRangeOnResize: true,
        shiftVisibleRangeOnNewBar: true
      },
      handleScroll: { mouseWheel: false, pressedMouseMove: true, horzTouchDrag: true, vertTouchDrag: false },
      handleScale: { axisPressedMouseMove: true, mouseWheel: false, pinch: true }
    });
    syncPadButtons();
    candleSeries = chart.addCandlestickSeries({
      upColor: "#16a34a", downColor: "#dc2626",
      borderUpColor: "#16a34a", borderDownColor: "#dc2626",
      wickUpColor: "#16a34a", wickDownColor: "#dc2626"
    });
    chart.timeScale().subscribeVisibleLogicalRangeChange(function () {
      fitPriceToVisibleCandles();
      layoutMarketOverlays();
      syncMacdTimeScale();
      syncGoLiveBtn();
      if (!followLive) {
        userPinned = true;
        return;
      }
      userPinned = !atRightEdge();
    });
    chart.subscribeClick(function (param) {
      if (chartTools && chartTools.getMode()) return; // vap/trend handled in kit
      if (!fpToolActive || !param || param.time == null) return;
      const t = typeof param.time === "number" ? nearestBarTime(param.time) : null;
      if (t == null) return;
      if (fpAnchor == null) {
        fpAnchor = t;
        fpHoverTime = t;
        fpSelected = false;
        layoutFootprint();
        return;
      }
      applyFpRange(fpAnchor, t);
    });
    if (window.TrinityChartKit && !chartTools) {
      chartTools = TrinityChartKit.attachTools({
        chart: chart,
        candleSeries: candleSeries,
        hostEl: el,
        pointSize: deskPointSize(secid, pointSize),
        overlayId: "signal-vap-stretch",
        getBars: function () {
          return (lastBarsRaw || []).map(function (b) {
            return {
              time: toChartTime(b.time),
              open: b.open, high: b.high, low: b.low, close: b.close, volume: b.volume
            };
          }).filter(function (b) { return b.time != null; });
        },
        onChange: scheduleSaveDeskLayout,
        onScaleLayout: layoutMarketOverlays,
        freezePrice: false,
        legendEl: $("signal-chart-legend")
      });
      loadDeskLayoutOnce();
    }
    chart.subscribeCrosshairMove(function (param) {
      if (!fpToolActive) {
        if (fpHoverTime != null && fpAnchor == null) {
          fpHoverTime = null;
          layoutFootprint();
        }
        return;
      }
      const t = param && typeof param.time === "number" ? nearestBarTime(param.time) : null;
      if (t === fpHoverTime) return;
      fpHoverTime = t;
      layoutFootprint();
    });
    let overlayRoTimer = null;
    function scheduleOverlayLayout() {
      if (overlayRoTimer) return;
      overlayRoTimer = setTimeout(function () {
        overlayRoTimer = null;
        resizeChartToHost();
        layoutMarketOverlays();
      }, 80);
    }
    window.addEventListener("resize", scheduleOverlayLayout);
    if (typeof ResizeObserver !== "undefined" && !el._trinityRo) {
      el._trinityRo = new ResizeObserver(scheduleOverlayLayout);
      el._trinityRo.observe(el);
    }
    ensureZoneOverlay();
    ensureProfileOverlay();
    ensureFootprintOverlay();
    bindUserScaleCapture(el);
    if (window.TrinityChartKit && typeof TrinityChartKit.bindTradingViewNav === "function" && !chartNav) {
      chartNav = TrinityChartKit.bindTradingViewNav({
        chart: chart,
        series: candleSeries,
        hostEl: el,
        goLiveBtn: $("signal-chart-live"),
        lockBtn: $("signal-chart-price-lock"),
        barSec: lastChartTf === "H1" ? 3600 : 300,
        isDrawing: function () {
          return !!(fpToolActive || (chartTools && chartTools.getMode()));
        },
        atRightEdge: atRightEdge,
        getPointSize: function () { return deskPointSize(lastDeskInstrument); },
        onTimeGesture: function () {
          scheduleRememberUserScale();
          layoutMarketOverlays();
        },
        onPriceLock: function (locked) {
          if (locked) freezePriceScale();
          else {
            unlockPriceScale();
            fitPriceToVisibleCandles();
          }
        },
        onGoLive: goLive,
        onMeasureMode: function (on) {
          setToolPressed("tool-measure", !!on);
        }
      });
      bindChartSlavePanes();
    }
    if (!scaleLocked) adoptSavedScale(secid);
  }
  function formatChartTimeMsk(t) {
    const sec = typeof t === "number" ? t : (t && t.timestamp);
    if (sec == null || !isFinite(Number(sec))) return "";
    const d = new Date(Number(sec) * 1000);
    try {
      return new Intl.DateTimeFormat("ru-RU", {
        timeZone: "Europe/Moscow",
        day: "2-digit",
        month: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        hour12: false
      }).format(d);
    } catch (_) {
      return d.toISOString().slice(11, 16);
    }
  }
  function toChartTime(iso) {
    if (!iso) return null;
    let s = String(iso).trim();
    // LocalDateTime often serializes as 2026-08-13T18:00+03:00 (no seconds).
    // Safari / some engines reject that — normalize to …T18:00:00+03:00.
    s = s.replace(
      /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2})([Zz]|[+-]\d{2}:?\d{2})?$/,
      function (_, hm, off) { return hm + ":00" + (off || ""); }
    );
    if (s.indexOf("T") < 0) s = s.replace(" ", "T");
    const d = new Date(s);
    if (isNaN(d.getTime())) return null;
    return Math.floor(d.getTime() / 1000);
  }
  function medianBarMinutes(bars) {
    if (!bars || bars.length < 3) return null;
    const ds = [];
    let prev = null;
    for (let i = 0; i < bars.length; i++) {
      const t = toChartTime(bars[i] && bars[i].time);
      if (t == null) continue;
      if (prev != null) {
        const m = (t - prev) / 60;
        if (m > 0 && m < 24 * 60) ds.push(m);
      }
      prev = t;
    }
    if (ds.length < 2) return null;
    ds.sort(function (a, b) { return a - b; });
    return ds[Math.floor(ds.length / 2)];
  }
  function resizeChartToHost() {
    const el = $("signal-chart");
    if (!chart || !el) return;
    const w = Math.round(el.clientWidth || el.offsetWidth || 0);
    const h = Math.round(Math.max(el.clientHeight || 0, 420));
    if (w >= 40 && (w !== lastChartSize.w || h !== lastChartSize.h)) {
      lastChartSize = { w: w, h: h };
      applyingScale = true;
      try { chart.applyOptions({ width: w, height: h }); } catch (_) {}
      applyingScale = false;
      if (w >= 40) chartNeedsFit = false;
    }
    if (volumeChart) {
      const vEl = $("signal-volume");
      if (vEl && vEl.clientWidth >= 40) {
        try { volumeChart.applyOptions({ width: vEl.clientWidth }); } catch (_) {}
      }
    }
    if (macdChart) {
      const mEl = $("signal-macd");
      if (mEl && (mEl.clientWidth || el.clientWidth) >= 40) {
        try { macdChart.applyOptions({ width: mEl.clientWidth || el.clientWidth }); } catch (_) {}
      }
    }
  }
  function sanitizeCandles(raw) {
    const out = [];
    let prev = null;
    (raw || []).forEach(function (c) {
      if (!c || c.time == null) return;
      const t = c.time;
      let o = Number(c.open), h = Number(c.high), l = Number(c.low), cl = Number(c.close);
      if (![o, h, l, cl].every(Number.isFinite)) return;
      if (prev != null && t <= prev) return;
      // Drop absurd wicks (live DOM / stale IndexedDB used to pin lows to ZERO ~1pt away).
      const bodyLo = Math.min(o, cl);
      const bodyHi = Math.max(o, cl);
      const maxWick = Math.max(0.30, (bodyHi - bodyLo) * 4 + 0.08);
      if (h < bodyHi) h = bodyHi;
      if (l > bodyLo) l = bodyLo;
      if (h - bodyHi > maxWick) h = bodyHi + maxWick;
      if (bodyLo - l > maxWick) l = bodyLo - maxWick;
      const mid = Math.abs(cl) || Math.abs(o);
      if (mid > 0 && (h - l) > mid * 0.25) {
        h = bodyHi;
        l = bodyLo;
      }
      out.push({ time: t, open: o, high: h, low: l, close: cl });
      prev = t;
    });
    return out;
  }
  function applyTimeSnap(snap) {
    if (!chart || !snap) return;
    if (snap.barSpacing > 0) {
      try { chart.timeScale().applyOptions({ barSpacing: snap.barSpacing }); } catch (_) {}
    }
    if (snap.logical) {
      try { chart.timeScale().setVisibleLogicalRange(snap.logical); } catch (_) {}
    }
  }
  function replaceDataKeepView(candles) {
    const snap = snapshotTimeScale();
    applyingScale = true;
    try {
      candleSeries.setData(candles);
      applyTimeSnap(snap);
      if (priceScaleLocked) freezePriceScale();
    } finally {
      applyingScale = false;
    }
    requestAnimationFrame(function () {
      applyingScale = true;
      applyTimeSnap(snap);
      if (priceScaleLocked) freezePriceScale();
      applyingScale = false;
      applyCombinedMarkers();
    });
  }
  /**
   * @param {boolean} forceFit fit content / unlock
   * @param {boolean} [fromServer] full series replace (desk poll) — incremental update alone
   *   leaves fake wicks on older bars after IndexedDB/live DOM corruption
   */
  function updateCandles(candles, forceFit, fromServer) {
    candles = sanitizeCandles(candles);
    lastSanitizedCandles = candles;
    if (!candleSeries || !candles.length) return;
    resizeChartToHost();
    const last = candles[candles.length - 1];
    const prevBar = candles.length >= 2 ? candles[candles.length - 2] : null;
    const firstPaint = lastCandleTime == null;
    const sameLast = !firstPaint && last.time === lastCandleTime;
    const newBar = !firstPaint && prevBar && prevBar.time === lastCandleTime;

    // Server desk payload: always rewrite series (keeps zoom). Live book only updates last bar.
    if (fromServer && !forceFit && !firstPaint) {
      replaceDataKeepView(candles);
      lastCandleTime = last.time;
      lastCandlesLen = candles.length;
      if (priceScaleLocked) freezePriceScale();
      requestAnimationFrame(function () {
        if (priceScaleLocked) freezePriceScale();
        else fitPriceToVisibleCandles();
        layoutMarketOverlays();
        syncMacdTimeScale();
        paintChartOhlc();
      });
      return;
    }

    if (!forceFit && !firstPaint) {
      if (sameLast || newBar) {
        applyingScale = true;
        try {
          if (newBar && prevBar) {
            candleSeries.update(prevBar);
          }
          candleSeries.update(last);
        } catch (_) {
          replaceDataKeepView(candles);
        }
        applyingScale = false;
      } else {
        replaceDataKeepView(candles);
      }
      lastCandleTime = last.time;
      lastCandlesLen = candles.length;
      if (priceScaleLocked) freezePriceScale();
      requestAnimationFrame(function () {
        if (priceScaleLocked) freezePriceScale();
        else fitPriceToVisibleCandles();
        layoutMarketOverlays();
        syncMacdTimeScale();
        paintChartOhlc();
      });
      return;
    }

    applyingScale = true;
    try {
      candleSeries.setData(candles);
      lastCandleTime = last.time;
      lastCandlesLen = candles.length;
      if (forceFit && !scaleLocked) {
        try { candleSeries.priceScale().applyOptions({ autoScale: true }); } catch (_) {}
        try { chart.timeScale().fitContent(); } catch (_) {}
        userPinned = false;
        try { chart.timeScale().applyOptions({ rightOffset: currentRightOffset() }); } catch (_) {}
        try { chart.timeScale().scrollToRealTime(); } catch (_) {}
      } else if (scaleLocked) {
        restoreTimeScale(followLive && !userPinned);
      }
    } catch (err) {
      try {
        candleSeries.setData(candles);
        lastCandleTime = last.time;
        lastCandlesLen = candles.length;
      } catch (e2) {
        if (typeof console !== "undefined") console.warn("chart setData failed", e2);
      }
    }
    applyingScale = false;
    requestAnimationFrame(function () {
      resizeChartToHost();
      fitPriceToVisibleCandles();
      layoutMarketOverlays();
      syncMacdTimeScale();
      paintChartOhlc();
    });
  }
  function livePxFromBook(book) {
    if (!book) return null;
    const bids = book.bids || [];
    const asks = book.asks || [];
    const bb = bids.length ? Number(bids[0].p) : NaN;
    const ba = asks.length ? Number(asks[0].p) : NaN;
    if (bb > 0 && ba > 0) return (bb + ba) / 2;
    if (bb > 0) return bb;
    if (ba > 0) return ba;
    return null;
  }
  function paintLastCandle(px, book) {
    if (!candleSeries || !(px > 0) || lastCandleTime == null) return;
    const raw = lastBarsRaw && lastBarsRaw.length ? lastBarsRaw[lastBarsRaw.length - 1] : null;
    if (!raw) return;
    const o = Number(raw.open);
    let h = Number(raw.high);
    let l = Number(raw.low);
    if (![o, h, l].every(Number.isFinite)) return;
    if (!plausibleLivePx(o, px) && !plausibleLivePx(Number(raw.close), px)) return;
    // Chart: only last/mid trade expands the forming wick — NOT best bid/ask.
    // Bid/ask extremes caused fake spikes to deep DOM levels; TP touch uses book separately.
    if (px > h) h = px;
    if (px < l) l = px;
    raw.close = px;
    raw.high = h;
    raw.low = l;
    applyingScale = true;
    try {
      candleSeries.update({ time: lastCandleTime, open: o, high: h, low: l, close: px });
    } catch (_) {}
    applyingScale = false;
    if (priceScaleLocked) freezePriceScale();
    applyLiveManage(px, book);
  }
  function bookTouchPx(open, book, mid) {
    const bb = book && book.bids && book.bids[0] ? Number(book.bids[0].p) : NaN;
    const ba = book && book.asks && book.asks[0] ? Number(book.asks[0].p) : NaN;
    const xs = [mid, bb, ba].filter(function (v) { return v > 0; });
    if (!xs.length) return mid;
    if (open && open.side === "BUY") return Math.max.apply(null, xs);
    return Math.min.apply(null, xs);
  }
  function liveTouchedLevel(open, px, key) {
    if (!open || !(px > 0)) return false;
    const lvl = Number(open[key]);
    if (!(lvl > 0)) return false;
    return open.side === "BUY" ? px >= lvl : px <= lvl;
  }
  function liveWouldExit(open, px) {
    if (!open || !(px > 0)) return false;
    if (liveTouchedLevel(open, px, "tp2")) return true;
    const sl = Number(open.sl);
    if (!(sl > 0)) return false;
    return open.side === "BUY" ? px <= sl : px >= sl;
  }
  function applyLiveManage(px, book) {
    if (!lastWorkingOpen || !(px > 0)) return;
    const open = lastWorkingOpen;
    const touch = bookTouchPx(open, book, px);
    if (liveTouchedLevel(open, touch, "tp2") || liveWouldExit(open, touch)) {
      flattenWorking();
      return;
    }
    if (!open.tp1Done && liveTouchedLevel(open, touch, "tp1")) {
      const qty = Number(open.qty) || 0;
      let q1 = Math.round(qty / 3);
      if (q1 >= qty) q1 = qty - 1;
      if (q1 < 0) q1 = 0;
      if (q1 > 0) open.qty = qty - q1;
      open.tp1Done = true;
      if (Number(open.avg) > 0) open.sl = Number(open.avg);
      liveTp1Until = Date.now() + 60000;
      lastOverlayKey = "";
      applyOverlays(lastOverlayPlan, lastOverlaySig, lastOverlayCandles, overlayStructure, open);
      if (lastDeskSnapshot) {
        try { syncStatusRail(lastDeskSnapshot); } catch (_) {}
      }
    }
  }
  function flattenWorking() {
    lastWorkingOpen = null;
    liveFlatUntil = Date.now() + 60000;
    liveTp1Until = 0;
    lastOverlayKey = "";
    applyOverlays(
      Object.assign({}, lastOverlayPlan, { actionable: false }),
      lastOverlaySig,
      lastOverlayCandles,
      overlayStructure,
      null
    );
    if (lastDeskSnapshot) {
      const snap = lastDeskSnapshot;
      if (snap.situation) {
        snap.situation = Object.assign({}, snap.situation, {
          inTrade: false,
          posture: "WATCHING_ZONE"
        });
      }
      if (snap.fairPaper) {
        snap.fairPaper = Object.assign({}, snap.fairPaper, { open: null, openPlaybookId: null });
      }
      try { syncStatusRail(snap); } catch (_) {}
    }
  }
  function renderDom(book) {
    const body = $("signal-dom-body");
    const meta = $("signal-dom-meta");
    const imb = $("signal-dom-imbalance");
    const imbBid = $("signal-dom-imb-bid");
    const imbAsk = $("signal-dom-imb-ask");
    if (!body) return;
    if (!domScrollBound) {
      domScrollBound = true;
      body.addEventListener("scroll", function () {
        domFollowMid = false;
      }, { passive: true });
    }
    if (!book || ((!book.bids || !book.bids.length) && (!book.asks || !book.asks.length))) {
      body.innerHTML = "<div class=\"signal-dom-empty\">Нет DOM</div>";
      if (meta) meta.textContent = book && book.summary ? book.summary : "—";
      if (imb) imb.hidden = true;
      return;
    }
    const prevScroll = body.scrollTop;
    const hadRows = !!body.querySelector(".dom-row");
    const bids = (book.bids || []).slice(0, 50);
    const asks = (book.asks || []).slice(0, 50);
    const tape = book.tapeByPrice || {};
    const bestBid = bids.length ? Number(bids[0].p) : null;
    const bestAsk = asks.length ? Number(asks[0].p) : null;
    let bidLots = 0, askLots = 0;
    bids.forEach(function (b) { bidLots += Number(b.q) || 0; });
    asks.forEach(function (a) { askLots += Number(a.q) || 0; });
    const totLots = bidLots + askLots;
    if (imb && imbBid && imbAsk && totLots > 0) {
      imb.hidden = false;
      const bp = Math.round(100 * bidLots / totLots);
      imbBid.style.width = bp + "%";
      imbAsk.style.width = (100 - bp) + "%";
      imbBid.title = "Bid " + Math.round(bidLots) + " лотов (" + bp + "%)";
      imbAsk.title = "Ask " + Math.round(askLots) + " лотов (" + (100 - bp) + "%)";
    } else if (imb) {
      imb.hidden = true;
    }
    if (meta) {
      const age = book.asOf ? (" · " + new Date(book.asOf).toLocaleTimeString("ru-RU")) : "";
      const spr = (bestBid != null && bestAsk != null)
        ? (" · spr " + (bestAsk - bestBid).toFixed(2))
        : "";
      meta.textContent = (book.instrumentId || "BR")
        + " · depth " + Math.max(bids.length, asks.length)
        + spr + age;
    }
    let maxQ = 1;
    bids.forEach(function (b) { maxQ = Math.max(maxQ, Number(b.q) || 0); });
    asks.forEach(function (a) { maxQ = Math.max(maxQ, Number(a.q) || 0); });
    Object.keys(tape).forEach(function (k) {
      const t = tape[k];
      if (t && t.total) maxQ = Math.max(maxQ, Number(t.total) || 0);
    });
    const pxKey = function (p) {
      return (Math.round(Number(p) * 100) / 100).toFixed(2);
    };
    const barW = function (q) {
      return Math.max(4, Math.round(100 * (Number(q) || 0) / maxQ));
    };
    let html = "";
    // Asks: reverse so highest at top, best ask at bottom of ask zone
    for (let i = asks.length - 1; i >= 0; i--) {
      const a = asks[i];
      const p = Number(a.p);
      const q = Number(a.q) || 0;
      const key = pxKey(p);
      const t = tape[key] || {};
      const buy = Number(t.buy) || 0;
      const sell = Number(t.sell) || 0;
      const isBest = bestAsk != null && Math.abs(p - bestAsk) < 1e-9;
      html += "<div class=\"dom-row dom-ask" + (isBest ? " is-best" : "") + "\">"
        + "<span class=\"dom-bid-px\"></span>"
        + "<span class=\"dom-bid-q\"></span>"
        + "<span class=\"dom-px\">" + p.toFixed(2) + "</span>"
        + "<span class=\"dom-ask-q\"><i style=\"width:" + barW(q) + "%\"></i><em>" + q + "</em></span>"
        + "<span class=\"dom-ask-px\">" + p.toFixed(2) + "</span>"
        + "<span class=\"dom-tape\">"
        + (buy || sell
          ? ("<b class=\"b\">" + buy + "</b><i>×</i><b class=\"s\">" + sell + "</b>")
          : "")
        + "</span>"
        + "</div>";
    }
    if (bestBid != null && bestAsk != null) {
      const mid = ((bestBid + bestAsk) / 2).toFixed(2);
      const spr = (bestAsk - bestBid).toFixed(2);
      html += "<div class=\"dom-row dom-spread\">"
        + "<span class=\"dom-spread-label\">SPREAD " + spr + " · mid " + mid + "</span>"
        + "</div>";
    }
    for (let i = 0; i < bids.length; i++) {
      const b = bids[i];
      const p = Number(b.p);
      const q = Number(b.q) || 0;
      const key = pxKey(p);
      const t = tape[key] || {};
      const buy = Number(t.buy) || 0;
      const sell = Number(t.sell) || 0;
      const isBest = bestBid != null && Math.abs(p - bestBid) < 1e-9;
      html += "<div class=\"dom-row dom-bid" + (isBest ? " is-best" : "") + "\">"
        + "<span class=\"dom-bid-px\">" + p.toFixed(2) + "</span>"
        + "<span class=\"dom-bid-q\"><i style=\"width:" + barW(q) + "%\"></i><em>" + q + "</em></span>"
        + "<span class=\"dom-px\">" + p.toFixed(2) + "</span>"
        + "<span class=\"dom-ask-q\"></span>"
        + "<span class=\"dom-ask-px\"></span>"
        + "<span class=\"dom-tape\">"
        + (buy || sell
          ? ("<b class=\"b\">" + buy + "</b><i>×</i><b class=\"s\">" + sell + "</b>")
          : "")
        + "</span>"
        + "</div>";
    }
    body.innerHTML = html;
    paintLastCandle(livePxFromBook(book), book);
    // Center mid only on first paint; never scrollIntoView (it jumps the whole page)
    if (!hadRows || domFollowMid) {
      const bestEl = body.querySelector(".dom-spread") || body.querySelector(".is-best");
      if (bestEl) {
        const target = bestEl.offsetTop - (body.clientHeight / 2) + (bestEl.offsetHeight / 2);
        body.scrollTop = Math.max(0, target);
      }
    } else {
      body.scrollTop = prevScroll;
    }
  }
  async function kickRobot() {
    const btn = $("sig-kick-btn");
    if (btn) btn.disabled = true;
    try {
      const res = await fetch("/api/trend/kick?mode=soft&reason=desk-button", {
        method: "POST",
        headers: deskAuthHeaders()
      });
      const data = await res.json().catch(function () { return {}; });
      if (!res.ok) {
        const msg = data.message || data.error || ("HTTP " + res.status);
        throw new Error(msg);
      }
      await loadDesk(true);
      const brief = $("signal-desk-brief-body");
      if (brief) {
        brief.innerHTML = "<p><strong>Сброс залипания:</strong> "
          + (data.reason || "soft")
          + " · kicksToday=" + (data.kickCountToday || "?")
          + " · " + (data.deskSummary || data.engineState || "")
          + " <span class='signal-brief-note'>(day-lock TOP/BOT сохранён)</span></p>"
          + brief.innerHTML;
      }
    } catch (e) {
      alert("Сброс не удался: " + (e && e.message ? e.message : e));
    } finally {
      if (btn) btn.disabled = false;
    }
  }
  function deskInstrumentQuery() {
    const inst = wantedDeskInstrument() || lastDeskInstrument;
    return inst ? ("?instrument=" + encodeURIComponent(inst)) : "";
  }
  async function loadBook() {
    try {
      const res = await fetch("/api/marketdata/book" + deskInstrumentQuery(),
        { headers: { Accept: "application/json" } });
      if (!res.ok) return;
      renderDom(await res.json());
    } catch (_) {}
  }
  let deskInFlight = false;
  let deskReloadQueued = false;
  async function loadDesk(forceFit) {
    if (deskInFlight) {
      deskReloadQueued = true;
      deskQueuedForceFit = deskQueuedForceFit || !!forceFit;
      return;
    }
    deskInFlight = true;
    const gen = ++deskFetchGen;
    const meta = $("signal-desk-meta");
    const wantInst = wantedDeskInstrument();
    try {
      const q = [];
      if (wantInst) q.push("instrument=" + encodeURIComponent(wantInst));
      q.push("playbook=" + encodeURIComponent(viewPlaybookId()));
      const res = await fetch("/api/trend/desk?" + q.join("&"), { headers: { Accept: "application/json" } });
      if (!res.ok) throw new Error("HTTP " + res.status);
      const data = await res.json();
      if (gen !== deskFetchGen) return;
      const stillWant = wantedDeskInstrument() || wantInst;
      if (deskScope() === "positional"
          && stillWant && data.instrument && !sameInstrumentFamily(stillWant, data.instrument)) {
        deskReloadQueued = true;
        return;
      }
      if (meta) {
        meta.textContent = (data.playbookName || data.playbookId || "playbook")
          + " · " + (data.instrument || "BR")
          + " · " + (data.timeframe || "M5")
          + " · bars=" + (data.barCount || 0)
          + " · source=" + (data.barsSource || "?")
          + " · " + (data.engineState || "")
          + (followLive && !userPinned ? " · follow" : " · zoom locked")
          + clockMeta((data.situation) || {});
      }
      fillDeskSelects(data);
      paintRobotChip(data);
      syncPositionalAutoSwitch(data);
      const oilBanWrap = $("us-oil-banner");
      if (oilBanWrap) oilBanWrap.hidden = deskScope() === "positional";
      const oilBan = $("us-oil-banner-text");
      const oil = (data.situation && data.situation.usOil) || {};
      if (oilBan) {
        const parts = [];
        if (oil.brief) parts.push(oil.brief);
        if (oil.waitReason) parts.push(oil.waitReason);
        oilBan.textContent = parts.length
          ? parts.join(" ")
          : "Ночной ход CL часто уже сидит в утреннем гэпе BR. Exclusive: BOT = покупка, TOP = продажа — CL сторону не переворачивает.";
      }
      $("sig-delivery").textContent = data.delivery || "—";
      const sig = data.signal || {};
      const plan = data.plan || {};
      $("sig-side").textContent = sig.side || plan.side || "—";
      $("sig-mode").textContent = sig.mode || plan.mode || "—";
      $("sig-potential").textContent = fmtPot(data.potentialPnlRub);
      $("sig-side").classList.toggle("is-buy", (sig.side || plan.side) === "BUY");
      $("sig-side").classList.toggle("is-sell", (sig.side || plan.side) === "SELL");
      const chartInst = data.instrument || "—";
      const chartLabel = $("signal-chart-label");
      const paperTitle = $("signal-paper-title");
      if (paperTitle) {
        paperTitle.textContent = "Сделки сегодня · " + chartInst
          + (data.robotInstrument && data.robotInstrument !== chartInst
            ? (" · робот: " + data.robotInstrument) : "");
      }
      renderPaper(data.paper, data);
      const briefBody = $("signal-desk-brief-body");
      if (briefBody) briefBody.innerHTML = buildOperatorBrief(data);
      syncDomPressureFab(data);

      ensureChart(data.instrument, data.pointSize);
      resizeChartToHost();
      // Positional desk evaluates on H1 — prefer barsH1 for the candle pane.
      const wantH1 = (data.timeframe === "H1" || data.playbookId === "positional-volume-h1");
      const useH1 = wantH1 && Array.isArray(data.barsH1) && data.barsH1.length > 0;
      let rawBars = useH1 ? data.barsH1 : (data.bars || []);
      // Fallback: positional wire may send empty M5 `bars` — never leave the pane blank if H1 exists.
      if ((!rawBars || !rawBars.length) && Array.isArray(data.barsH1) && data.barsH1.length) {
        rawBars = data.barsH1;
      }
      // Label must match what we actually paint (not server intent alone).
      let chartTf = (useH1 || rawBars === data.barsH1) ? "H1" : "M5";
      let chartSource = (chartTf === "H1") ? (data.h1Source || "") : "";
      const medianMin = medianBarMinutes(rawBars);
      if (chartTf === "H1" && medianMin != null && medianMin <= 15) {
        chartTf = "M5";
        chartSource = "interval-guard";
        if (meta) {
          meta.textContent = (meta.textContent || "")
            + " · chart: H1 payload looked like M5 (~" + medianMin + "m)";
        }
      }
      lastChartTf = chartTf;
      setChartLegendSymbol(chartInst, chartTf);
      if (chartLabel) {
        chartLabel.textContent = "График · " + chartInst + " " + (chartTf === "H1" ? "час" : chartTf)
          + (chartSource ? (" · " + h1SourceRu(chartSource)) : "");
      }
      const instrumentChanged = !!chartInst && chartInst !== lastDeskInstrument;
      if (instrumentChanged) {
        lastDeskInstrument = chartInst;
        lastOverlayKey = "";
        lastCandleTime = null;
        lastCandlesLen = 0;
        scaleLocked = false;
        lockedBarSpacing = null;
        lockedLogical = null;
        unlockPriceScale();
        const hadSaved = adoptSavedScale(chartInst);
        chartNeedsFit = !hadSaved;
        clearLines();
        if (!hadSaved) {
          try {
            if (candleSeries) candleSeries.priceScale().applyOptions({ autoScale: true });
          } catch (_) {}
        }
      }
      const candles = rawBars.map(function (b) {
        const t = toChartTime(b.time);
        if (t == null) return null;
        return { time: t, open: b.open, high: b.high, low: b.low, close: b.close, volume: b.volume };
      }).filter(Boolean);
      const livePx = livePxFromBook(data.book);
      if (livePx > 0 && candles.length) {
        const last = candles[candles.length - 1];
        const ref = Number(last.close);
        if (plausibleLivePx(ref, livePx)) {
          last.close = livePx;
          if (livePx > last.high) last.high = livePx;
          if (livePx < last.low) last.low = livePx;
        }
      }
      if (meta && rawBars.length && !candles.length) {
        meta.textContent = (meta.textContent || "") + " · chart: bad bar times";
      } else if (meta && candles.length) {
        meta.textContent = (meta.textContent || "") + " · candles=" + candles.length;
      }
      const sit = data.situation || {};
      const fp = sit.fairPaper || data.fairPaper || {};
      const overlayPb = sit.playbookId
        || viewPlaybookId()
        || (data.parallelPlaybooks ? "levels-profile-br-m5" : data.playbookId)
        || "";
      let overlayOpen = sit.inTrade ? fairPaperLaneOpen(fp, overlayPb) : null;
      const touchPx = bookTouchPx(overlayOpen || lastWorkingOpen, data.book, livePx);
      if (overlayOpen && liveFlatUntil > Date.now()
          && liveWouldExit(overlayOpen, touchPx > 0 ? touchPx : Number(overlayOpen.avg))) {
        overlayOpen = null;
      } else if (overlayOpen) {
        liveFlatUntil = 0;
        if (liveTp1Until > Date.now() && !overlayOpen.tp1Done
            && liveTouchedLevel(overlayOpen, touchPx, "tp1")) {
          overlayOpen = Object.assign({}, overlayOpen, {
            tp1Done: true,
            sl: Number(overlayOpen.avg) > 0 ? overlayOpen.avg : overlayOpen.sl
          });
        } else if (overlayOpen.tp1Done) {
          liveTp1Until = 0;
        }
      }
      lastWorkingOpen = overlayOpen;
      lastOverlayPlan = plan;
      lastOverlaySig = sig;
      lastOverlayCandles = candles;
      lastDeskSnapshot = data;
      lastTimelineMarkers = timelineMarkersFromDesk(data);
      if (candles.length) {
        updateCandles(candles, !!forceFit, true);
        const planForOv = (!overlayOpen && liveFlatUntil > Date.now())
          ? Object.assign({}, plan, { actionable: false })
          : plan;
        applyOverlays(planForOv, sig, candles, data.structure || {}, overlayOpen);
        if (livePx > 0) applyLiveManage(livePx, data.book);
        refreshImpulseUi(candles);
      } else if (instrumentChanged && candleSeries) {
        // Don't leave the previous instrument's candles on screen.
        try { candleSeries.setData([]); } catch (_) {}
        lastOverlayKey = "";
        applyOverlays({}, {}, [], {}, null);
      }
      lastBarsRaw = rawBars;
      lastProfile = data.profile || [];
      lastFootprint = data.footprint || [];
      indexFootprints(lastFootprint);
      updateVolume(lastBarsRaw);
      updateMacd(lastBarsRaw);
      // setData on desk poll clears LW markers unless we re-apply every refresh.
      applyCombinedMarkers();
      layoutMarketOverlays();
      if (candles.length && window.TrinityChartKit && typeof TrinityChartKit.barCachePut === "function") {
        // Cache server OHLC only (no live mid stretch) so IndexedDB cannot re-poison wicks.
        const cacheBars = rawBars.map(function (b) {
          const t = toChartTime(b.time);
          if (t == null) return null;
          return { time: t, open: b.open, high: b.high, low: b.low, close: b.close, volume: b.volume };
        }).filter(Boolean);
        TrinityChartKit.barCachePut(chartInst, chartTf, {
          instrument: chartInst,
          tf: chartTf,
          pointSize: data.pointSize,
          bars: cacheBars,
          raw: rawBars
        });
      }
      if (data.book) renderDom(data.book);
      // After chart: compliance shape differs for positional (object+items) vs BR (array).
      try { renderCompliance(data); } catch (compErr) {
        if (typeof console !== "undefined") console.warn("renderCompliance", compErr);
      }
      if (window.TrinityPlaques && typeof window.TrinityPlaques.refresh === "function") {
        window.TrinityPlaques.refresh();
      }
    } catch (err) {
      if (meta) meta.textContent = "Ошибка desk: " + (err.message || err);
    } finally {
      deskInFlight = false;
      if (deskReloadQueued) {
        deskReloadQueued = false;
        const again = deskQueuedForceFit;
        deskQueuedForceFit = false;
        loadDesk(!!forceFit || again);
      }
    }
  }
  const btn = $("signal-desk-refresh");
  if (btn) btn.addEventListener("click", function () { loadDesk(false); });
  bindPositionalAutoSwitch();
  const kickBtn = $("sig-kick-btn");
  if (kickBtn) kickBtn.addEventListener("click", kickRobot);
  const fitBtn = $("signal-desk-fit");
  if (fitBtn) fitBtn.addEventListener("click", function () {
    unlockScale();
    userPinned = false;
    followLive = true;
    const follow = $("signal-desk-follow");
    if (follow) follow.checked = true;
    if (chart) {
      applyingScale = true;
      try { chart.timeScale().fitContent(); } catch (_) {}
      applyingScale = false;
      applyRightPad(true);
    }
    loadDesk(true);
    syncGoLiveBtn();
    syncPriceLockBtn();
  });
  const padBtn = $("signal-desk-pad");
  if (padBtn) padBtn.addEventListener("click", toggleRightPad);
  const chartPadBtn = $("signal-chart-pad");
  if (chartPadBtn) chartPadBtn.addEventListener("click", toggleRightPad);
  syncPadButtons();
  const follow = $("signal-desk-follow");
  if (follow) {
    follow.checked = true;
    follow.addEventListener("change", function () {
      followLive = !!follow.checked;
      if (followLive) {
        userPinned = false;
        if (chart) {
          applyRightPad(true);
        }
      }
      syncGoLiveBtn();
    });
  }
  const volToggle = $("signal-desk-volume");
  if (volToggle) {
    volToggle.addEventListener("change", function () {
      showVolume = !!volToggle.checked;
      ensureVolumeChart();
      if (showVolume) updateVolume(lastBarsRaw);
      else if ($("signal-volume-wrap")) $("signal-volume-wrap").hidden = true;
    });
  }
  const toolFp = $("tool-footprint");
  if (toolFp) toolFp.addEventListener("click", toggleFpTool);
  const toolMacd = $("tool-macd");
  if (toolMacd) {
    toolMacd.addEventListener("click", function () {
      showMacd = !showMacd;
      setToolPressed("tool-macd", showMacd);
      const wrap = $("signal-macd-wrap");
      if (wrap) wrap.hidden = !showMacd;
      if (!showMacd) {
        lastDivMarkers = [];
        lastDivMarkersKey = "";
        applyCombinedMarkers();
      }
      updateMacd(lastBarsRaw);
    });
  }
  const toolProf = $("tool-profile");
  if (toolProf) {
    setToolPressed("tool-profile", showProfile);
    toolProf.addEventListener("click", function () {
      showProfile = !showProfile;
      setToolPressed("tool-profile", showProfile);
      layoutProfile(lastProfile);
      if (chartTools) chartTools.refreshOverlays();
    });
  }
  document.addEventListener("keydown", function (ev) {
    const tag = (ev.target && ev.target.tagName || "").toLowerCase();
    if (tag === "input" || tag === "textarea" || tag === "select") return;
    if (ev.key === "End" && chart) {
      ev.preventDefault();
      goLive();
      return;
    }
    if (ev.key === "Escape") {
      const gate = $("signal-guide-modal");
      if (gate && !gate.hidden) {
        closeStrategyGuide();
        return;
      }
      if (chartNav && typeof chartNav.getMeasureMode === "function" && chartNav.getMeasureMode()) {
        setMeasureTool(false);
        return;
      }
      if (chartTools && chartTools.getMode()) {
        chartTools.setMode(null);
        syncDrawToolButtons();
        return;
      }
      if (fpToolActive || fpPinned.length || fpRangeFrom != null) {
        fpToolActive = false;
        setToolPressed("tool-footprint", false);
        syncChartCursor();
        clearFpPins();
      }
      return;
    }
    if ((ev.key === "Backspace" || ev.key === "Delete")
        && (fpSelected || fpPinned.length || fpRangeFrom != null)) {
      const tag = (ev.target && ev.target.tagName || "").toLowerCase();
      if (tag === "input" || tag === "textarea" || tag === "select") return;
      clearFpPins();
      ev.preventDefault();
    }
  });
  let guideLastFocus = null;
    function openStrategyGuide() {
    applyDeskChrome();
    const gate = $("signal-guide-modal");
    const dialog = gate && gate.querySelector(".signal-guide-modal");
    if (!gate || !dialog) return;
    guideLastFocus = document.activeElement;
    gate.hidden = false;
    gate.setAttribute("aria-hidden", "false");
    requestAnimationFrame(function () {
      gate.classList.add("is-open");
      dialog.focus();
    });
  }
  function closeStrategyGuide() {
    const gate = $("signal-guide-modal");
    if (!gate || gate.hidden) return;
    gate.classList.remove("is-open");
    gate.setAttribute("aria-hidden", "true");
    window.setTimeout(function () {
      gate.hidden = true;
      if (guideLastFocus && typeof guideLastFocus.focus === "function") {
        guideLastFocus.focus();
      }
      guideLastFocus = null;
    }, 220);
  }
  const guideOpen = $("signal-guide-open");
  if (guideOpen) guideOpen.addEventListener("click", openStrategyGuide);
  const guideGate = $("signal-guide-modal");
  if (guideGate) {
    guideGate.querySelectorAll("[data-guide-close]").forEach(function (el) {
      el.addEventListener("click", closeStrategyGuide);
    });
    guideGate.querySelectorAll(".signal-guide-toc a").forEach(function (a) {
      a.addEventListener("click", function (ev) {
        const id = (a.getAttribute("href") || "").replace(/^#/, "");
        const target = id && document.getElementById(id);
        const body = guideGate.querySelector(".signal-guide-body");
        if (!target || !body) return;
        ev.preventDefault();
        const top = target.offsetTop - 8;
        body.scrollTo({ top: Math.max(0, top), behavior: "smooth" });
      });
    });
  }
  let pressureFabObserver = null;
  let pressureTargetVisible = true;

  function fmtStatusPx(v) {
    if (v == null || typeof v !== "number" || !isFinite(v)) return "—";
    const a = Math.abs(v);
    if (a >= 1000) return v.toFixed(0);
    if (a >= 100) return v.toFixed(2);
    return v.toFixed(2);
  }
  function sideRu(side) {
    if (side === "BUY") return "покупку";
    if (side === "SELL") return "продажу";
    return "";
  }
  function modeRu(mode) {
    const m = String(mode || "").toUpperCase();
    if (m === "BOUNCE") return "отскок";
    if (m === "RETEST") return "ретест";
    if (m === "BREAKOUT" || m === "BREAK") return "пробой";
    return "";
  }
  function zoneRu(lock, lv) {
    if (lock && lock.low != null && lock.high != null) {
      return fmtStatusPx(lock.low) + "–" + fmtStatusPx(lock.high);
    }
    if (lv && lv.entry != null) return "около " + fmtStatusPx(lv.entry);
    return "";
  }
  function looksTechnicalStatus(s) {
    const t = String(s || "");
    return /htf\s*=|htfSrc\s*=|§\d|grid\s*=|DOM@|size\s*[×x]|stopQty|ZERO\s*BARS|RANGE\s*:|BUY\s+\d+\s*\/\s*SELL/i.test(t);
  }
  function layoutStatusFabs() {
    const wind = $("signal-status-wind");
    const robot = $("signal-status-robot");
    const book = $("signal-pressure-fab");
    const gap = 10;
    const base = 20; // ~1.25rem
    let bottom = base;
    if (book && !book.hidden) {
      bottom += Math.max(book.getBoundingClientRect().height || 0, 48) + gap;
    }
    if (robot) {
      if (!book || book.hidden) robot.classList.add("is-solo");
      else robot.classList.remove("is-solo");
      robot.style.bottom = bottom + "px";
      bottom += Math.max(robot.getBoundingClientRect().height || 0, 64) + gap;
    }
    if (wind) {
      if (!book || book.hidden) wind.classList.add("is-solo-low");
      else wind.classList.remove("is-solo-low");
      wind.style.bottom = bottom + "px";
    }
  }
  function layoutRobotFab() {
    layoutStatusFabs();
  }
  function paintWindLeg(el, leg) {
    if (!el) return;
    const tf = (leg && leg.tf) || "—";
    const arrow = (leg && leg.arrow) || "↔";
    const label = (leg && leg.label) || "боковик";
    const tone = (leg && leg.tone) || "flat";
    el.classList.remove("is-gold", "is-black", "is-flat");
    el.classList.add(tone === "gold" ? "is-gold" : (tone === "black" ? "is-black" : "is-flat"));
    el.innerHTML = "<span class=\"k\">" + tf + "</span> " + arrow + " " + label;
  }
  function syncWindFab(data) {
    const windFab = $("signal-status-wind");
    if (!windFab) return;
    const sit = (data && data.situation) || {};
    const wind = sit.wind || {};
    paintWindLeg($("signal-wind-h1"), wind.h1);
    paintWindLeg($("signal-wind-h4"), wind.h4);
    paintWindLeg($("signal-wind-day"), wind.day);
    const sub = $("signal-status-wind-sub");
    const explain = wind.explain || "Ветер по часу / 4ч / дню ещё считается…";
    if (sub) sub.textContent = explain;
    windFab.title = explain;
    layoutStatusFabs();
  }
  function fairPaperLaneOpen(fp, pbId) {
    if (!fp) return null;
    if (fp.lanes && pbId && fp.lanes[pbId]) {
      return fp.lanes[pbId].open || null;
    }
    if (fp.lanes && pbId) return null;
    const open = fp.open || null;
    if (!open) return null;
    if (pbId && open.playbookId && open.playbookId !== pbId) return null;
    if (pbId && fp.playbookId && fp.playbookId !== pbId && fp.playbookId !== "both") return null;
    return open;
  }
  function buildRobotFabCopy(data) {
    const sit = (data && data.situation) || {};
    const plan = (data && data.plan) || {};
    const sig = (data && data.signal) || {};
    const fp = sit.fairPaper || ((data && data.fairPaper) || {});
    // Server posture is source of truth — do not override with a stale top-level fp.open.
    const posture = sit.posture || "SCANNING";
    const pbId = sit.playbookId
      || viewPlaybookId()
      || (data && data.parallelPlaybooks ? "levels-profile-br-m5" : (data && data.playbookId))
      || "";
    const laneOpen = fairPaperLaneOpen(fp, pbId);
    const lv = sit.setupLevels || {};
    const side = lv.side || sig.side || plan.side || "";
    const mode = lv.mode || plan.mode || sig.mode || "";
    const sideWord = sideRu(side);
    const modeWord = modeRu(mode);
    const zone = zoneRu(sit.activeLock, lv);
    const phase = sit.sessionPhaseRu || "";
    const rawWhy = sit.why || data.summary || plan.rationale || "";
    const whyHuman = humanizeDeskReason(rawWhy);
    const usableWhy = (whyHuman && !looksTechnicalStatus(whyHuman)) ? whyHuman : "";
    const comm = (data && data.commentary) || sit.commentary || {};
    const head = comm.headline || "";

    let cls = "is-scan";
    let status = "Сканирует";
    let detail = "Ищет сетап на графике";

    if (posture === "IN_TRADE") {
      cls = "is-trade";
      status = "В сделке";
      const open = laneOpen || {};
      const s = open.side || side || "";
      const avg = open.avg != null ? open.avg : lv.entry;
      const sl = open.sl != null ? open.sl : lv.stop;
      const qty = open.qty != null ? open.qty : lv.qty;
      const bits = [];
      if (s === "BUY") bits.push("покупка");
      else if (s === "SELL") bits.push("продажа");
      if (modeWord) bits.push(modeWord);
      if (open.tp1Done) bits.push("TP1 снят · стоп в БУ · ждём TP2");
      if (avg != null) bits.push("вход " + fmtStatusPx(avg));
      if (sl != null) bits.push("стоп " + fmtStatusPx(sl));
      if (qty != null) bits.push("×" + qty);
      detail = bits.join(" · ") || "Ведём позицию";
    } else if (posture === "WAITING_FILL") {
      cls = "is-armed";
      status = "Ждёт исполнения";
      const bits = [];
      if (sideWord) bits.push("лимитки на " + sideWord);
      if (modeWord) bits.push(modeWord);
      if (lv.near != null) bits.push("от " + fmtStatusPx(lv.near));
      else if (lv.entry != null) bits.push("около " + fmtStatusPx(lv.entry));
      if (lv.stop != null) bits.push("стоп " + fmtStatusPx(lv.stop));
      detail = bits.join(" · ") || "Лимитки выставлены — ждём fill";
    } else if (posture === "WATCHING_ZONE") {
      cls = "is-watch";
      status = "Смотрит зону";
      if (head) {
        detail = head;
      } else if (usableWhy) {
        detail = usableWhy;
      } else {
        const bits = [];
        if (sideWord) bits.push("готовит " + sideWord + (modeWord ? (" (" + modeWord + ")") : ""));
        else if (modeWord) bits.push(modeWord);
        if (zone) bits.push("зона " + zone);
        if (phase) bits.push("фаза: " + phase);
        detail = bits.join(" · ") || "Ждёт касание рабочей полки";
      }
    } else if (posture === "NOT_IN_TRADE") {
      cls = "is-flat";
      // Session-closed copy from situation.why — keep the same wording as plaques.
      if (usableWhy && (usableWhy.indexOf("сесси") >= 0 || usableWhy.indexOf("Сесси") >= 0
          || usableWhy.indexOf("открытия") >= 0 || usableWhy.indexOf("окна") >= 0)) {
        status = "Сессия закрыта";
      } else {
        status = "Не в сделке";
      }
      detail = head || usableWhy || "Нового сетапа сейчас нет";
    } else {
      cls = "is-scan";
      status = "Сканирует";
      if (head) {
        detail = head;
      } else if (usableWhy) {
        detail = usableWhy;
      } else {
        const bits = [];
        if (sideWord && modeWord) bits.push(modeWord + " на " + sideWord);
        else if (modeWord) bits.push(modeWord);
        if (zone) bits.push("зона " + zone);
        if (phase) bits.push("фаза: " + phase);
        detail = bits.length ? bits.join(" · ") : "Ищет сетап на графике";
      }
    }
    if (posture !== "IN_TRADE") {
      const closed = lastCloseBit(sit, fp, pbId);
      if (closed) detail = closed + " · " + detail;
    }
    if (detail.length > 220) detail = detail.slice(0, 218) + "…";
    return { cls: cls, status: status, detail: detail };
  }
  function syncStatusRail(data) {
    const robot = $("signal-status-robot");
    if (!robot) return;
    const copy = buildRobotFabCopy(data);
    const title = $("signal-status-robot-title");
    const sub = $("signal-status-robot-sub");
    robot.classList.remove("is-bid", "is-ask", "is-flat", "is-trade", "is-armed", "is-watch", "is-scan");
    robot.classList.add(copy.cls);
    if (title) title.textContent = copy.status;
    if (sub) sub.textContent = copy.detail;
    robot.title = copy.status + " — " + copy.detail;
    layoutRobotFab();
  }

  let pressureFabWasHidden = null;
  function syncDomPressureFab(data) {
    const fab = $("signal-pressure-fab");
    const fabText = $("signal-pressure-fab-text");
    const target = $("signal-dom-pressure");
    if (!fab) return;
    function notifyLayoutIfHiddenChanged() {
      const nowHidden = !!fab.hidden;
      if (pressureFabWasHidden === nowHidden) return;
      pressureFabWasHidden = nowHidden;
      if (window.TrinityPlaques && typeof window.TrinityPlaques.layout === "function") {
        window.TrinityPlaques.layout();
      } else {
        layoutRobotFab();
      }
    }
    if (!target) {
      fab.hidden = true;
      notifyLayoutIfHiddenChanged();
      return;
    }
    const sit = (data && data.situation) || {};
    const skew = sit.domSkew || 0;
    let cls = "is-flat";
    let label = "Баланс стакана";
    if (skew > 40) {
      cls = "is-bid";
      label = "Давление покупателей";
    } else if (skew < -40) {
      cls = "is-ask";
      label = "Давление продавцов";
    }
    fab.classList.remove("is-bid", "is-ask", "is-flat");
    fab.classList.add(cls);
    if (fabText && fabText.textContent !== label) fabText.textContent = label;
    fab.hidden = pressureTargetVisible;
    notifyLayoutIfHiddenChanged();
    if (!pressureFabObserver && typeof IntersectionObserver === "function") {
      pressureFabObserver = new IntersectionObserver(function (entries) {
        const e = entries[0];
        pressureTargetVisible = !!(e && e.isIntersecting && e.intersectionRatio > 0.15);
        const f = $("signal-pressure-fab");
        const t = $("signal-dom-pressure");
        if (!f) return;
        f.hidden = !t || pressureTargetVisible;
        notifyLayoutIfHiddenChanged();
      }, { root: null, threshold: [0, 0.15, 0.4] });
      pressureFabObserver.observe(target);
    } else if (pressureFabObserver && target && !target._plaqueObserved) {
      target._plaqueObserved = true;
      pressureFabObserver.observe(target);
    }
  }

  const statusWind = $("signal-status-wind");
  if (statusWind) {
    statusWind.addEventListener("click", function () {
      const brief = $("signal-desk-brief");
      if (brief && brief.scrollIntoView) brief.scrollIntoView({ behavior: "smooth", block: "start" });
    });
  }
  const statusRobot = $("signal-status-robot");
  if (statusRobot) {
    statusRobot.addEventListener("click", function () {
      const brief = $("signal-desk-brief");
      if (brief && brief.scrollIntoView) brief.scrollIntoView({ behavior: "smooth", block: "start" });
    });
  }

  const pressureFab = $("signal-pressure-fab");
  if (pressureFab) {
    pressureFab.addEventListener("click", function () {
      const target = $("signal-dom-pressure") || $("signal-desk-brief");
      if (!target || typeof target.scrollIntoView !== "function") return;
      target.scrollIntoView({ behavior: "smooth", block: "center" });
    });
  }

  let deskSelectsWired = false;
  let deskSaveInFlight = false;
  function fillDeskSelects(data) {
    const pbSel = $("sig-playbook");
    const instSel = $("sig-instrument");
    if (!pbSel || !instSel) return;
    const playbooks = data.playbooks || [];
    const instruments = data.instruments || [];
    const activePb = data.playbookId || "";
    const activeInst = data.instrument || "";
    if (pbSel.options.length === 0 && playbooks.length) {
      playbooks.forEach(function (p) {
        const opt = document.createElement("option");
        opt.value = p.id;
        opt.textContent = p.name || p.id;
        pbSel.appendChild(opt);
      });
    }
    if (instSel.options.length === 0 && instruments.length) {
      instruments.forEach(function (o) {
        const opt = document.createElement("option");
        opt.value = o.secid;
        opt.textContent = (o.name || o.family) + " · " + o.secid;
        opt.dataset.playbookIds = (o.playbookIds || []).join(",");
        instSel.appendChild(opt);
      });
    } else if (instruments.length) {
      instruments.forEach(function (o) {
        if (!o || !o.secid) return;
        const fam = String(o.family || o.secid).slice(0, 2).toUpperCase();
        for (let i = 0; i < instSel.options.length; i++) {
          const v = String(instSel.options[i].value || "").toUpperCase();
          if (v.indexOf(fam) === 0 && v !== String(o.secid).toUpperCase()) {
            instSel.options[i].value = o.secid;
            instSel.options[i].textContent = (o.name || o.family) + " · " + o.secid;
          }
        }
      });
    }
    // Don't clobber the select while a save is in flight (poll would snap back to "both").
    if (!deskSaveInFlight) {
      if (activePb) pbSel.value = activePb;
      if (deskInstrumentPinned) {
        matchInstrumentOption(instSel, deskInstrumentPinned);
        if (activeInst && sameInstrumentFamily(deskInstrumentPinned, activeInst)
            && String(deskInstrumentPinned).toUpperCase() !== String(activeInst).toUpperCase()) {
          matchInstrumentOption(instSel, activeInst);
          deskInstrumentPinned = activeInst;
          writeStoredInstrument(activeInst);
        }
      } else if (activeInst) {
        matchInstrumentOption(instSel, activeInst);
      }
    }
    const wantPb = viewPlaybookId();
    for (let i = 0; i < instSel.options.length; i++) {
      const ids = (instSel.options[i].dataset.playbookIds || "").split(",");
      const ok = !ids[0] || ids.indexOf(wantPb) >= 0;
      instSel.options[i].hidden = !ok;
      instSel.options[i].disabled = !ok;
    }
    const cur = instSel.options[instSel.selectedIndex];
    if (cur && (cur.hidden || cur.disabled)) {
      if (deskInstrumentPinned) {
        matchInstrumentOption(instSel, deskInstrumentPinned);
      } else {
        for (let i = 0; i < instSel.options.length; i++) {
          if (!instSel.options[i].hidden && !instSel.options[i].disabled) {
            instSel.value = instSel.options[i].value;
            break;
          }
        }
      }
    }
    if (!deskSelectsWired) {
      deskSelectsWired = true;
      instSel.addEventListener("change", function () {
        invalidateDeskFetch();
        lastDeskInstrument = "";
        lastOverlayKey = "";
        deskInstrumentPinned = instSel.value;
        writeStoredInstrument(instSel.value);
        scheduleSaveDeskLayout();
        try { if (candleSeries) candleSeries.setData([]); } catch (_) {}
        const tfHint = deskScope() === "positional" ? "H1" : "M5";
        setChartLegendSymbol(instSel.value, tfHint);
        const lab = $("signal-chart-label");
        if (lab) lab.textContent = "График · " + instSel.value + " · загрузка…";
        const persist = saveDeskSelection({ instrumentId: instSel.value }, { quiet: true });
        Promise.resolve(persist).finally(function () {
          loadDesk(true);
        });
      });
      if (window.TrinityPlaques && typeof window.TrinityPlaques.refresh === "function") {
        window.TrinityPlaques.refresh();
      }
    }
  }
  async function saveDeskSelection(patch, opts) {
    opts = opts || {};
    const quiet = !!opts.quiet;
    deskSaveInFlight = true;
    try {
      if (!hasDeskWriteAuth()) {
        if (patch.instrumentId) {
          deskInstrumentPinned = patch.instrumentId;
          writeStoredInstrument(patch.instrumentId);
        }
        if (patch.playbookId) writeStoredPlaybook(patch.playbookId);
        scheduleSaveDeskLayout();
        if (!quiet) {
          console.warn("saveDeskSelection: нет сессии — сохранено локально (instrument/playbook)");
        }
        return false;
      }
      const cur = await fetch("/api/trend/settings", { headers: { Accept: "application/json" } });
      const view = cur.ok ? await cur.json() : {};
      const body = {
        autoExecution: view.autoExecution,
        liveExecution: view.liveExecution,
        playbookId: patch.playbookId || view.playbookId,
        instrumentId: patch.instrumentId || view.instrumentId,
        positionalAutoExecution: view.positionalAutoExecution
      };
      const res = await fetch("/api/trend/settings", {
        method: "POST",
        headers: deskAuthHeaders({ "Content-Type": "application/json" }),
        body: JSON.stringify(body)
      });
      if (!res.ok) {
        const errBody = await res.json().catch(function () { return {}; });
        throw new Error(errBody.message || errBody.error || ("HTTP " + res.status));
      }
      if (window.TrinityPlaques && typeof window.TrinityPlaques.refresh === "function") {
        window.TrinityPlaques.refresh();
      }
      return true;
    } catch (e) {
      console.warn("saveDeskSelection failed", e);
      if (!quiet) {
        alert("Не удалось сменить плейбук/инструмент: " + (e && e.message ? e.message : e)
          + "\nВойдите в кабинет (/view) — POST /api/trend/settings требует авторизацию.");
      }
      return false;
    } finally {
      deskSaveInFlight = false;
    }
  }


  function scheduleSaveDeskLayout() {
    if (!window.TrinityChartKit) return;
    clearTimeout(deskLayoutTimer);
    deskLayoutTimer = setTimeout(persistDeskLayout, 500);
  }
  async function loadDeskLayoutOnce() {
    if (!window.TrinityChartKit) return;
    try {
      deskLayoutDoc = await TrinityChartKit.loadLayouts();
      const desk = deskLayoutDoc.desk || {};
      if (desk.instrument) deskInstrumentPinned = desk.instrument;
      const st = desk.tools || null;
      if (chartTools && st) chartTools.setState(st);
      const sc = desk.scale;
      if (!scaleLocked && lastCandleTime == null && sc && sc.barSpacing > 0
          && (!sc.instrument || sc.instrument === lastDeskInstrument)) {
        scaleLocked = true;
        lockedBarSpacing = sc.barSpacing;
        lockedLogical = sc.logical || null;
        restoreTimeScale(followLive && !userPinned);
      }
    } catch (e) {
      console.warn("chart layout load", e);
    }
  }
  async function persistDeskLayout() {
    if (!window.TrinityChartKit || !chartTools) return;
    try {
      const cur = deskLayoutDoc || await TrinityChartKit.loadLayouts();
      cur.desk = cur.desk || {};
      cur.desk.tools = chartTools.getState();
      cur.desk.instrument = ($("sig-instrument") && $("sig-instrument").value) || null;
      cur.desk.playbookId = ($("sig-playbook") && $("sig-playbook").value) || null;
      if (scaleLocked && lockedBarSpacing > 0) {
        cur.desk.scale = {
          instrument: lastDeskInstrument || null,
          barSpacing: lockedBarSpacing,
          logical: lockedLogical
        };
      }
      deskLayoutDoc = await TrinityChartKit.saveLayouts(cur);
    } catch (e) {
      console.warn("chart layout save", e);
    }
  }
  function syncDrawToolButtons() {
    const mode = chartTools ? chartTools.getMode() : null;
    setToolPressed("tool-vap-stretch", mode === "vap");
    setToolPressed("tool-trendline", mode === "trend");
    setToolPressed("tool-measure", !!(chartNav && chartNav.getMeasureMode && chartNav.getMeasureMode()));
  }


  const toolVap = $("tool-vap-stretch");
  if (toolVap) {
    toolVap.addEventListener("click", function () {
      if (!chartTools) return;
      const on = chartTools.getMode() !== "vap";
      if (on && fpToolActive) toggleFpTool();
      if (on) setMeasureTool(false);
      chartTools.setMode(on ? "vap" : null);
      syncDrawToolButtons();
    });
  }
  const toolTl = $("tool-trendline");
  if (toolTl) {
    toolTl.addEventListener("click", function () {
      if (!chartTools) return;
      const on = chartTools.getMode() !== "trend";
      if (on && fpToolActive) toggleFpTool();
      if (on) setMeasureTool(false);
      chartTools.setMode(on ? "trend" : null);
      syncDrawToolButtons();
    });
  }
  const toolMeasure = $("tool-measure");
  if (toolMeasure) {
    toolMeasure.addEventListener("click", function () {
      const on = !(chartNav && chartNav.getMeasureMode && chartNav.getMeasureMode());
      setMeasureTool(on);
    });
  }
  const toolMa = $("tool-ma");
  if (toolMa) {
    toolMa.addEventListener("click", function () {
      if (!chartTools || !window.TrinityChartKit) return;
      const cfg = TrinityChartKit.promptMaConfig({ type: "SMA", period: 20 });
      if (!cfg) return;
      chartTools.upsertMa(cfg);
      setToolPressed("tool-ma", true);
      scheduleSaveDeskLayout();
    });
    toolMa.addEventListener("contextmenu", function (ev) {
      ev.preventDefault();
      if (!chartTools) return;
      if (window.confirm("Убрать все скользящие средние с графика?")) {
        chartTools.clearMas();
        setToolPressed("tool-ma", false);
        scheduleSaveDeskLayout();
      }
    });
  }

  async function paintCachedChart() {
    if (!window.TrinityChartKit || typeof TrinityChartKit.barCacheGet !== "function") return false;
    const instSel = $("sig-instrument");
    const want = (instSel && instSel.value) || deskInstrumentPinned || readStoredInstrument() || "";
    const tf = deskScope() === "positional" ? "H1" : "M5";
    const row = await TrinityChartKit.barCacheGet(want || "_last", tf);
    if (!row || !row.bars || !row.bars.length) return false;
    const inst = row.instrument || want || "BR";
    if (want && inst && typeof sameInstrumentFamily === "function" && !sameInstrumentFamily(want, inst)) {
      return false;
    }
    ensureChart(inst, row.pointSize);
    const candles = row.bars.map(function (b) {
      if (!b || b.time == null) return null;
      if (typeof b.time === "number") {
        return { time: b.time, open: b.open, high: b.high, low: b.low, close: b.close };
      }
      const t = toChartTime(b.time);
      if (t == null) return null;
      return { time: t, open: b.open, high: b.high, low: b.low, close: b.close, volume: b.volume };
    }).filter(Boolean);
    if (!candles.length) return false;
    if (inst && inst !== lastDeskInstrument) {
      lastDeskInstrument = inst;
      adoptSavedScale(inst);
    }
    lastChartTf = row.tf || tf || "M5";
    setChartLegendSymbol(inst, lastChartTf);
    if (row.raw && row.raw.length) lastBarsRaw = row.raw;
    lastCandlesLen = 0;
    lastCandleTime = null;
    updateCandles(candles, false);
    refreshImpulseUi(candles);
    const chartLabel = $("signal-chart-label");
    if (chartLabel) {
      chartLabel.textContent = "График · " + inst + " " + lastChartTf + " · локальный архив";
    }
    const meta = $("signal-desk-meta");
    if (meta) {
      meta.textContent = "локальный архив · " + candles.length + " свечей · ждём сервер…";
    }
    return true;
  }

  async function applyUrlPlaybookOnce() {
    try {
      const q = new URLSearchParams(location.search);
      const pb = (q.get("playbook") || "").trim();
      if (!pb || pb === "pairs-daily") return;
      if (pb.indexOf("levels-profile") < 0 && pb.indexOf("positional") < 0 && pb !== "both") return;
      if (deskScope() === "positional") {
        return;
      }
      if (pb.indexOf("positional") >= 0) {
        location.replace("/view/trend-positional");
        return;
      }
      // Hard refresh with ?playbook= must not pop auth modal — persist only when session exists.
      // Dedicated desks keep fair-paper on `both`; do not persist levels-only.
      if (pb === "both") {
        await saveDeskSelection({ playbookId: pb }, { quiet: true });
      }
    } catch (_) {}
  }

  async function bootDesk() {
    applyDeskChrome();
    try {
      if (window.TrinityChartKit) {
        await loadDeskLayoutOnce();
      }
    } catch (_) {}
    if (!deskInstrumentPinned) {
      const ls = readStoredInstrument();
      if (ls) deskInstrumentPinned = ls;
    }
    applyUrlInstrumentOnce();
    const hadCache = await paintCachedChart();
    try {
      await applyUrlPlaybookOnce();
    } catch (_) {}
    applyBootInstrumentFromStorage();
    await loadDesk(!hadCache);
    loadBook();
    (function pollDeskLoop() {
      setTimeout(function () {
        loadDesk(false).finally(pollDeskLoop);
      }, (lastWorkingOpen || liveFlatUntil > Date.now()) ? DESK_LIVE_MS : DESK_MS);
    })();
    (function pollBookLoop() {
      setTimeout(function () {
        Promise.resolve(loadBook()).finally(pollBookLoop);
      }, (lastWorkingOpen || liveFlatUntil > Date.now()) ? BOOK_LIVE_MS : BOOK_MS);
    })();
  }

  bootDesk();
})();