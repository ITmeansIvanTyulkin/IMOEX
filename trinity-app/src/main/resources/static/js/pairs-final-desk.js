(function () {
  const desk = document.getElementById("pairs-desk");
  if (!desk) return;

  const SECTORS = [
    { id: "OIL_GAS", ru: "нефть" },
    { id: "METALS_MINING", ru: "металлы" },
    { id: "BANKS", ru: "банки" },
    { id: "RETAIL", ru: "ритейл" }
  ];
  const SECTOR_WATCH = {
    OIL_GAS: "GAZP/LKOH",
    METALS_MINING: "NLMK/GMKN",
    BANKS: "SBER/VTBR",
    RETAIL: "MGNT/FIVE"
  };
  const FILTER_COPY = {
    ALL: "Все итоговые строки",
    ENTER: "Только вход (ENTER / REDUCE_SIZE)",
    WATCH: "Только наблюдение (WATCH)",
    BLOCK: "Только блок (BLOCK)"
  };
  const SB_TOKEN_KEY = "trinity.supabase.access_token";
  const USER_KEY = "imoex.ops.user";
  const PASS_KEY = "imoex.ops.pass";

  let filterKind = "ALL";
  let lastChampion = "";
  let lastSitOut = false;
  let lastAdx = NaN;
  let lastAdxLabel = "—";
  let adxNeedle = 0;
  let adxAnim = null;
  let userWatchSector = "";
  let sectorHits = [];
  let lastReview = null;

  function $(id) {
    return document.getElementById(id);
  }

  function authHeader() {
    const token = localStorage.getItem(SB_TOKEN_KEY);
    if (token) return "Bearer " + token;
    const u = localStorage.getItem(USER_KEY);
    const p = localStorage.getItem(PASS_KEY);
    if (u && p) return "Basic " + btoa(unescape(encodeURIComponent(u + ":" + p)));
    return "";
  }

  function headers() {
    const h = { Accept: "application/json" };
    const a = authHeader();
    if (a) h.Authorization = a;
    return h;
  }

  async function getJson(path) {
    const ms = (window.TrinityFastBoot && TrinityFastBoot.DESK_MS) || 25000;
    try {
      if (window.TrinityFastBoot && typeof TrinityFastBoot.fetchAbort === "function") {
        let res = await TrinityFastBoot.fetchAbort(path, {
          ms: ms,
          headers: headers(),
          credentials: "same-origin"
        });
        if (res.status === 401 || res.status === 403) {
          res = await TrinityFastBoot.fetchAbort(path, {
            ms: ms,
            headers: { Accept: "application/json" },
            credentials: "same-origin"
          });
        }
        if (!res.ok) return null;
        return res.json();
      }
      let res = await fetch(path, { headers: headers() });
      if (res.status === 401 || res.status === 403) {
        res = await fetch(path, { headers: { Accept: "application/json" } });
      }
      if (!res.ok) return null;
      return res.json();
    } catch (_) {
      return null;
    }
  }

  function parseAdx(v) {
    if (v == null || v === "" || v === "NaN") return NaN;
    const n = Number(v);
    return isFinite(n) ? n : NaN;
  }

  function tickClock() {
    const el = $("pairs-desk-clock");
    if (!el) return;
    const now = new Date();
    el.textContent = now.toLocaleTimeString("ru-RU", {
      timeZone: "Europe/Moscow",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit"
    }) + " МСК";
  }

  function fitCanvas(canvas) {
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.clientWidth || 640;
    const h = canvas.clientHeight || 160;
    canvas.width = Math.round(w * dpr);
    canvas.height = Math.round(h * dpr);
    const ctx = canvas.getContext("2d");
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    return { ctx: ctx, w: w, h: h };
  }

  function drawSectors(review) {
    const canvas = $("pairs-sector-chart");
    if (!canvas) return;
    lastReview = review || lastReview;
    const { ctx, w, h } = fitCanvas(canvas);
    ctx.clearRect(0, 0, w, h);
    sectorHits = [];
    const rows = (review && review.sectors) || [];
    const byId = {};
    rows.forEach(function (s) { if (s && s.sector) byId[s.sector] = s; });
    const champ = review && review.champion;
    const values = SECTORS.map(function (s) {
      const r = byId[s.id] || {};
      return Math.max(0, Number(r.qualityPairs || 0) + Number(r.researchScore || 0) / 10);
    });
    const max = Math.max(1, Math.max.apply(null, values));
    const pad = 28;
    const slot = (w - pad * 2) / SECTORS.length;
    const barW = slot * 0.55;
    SECTORS.forEach(function (s, i) {
      const r = byId[s.id] || {};
      const x = pad + (i + 0.5) * slot;
      const bh = (values[i] / max) * (h - 48);
      const y = h - 24 - bh;
      const selected = (userWatchSector || champ) === s.id;
      ctx.fillStyle = s.id === champ ? "#0b7a66" : "rgba(126, 182, 212, 0.65)";
      ctx.fillRect(x - barW / 2, y, barW, Math.max(2, bh));
      if (s.id === champ) {
        ctx.strokeStyle = "#096556";
        ctx.lineWidth = 1.5;
        ctx.strokeRect(x - barW / 2 - 3, y - 3, barW + 6, Math.max(2, bh) + 6);
      }
      if (userWatchSector && userWatchSector === s.id && s.id !== champ) {
        ctx.strokeStyle = "#1e2a32";
        ctx.setLineDash([3, 2]);
        ctx.lineWidth = 1;
        ctx.strokeRect(x - barW / 2 - 3, y - 3, barW + 6, Math.max(2, bh) + 6);
        ctx.setLineDash([]);
      }
      ctx.fillStyle = selected ? "#1e2a32" : "#6a7680";
      ctx.font = "11px IBM Plex Sans, sans-serif";
      ctx.textAlign = "center";
      ctx.fillText(s.ru, x, h - 8);
      ctx.fillStyle = "#1e2a32";
      ctx.fillText(String(r.qualityPairs || 0), x, y - 6);
      sectorHits.push({ id: s.id, x0: pad + i * slot, x1: pad + (i + 1) * slot });
    });
  }

  function drawZ(points, note) {
    const canvas = $("pairs-z-chart");
    if (!canvas) return;
    const { ctx, w, h } = fitCanvas(canvas);
    ctx.clearRect(0, 0, w, h);
    ctx.strokeStyle = "rgba(30, 42, 50, 0.08)";
    ctx.beginPath();
    ctx.moveTo(8, h / 2);
    ctx.lineTo(w - 8, h / 2);
    ctx.stroke();
    const zs = (points || []).map(function (p) { return Number(p.value); }).filter(function (v) {
      return isFinite(v);
    });
    if (!zs.length) {
      ctx.fillStyle = "#6a7680";
      ctx.font = "13px IBM Plex Sans, sans-serif";
      ctx.textAlign = "left";
      ctx.fillText(note || "Нет ряда Z — нажмите «Анализ + paper»", 16, h / 2);
      return;
    }
    const slice = zs.slice(-90);
    let lo = -2.5;
    let hi = 2.5;
    slice.forEach(function (v) {
      lo = Math.min(lo, v);
      hi = Math.max(hi, v);
    });
    const span = hi - lo || 1;
    function yOf(v) {
      return 10 + (1 - (v - lo) / span) * (h - 20);
    }
    ctx.strokeStyle = "rgba(197, 48, 48, 0.35)";
    ctx.setLineDash([4, 4]);
    ctx.beginPath();
    ctx.moveTo(8, yOf(2));
    ctx.lineTo(w - 8, yOf(2));
    ctx.moveTo(8, yOf(-2));
    ctx.lineTo(w - 8, yOf(-2));
    ctx.stroke();
    ctx.setLineDash([]);
    ctx.strokeStyle = "#0b7a66";
    ctx.lineWidth = 1.8;
    ctx.beginPath();
    slice.forEach(function (v, i) {
      const x = 8 + i * ((w - 16) / Math.max(1, slice.length - 1));
      const y = yOf(v);
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    });
    ctx.stroke();
    const last = slice[slice.length - 1];
    ctx.fillStyle = "#1e2a32";
    ctx.font = "12px IBM Plex Mono, monospace";
    ctx.textAlign = "left";
    ctx.fillText("Z " + last.toFixed(2), 12, 18);
    if (note) {
      ctx.fillStyle = "#6a7680";
      ctx.font = "11px IBM Plex Sans, sans-serif";
      ctx.fillText(note, 12, h - 8);
    }
  }

  function paintAdx(adx, label, needleT) {
    const canvas = $("pairs-adx-chart");
    if (!canvas) return;
    const { ctx, w, h } = fitCanvas(canvas);
    ctx.clearRect(0, 0, w, h);
    const cx = w * 0.38;
    const cy = h * 0.78;
    const r = Math.min(w, h) * 0.44;
    function arc(from, to, color, width) {
      ctx.beginPath();
      ctx.strokeStyle = color;
      ctx.lineWidth = width;
      ctx.lineCap = "butt";
      ctx.arc(cx, cy, r, Math.PI + from * Math.PI, Math.PI + to * Math.PI);
      ctx.stroke();
    }
    arc(0, 0.5, "rgba(31, 122, 69, 0.35)", 14);
    arc(0.5, 0.625, "rgba(183, 121, 31, 0.45)", 14);
    arc(0.625, 1, "rgba(197, 48, 48, 0.4)", 14);
    const t = Math.max(0, Math.min(1, needleT));
    const ang = Math.PI + t * Math.PI;
    const tipR = r - 2;
    const baseR = 10;
    const tipX = cx + Math.cos(ang) * tipR;
    const tipY = cy + Math.sin(ang) * tipR;
    const nx = Math.cos(ang + Math.PI / 2);
    const ny = Math.sin(ang + Math.PI / 2);
    const bx = cx + Math.cos(ang) * baseR;
    const by = cy + Math.sin(ang) * baseR;
    ctx.beginPath();
    ctx.moveTo(tipX, tipY);
    ctx.lineTo(bx + nx * 5, by + ny * 5);
    ctx.lineTo(bx - nx * 5, by - ny * 5);
    ctx.closePath();
    ctx.fillStyle = "#1e2a32";
    ctx.fill();
    ctx.beginPath();
    ctx.arc(cx, cy, 6, 0, Math.PI * 2);
    ctx.fillStyle = "#1e2a32";
    ctx.fill();
    ctx.beginPath();
    ctx.arc(cx, cy, 2.5, 0, Math.PI * 2);
    ctx.fillStyle = "#fff";
    ctx.fill();
    const known = isFinite(adx);
    if (!known) ctx.globalAlpha = 0.35;
    ctx.fillStyle = "#1e2a32";
    ctx.font = "28px IBM Plex Sans, sans-serif";
    ctx.textAlign = "center";
    ctx.fillText(known ? String(Math.round(adx)) : "—", cx, cy - 18);
    ctx.globalAlpha = 1;
    ctx.fillStyle = "#6a7680";
    ctx.font = "13px IBM Plex Sans, sans-serif";
    ctx.fillText(known ? (label || "—") : "считаю IMOEX…", cx, cy + 22);
    ctx.textAlign = "left";
    ctx.font = "11px IBM Plex Sans, sans-serif";
    ctx.fillStyle = "#6a7680";
    ctx.fillText("SIDEWAYS", w * 0.64, h * 0.32);
    ctx.fillText("NEUTRAL", w * 0.64, h * 0.46);
    ctx.fillText("TREND", w * 0.64, h * 0.6);
    ctx.fillStyle = "#1f7a45";
    ctx.fillRect(w * 0.64 - 12, h * 0.32 - 8, 8, 8);
    ctx.fillStyle = "#b7791f";
    ctx.fillRect(w * 0.64 - 12, h * 0.46 - 8, 8, 8);
    ctx.fillStyle = "#c53030";
    ctx.fillRect(w * 0.64 - 12, h * 0.6 - 8, 8, 8);
  }

  function drawAdx(adx, label) {
    lastAdx = adx;
    lastAdxLabel = label || "—";
    const target = isFinite(adx) ? Math.max(0, Math.min(40, adx)) / 40 : 0;
    if (adxAnim) cancelAnimationFrame(adxAnim);
    const from = adxNeedle;
    const start = performance.now();
    const dur = 420;
    function step(now) {
      const p = Math.min(1, (now - start) / dur);
      const ease = 1 - Math.pow(1 - p, 3);
      adxNeedle = from + (target - from) * ease;
      paintAdx(lastAdx, lastAdxLabel, adxNeedle);
      if (p < 1) adxAnim = requestAnimationFrame(step);
      else adxAnim = null;
    }
    adxAnim = requestAnimationFrame(step);
  }

  function renderLegs(yTicker, xTicker, candlesY, candlesX) {
    function fill(id, headerId, ticker, bars) {
      const h = $(headerId);
      const ol = $(id);
      if (h) h.textContent = ticker || "—";
      if (!ol) return;
      const last = (bars || []).slice(-8).reverse();
      ol.innerHTML = last.map(function (b, i) {
        const prev = last[i + 1];
        const cls = prev && b.close >= prev.close ? "up" : (prev && b.close < prev.close ? "dn" : "");
        const t = String(b.time || "").slice(5, 10);
        return "<li><span>" + t + "</span><span class=\"" + cls + "\">"
          + Number(b.close).toFixed(2) + "</span></li>";
      }).join("") || "<li><span>нет свечей</span></li>";
    }
    fill("pairs-leg-y", "pairs-leg-y-h", yTicker, candlesY);
    fill("pairs-leg-x", "pairs-leg-x-h", xTicker, candlesX);
  }

  function sectorFallback(champion) {
    return SECTOR_WATCH[champion] || SECTOR_WATCH.OIL_GAS;
  }

  function sectorRu(id) {
    const s = SECTORS.find(function (x) { return x.id === id; });
    return s ? s.ru : id;
  }

  function watchNote(sectorId) {
    const ru = sectorRu(sectorId);
    if (lastSitOut) {
      return "watch " + ru + " · не вход: sit-out, 0 коинтегрированных";
    }
    return "watch " + ru + " · пороги ±2";
  }

  function renderZSwitch() {
    const box = $("pairs-z-switch");
    if (!box) return;
    const active = userWatchSector || lastChampion || "OIL_GAS";
    box.innerHTML = SECTORS.map(function (s) {
      const on = s.id === active ? " is-on" : "";
      return "<button type=\"button\" data-sector=\"" + s.id + "\" class=\"" + on.trim() + "\">"
        + s.ru + " · " + SECTOR_WATCH[s.id] + "</button>";
    }).join("");
    box.querySelectorAll("button").forEach(function (btn) {
      btn.addEventListener("click", function () {
        selectSectorWatch(btn.getAttribute("data-sector"));
      });
    });
  }

  function selectSectorWatch(sectorId) {
    if (!sectorId || !SECTOR_WATCH[sectorId]) return;
    userWatchSector = sectorId;
    const picked = { pair: SECTOR_WATCH[sectorId], note: watchNote(sectorId), sector: sectorId };
    desk.setAttribute("data-watch", picked.pair);
    renderZSwitch();
    if (lastReview) drawSectors(lastReview);
    loadWatch(picked);
  }

  function pickWatch(report, recs, finals, champion) {
    if (userWatchSector && SECTOR_WATCH[userWatchSector]) {
      return { pair: SECTOR_WATCH[userWatchSector], note: watchNote(userWatchSector), sector: userWatchSector };
    }
    const rows = document.querySelectorAll("#pairs-final-table tbody tr[data-decision]");
    const visible = [];
    rows.forEach(function (tr) {
      if (tr.style.display === "none") return;
      const pair = tr.getAttribute("data-pair");
      if (pair) visible.push(pair);
    });
    if (visible.length) return { pair: visible[0], note: "из фильтра · " + (FILTER_COPY[filterKind] || "") };

    if (finals && finals.length) {
      const f = finals[0];
      const t = f.technical || f;
      return { pair: (t.tickerY || f.tickerY || "") + "/" + (t.tickerX || f.tickerX || ""), note: "итог" };
    }
    if (recs && recs.length) {
      return { pair: (recs[0].tickerY || "") + "/" + (recs[0].tickerX || ""), note: "техсигнал" };
    }
    const top = report && report.topPairs;
    if (top && top.length) {
      return { pair: (top[0].tickerY || "") + "/" + (top[0].tickerX || ""), note: "топ research" };
    }
    const sector = champion || lastChampion || "OIL_GAS";
    return { pair: sectorFallback(sector), note: watchNote(sector), sector: sector };
  }

  async function loadWatch(picked) {
    const pair = picked && picked.pair;
    const note = (picked && picked.note) || "";
    if (!pair || pair.indexOf("/") < 0) {
      drawZ([], "Нет пары для Z");
      renderLegs("Y", "X", [], []);
      if ($("pairs-z-pair")) $("pairs-z-pair").textContent = "—";
      return;
    }
    const parts = pair.split("/");
    if ($("pairs-z-pair")) $("pairs-z-pair").textContent = pair;
    const path = "/api/charts/" + encodeURIComponent(parts[0]) + "/"
      + encodeURIComponent(parts[1]) + "/data";
    let res = await fetch(path, { headers: headers() });
    if (res.status === 401 || res.status === 403) {
      res = await fetch(path, { headers: { Accept: "application/json" } });
    }
    if (!res.ok) {
      drawZ([], "Нет данных графика для " + pair + " (HTTP " + res.status + ")");
      renderLegs(parts[0], parts[1], [], []);
      return;
    }
    const data = await res.json();
    if (!data || !(data.zScore && data.zScore.length)) {
      drawZ([], "Пустой ряд Z для " + pair);
      renderLegs(parts[0], parts[1], data && data.candlesY, data && data.candlesX);
      return;
    }
    drawZ(data.zScore, note);
    renderLegs(parts[0], parts[1], data.candlesY, data.candlesX);
  }

  function decisionMatch(d, kind) {
    if (kind === "ALL") return true;
    if (kind === "ENTER") return d === "ENTER" || d === "REDUCE_SIZE";
    if (kind === "WATCH") return d === "WATCH";
    if (kind === "BLOCK") return d === "BLOCK";
    return true;
  }

  function countByFilter() {
    const rows = document.querySelectorAll("#pairs-final-table tbody tr[data-decision]");
    const counts = { ALL: 0, ENTER: 0, WATCH: 0, BLOCK: 0 };
    rows.forEach(function (tr) {
      const d = tr.getAttribute("data-decision") || "";
      counts.ALL += 1;
      if (d === "ENTER" || d === "REDUCE_SIZE") counts.ENTER += 1;
      else if (d === "WATCH") counts.WATCH += 1;
      else if (d === "BLOCK") counts.BLOCK += 1;
    });
    return counts;
  }

  function updateFilterStatus(kind, counts) {
    const el = $("pairs-filter-status");
    if (!el) return;
    const total = counts.ALL;
    const shown = kind === "ALL" ? total : counts[kind] || 0;
    let extra = "";
    if (lastSitOut) extra = " · сейчас sit-out (слоты пустые)";
    else if (lastChampion) extra = " · фаворит: " + lastChampion;
    if (total === 0) {
      el.innerHTML = "<strong>" + (FILTER_COPY[kind] || kind) + "</strong>: итоговых строк нет"
        + (extra || "") + ". Фильтр готов — строки появятся после прогона с ENTER/WATCH/BLOCK.";
      return;
    }
    if (shown === 0) {
      el.innerHTML = "<strong>" + (FILTER_COPY[kind] || kind) + "</strong>: 0 из " + total
        + " строк" + extra + ".";
      return;
    }
    el.innerHTML = "<strong>" + (FILTER_COPY[kind] || kind) + "</strong>: показано "
      + shown + " из " + total + extra + ".";
  }

  function applyFilter(kind, opts) {
    filterKind = kind || "ALL";
    document.querySelectorAll(".pairs-chip").forEach(function (b) {
      b.classList.toggle("is-on", b.getAttribute("data-filter") === filterKind);
    });
    const rows = document.querySelectorAll("#pairs-final-table tbody tr[data-decision]");
    let visible = 0;
    rows.forEach(function (tr) {
      const d = tr.getAttribute("data-decision") || "";
      const show = decisionMatch(d, filterKind);
      tr.style.display = show ? "" : "none";
      if (show) visible += 1;
    });
    const empty = $("pairs-final-empty-row");
    if (empty) {
      empty.style.display = rows.length === 0 || visible === 0 ? "" : "none";
      const msg = empty.querySelector("[data-empty-msg]");
      if (msg) {
        if (rows.length === 0) {
          msg.textContent = lastSitOut
            ? "Таблица пуста: sit-out — нет слотов под вход. Research по секторам смотрите на пульте выше."
            : "Таблица пуста: нет итоговых строк после FA. Запустите «Анализ + paper».";
        } else {
          msg.textContent = "По фильтру «" + (FILTER_COPY[filterKind] || filterKind)
            + "» нет строк. Переключите на «Все» или другой статус.";
        }
      }
    }
    const counts = countByFilter();
    updateFilterStatus(filterKind, counts);
    desk.setAttribute("data-filter", filterKind);
    if (!opts || opts.reloadWatch !== false) {
      const picked = pickWatch(null, null, null, lastChampion);
      loadWatch(picked);
    }
  }

  function syncModeSwitch(on) {
    const toggle = $("pairs-auto-execution");
    if (!toggle) return;
    toggle.checked = !!on;
    toggle.setAttribute("aria-checked", on ? "true" : "false");
    const wrap = toggle.closest(".mode-switch");
    if (wrap) {
      wrap.classList.toggle("is-auto", !!on);
      wrap.classList.toggle("is-signal", !on);
    }
  }

  async function loadDelivery() {
    const toggle = $("pairs-auto-execution");
    if (!toggle) return;
    const view = await getJson("/api/broker/settings");
    if (!view) {
      syncModeSwitch(false);
      return;
    }
    syncModeSwitch(!!view.autoExecuteAfterAnalysis);
    const hint = $("pairs-delivery-hint");
    if (hint) {
      hint.textContent = toggle.checked
        ? "Авто: после анализа журнал + заявки, если брокер готов."
        : "Наблюдение: журнал пишется, заявок брокеру нет.";
    }
  }

  async function setDelivery(on) {
    const hint = $("pairs-delivery-hint");
    syncModeSwitch(on);
    try {
      const res = await fetch("/api/broker/settings", {
        method: "POST",
        headers: Object.assign({ "Content-Type": "application/json" }, headers()),
        body: JSON.stringify({ autoExecuteAfterAnalysis: !!on })
      });
      if (!res.ok) throw new Error("settings " + res.status);
      if (hint) hint.textContent = on
        ? "Авто включён. Живые заявки всё равно требуют готового брокера."
        : "Наблюдение: только журнал, без авто-заявок.";
    } catch (e) {
      syncModeSwitch(!on);
      if (hint) hint.textContent = "Не удалось сохранить режим: " + e.message;
    }
  }

  async function refresh() {
    tickClock();
    const [review, report, recs, finals, regime, paper] = await Promise.all([
      getJson("/api/analysis/cluster-review"),
      getJson("/api/analysis/report"),
      getJson("/api/analysis/recommendations"),
      getJson("/api/analysis/final"),
      getJson("/api/analysis/regime"),
      getJson("/api/paper/journal")
    ]);

    if (review) {
      lastChampion = review.champion || "";
      lastSitOut = !!review.sitOut;
      if (!userWatchSector && lastChampion) userWatchSector = lastChampion;
      drawSectors(review);
      const name = $("pairs-champ-name");
      const meta = $("pairs-champ-meta");
      const lead = $("pairs-desk-lead");
      const ru = { OIL_GAS: "нефть", METALS_MINING: "металлы", BANKS: "банки", RETAIL: "ритейл" };
      if (name) name.textContent = review.sitOut ? "sit-out" : (ru[review.champion] || review.champion || "—");
      if (meta) meta.textContent = review.sitOut ? "слоты пустые" : "торгуем только этот сектор";
      if (lead && review.championReason) lead.textContent = review.championReason;
      desk.setAttribute("data-posture", review.sitOut ? "sitout" : "scan");
      if (!desk.getAttribute("data-watch")) {
        desk.setAttribute("data-watch", sectorFallback(review.champion));
      }
    }
    if (report) {
      if ($("pairs-scan-coint")) $("pairs-scan-coint").textContent = (report.cointegratedPairs || 0) + " коинт.";
      if ($("pairs-scan-meta")) {
        $("pairs-scan-meta").textContent = (report.tickersAnalyzed || 0) + " акций · "
          + (report.pairsTested || 0) + " пар";
      }
    }
    if (regime) {
      const adx = parseAdx(regime.adx);
      if ($("pairs-regime-label")) $("pairs-regime-label").textContent = regime.label || "—";
      if ($("pairs-regime-adx")) {
        $("pairs-regime-adx").textContent = isFinite(adx) ? String(Math.round(adx)) : "—";
      }
      drawAdx(adx, regime.label);
    }
    if (paper) {
      const open = (paper.entries || []).filter(function (e) {
        return e && String(e.status || "").toUpperCase() === "OPEN";
      }).length;
      if ($("pairs-paper-open")) $("pairs-paper-open").textContent = "открыто " + open;
      const pnl = (paper.realizedPnlRub || 0) + (paper.unrealizedPnlRub || 0);
      if ($("pairs-paper-meta")) {
        $("pairs-paper-meta").textContent = isFinite(pnl)
          ? ("PnL " + Math.round(pnl) + " ₽")
          : "журнал DAILY";
      }
    }
    applyFilter(filterKind, { reloadWatch: false });
    renderZSwitch();
    const picked = pickWatch(report, recs, finals, lastChampion);
    if (picked.pair) desk.setAttribute("data-watch", picked.pair);
    await loadWatch(picked);
  }

  document.querySelectorAll(".pairs-chip").forEach(function (btn) {
    btn.addEventListener("click", function () {
      applyFilter(btn.getAttribute("data-filter") || "ALL");
    });
  });
  const sectorCanvas = $("pairs-sector-chart");
  if (sectorCanvas) {
    sectorCanvas.addEventListener("click", function (ev) {
      const rect = sectorCanvas.getBoundingClientRect();
      const x = ev.clientX - rect.left;
      const hit = sectorHits.find(function (s) { return x >= s.x0 && x < s.x1; });
      if (hit) selectSectorWatch(hit.id);
    });
  }
  const toggle = $("pairs-auto-execution");
  if (toggle) {
    const wrap = toggle.closest(".mode-switch");
    if (wrap && !wrap.classList.contains("is-auto") && !wrap.classList.contains("is-signal")) {
      wrap.classList.add("is-signal");
    }
    toggle.addEventListener("change", function () {
      setDelivery(toggle.checked);
    });
  }

  function bindPairsGuide() {
    let lastFocus = null;
    function openGuide() {
      const gate = $("pairs-guide-modal");
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
      const gate = $("pairs-guide-modal");
      if (!gate || gate.hidden) return;
      gate.classList.remove("is-open");
      gate.setAttribute("aria-hidden", "true");
      window.setTimeout(function () {
        gate.hidden = true;
        if (lastFocus && typeof lastFocus.focus === "function") lastFocus.focus();
        lastFocus = null;
      }, 220);
    }
    const openBtn = $("pairs-guide-open");
    if (openBtn) openBtn.addEventListener("click", openGuide);
    const gate = $("pairs-guide-modal");
    if (gate) {
      gate.querySelectorAll("[data-pairs-guide-close]").forEach(function (el) {
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
      const g = $("pairs-guide-modal");
      if (g && !g.hidden) {
        closeGuide();
        ev.preventDefault();
      }
    });
  }

  bindPairsGuide();
  tickClock();
  setInterval(tickClock, 1000);

  try {
    const bootEl = $("pairs-desk-boot");
    if (bootEl && bootEl.textContent) {
      const boot = JSON.parse(bootEl.textContent);
      lastChampion = boot.champion || "";
      lastSitOut = !!boot.sitOut;
      if (!userWatchSector && lastChampion) userWatchSector = lastChampion;
      drawSectors(boot);
      drawAdx(parseAdx(boot.adx), boot.label);
      renderZSwitch();
      if (!desk.getAttribute("data-watch") && boot.champion) {
        desk.setAttribute("data-watch", sectorFallback(boot.champion));
      }
    }
  } catch (_) { /* ignore */ }

  applyFilter("ALL", { reloadWatch: false });
  loadDelivery();
  refresh();
  setInterval(refresh, 8000);
  window.addEventListener("resize", function () {
    if (lastReview) drawSectors(lastReview);
    drawAdx(lastAdx, lastAdxLabel);
  });
})();
