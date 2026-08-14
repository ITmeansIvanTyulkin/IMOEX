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
  let chartNeedsFit = false;
  let userPinned = false;
  let followLive = true;
  let applyingScale = false;
  let scaleLocked = false;
  let lockedBarSpacing = null;
  let lockedLogical = null;
  const SCALE_STORE = "trinity.trend.desk.scale";
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
  let lastDivMarkersKey = "";
  let lastDeskInstrument = "";
  let chartTools = null;
  let deskLayoutDoc = null;
  let deskLayoutTimer = null;
  let domFollowMid = true;
  let domScrollBound = false;
  const DESK_MS = 20000;
  const BOOK_MS = 8000;
  const FP_PIN_MAX = 8;
  const MACD_FAST = 12;
  const MACD_SLOW = 26;
  const MACD_SIGNAL = 9;
  const RIGHT_PAD_ON = 22;
  const RIGHT_PAD_OFF = 4;
  const HI_LO_COLOR = "#b91c1c";
  const ZONE_EDGE = "#6d28d9";

  function $(id) { return document.getElementById(id); }
  function deMark(s) {
    return String(s == null ? "" : s).replace(/§\s*/g, "").replace(/\s+/g, " ").trim();
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
    const wantPb = desk && desk.playbookId;
    const rows = raw.filter(function (t) {
      if (!isSameMskDay(t && (t.closedAt || t.openedAt), today)) return false;
      if (wantInst && t.instrument && !sameInstrumentFamily(t.instrument, wantInst)) return false;
      if (wantPb && wantPb !== "both") {
        const pb = playbookFromTrade(t);
        if (pb && pb !== wantPb) return false;
      }
      return true;
    });
    if (!panel || !body) return;
    if (!rows.length) {
      panel.hidden = true;
      body.innerHTML = "";
      return;
    }
    panel.hidden = false;
    if (meta) {
      meta.textContent = "Сегодня · " + rows.length + " сделок · PnL " + fmtPnl(st.todayPnlRub)
        + " · полный statement → /view/statement#trend";
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
        + "<td>" + (t.qty != null ? t.qty : "—") + "</td>"
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
  function buildOperatorBrief(data) {
    const bars = data.bars || [];
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

    // ——— 1. Рынок сейчас ———
    const marketItems = [];
    let priceLine = "BR <strong>" + (close != null ? fmtPx(close) : "—") + "</strong>";
    const topRel = relZone(st.zoneTop, close, "TOP");
    const botRel = relZone(st.zoneBottom, close, "BOT");
    if (topRel && nearZone(st.zoneTop, close)) priceLine += " — " + topRel;
    else if (botRel && nearZone(st.zoneBottom, close)) priceLine += " — " + botRel;
    else if (topRel && botRel) priceLine += " — между зонами: " + topRel + ", " + botRel;
    else if (topRel || botRel) priceLine += " — " + (topRel || botRel);
    marketItems.push(priceLine);

    if (sit.dayMovePoints != null) {
      const dm = sit.dayMovePoints;
      let dayLine = "День " + (dm >= 0 ? "+" : "") + dm + "п от открытия сессии";
      if (dm <= -80) dayLine += " (dump — macro BEARISH proxy)";
      else if (dm >= 80) dayLine += " (rally — macro BULLISH proxy)";
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
    marketItems.push("Режим <code>" + esc(mkt) + "</code>, HTF=" + esc(htf)
      + (htfSource ? "@" + esc(htfSource) : "")
      + ", bias=" + esc(bias));
    if (sit.structureNote) {
      deMark(String(sit.structureNote)).split(/(?<=[.!])\s+/).forEach(function (chunk) {
        const t = chunk.trim();
        if (t) marketItems.push(esc(t));
      });
    }
    marketItems.push("Лента " + (tapeLive ? "живая" : "архив/ISS")
      + ", ~" + (data.barCount || 0) + " M5");
    const oil = sit.usOil || {};
    if (oil.brief) {
      marketItems.push(esc(oil.brief));
    }
    if (oil.waitReason) {
      marketItems.push(esc(oil.waitReason));
    }
    if (oil.exclusiveNote) {
      marketItems.push(esc(oil.exclusiveNote));
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
      marketHtml += ". HI/LO " + fmtPx(st.lookbackHigh) + " / " + fmtPx(st.lookbackLow) + ".";
      if (st.previousZeroPoint != null) {
        marketHtml += " Zero " + fmtPx(st.previousZeroPoint)
          + (st.zeroPointBroken ? " (пробита)." : " (держится).");
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
    const postureRu = ({
      IN_TRADE: "В СДЕЛКЕ",
      WAITING_FILL: "ЖДЁТ ИСПОЛНЕНИЯ",
      WATCHING_ZONE: "СМОТРИТ ЗОНУ",
      NOT_IN_TRADE: "НЕ В СДЕЛКЕ",
      SCANNING: "СКАНИРУЕТ"
    })[posture] || posture;
    const robotInTrade = posture === "IN_TRADE";
    let robotHtml = "<p class='signal-brief-kicker signal-brief-kicker--robot"
      + (robotInTrade ? " is-in-trade" : "")
      + "'>Робот · " + esc(postureRu) + "</p>";

    robotHtml += "<p><code>" + esc(state) + "</code>";
    if (side && side !== "NONE") robotHtml += " · " + esc(side) + (mode ? (" " + esc(mode)) : "");
    robotHtml += " · канал: "
      + (liveBroker ? "LIVE (осторожно)"
        : (autoJ ? "SANDBOX_FAIR · обкатка paper" : "SIGNAL_ONLY"))
      + ".</p>";

    // Senior TF wind — always visible in robot block
    const srcLabel = htfSource === "H1"
      ? "H1 из M5 (по закрытым часам)"
      : (htfSource === "M15"
        ? "M15 из M5"
        : (htfSource === "M5_PROXY"
          ? "M5-прокси (H1 пока без явного хода)"
          : (htfSource || "старший ТФ")));
    let windLine;
    if (htf === "UP") {
      windLine = "Ветер: <strong>вверх</strong> · " + esc(srcLabel)
        + " — лонги с ветром, шорты только осторожный отскок / меньше размер.";
    } else if (htf === "DOWN") {
      windLine = "Ветер: <strong>вниз</strong> · " + esc(srcLabel)
        + " — шорты с ветром, лонги только осторожный отскок / меньше размер.";
    } else {
      windLine = "Ветер: <strong>боковик</strong> · " + esc(srcLabel)
        + " — приоритет bounce у TOP/BOT; RETEST после пробоя+закрепления.";
    }
    robotHtml += "<p class='signal-brief-note signal-brief-htf'>" + windLine + "</p>";

    if (sit.sessionPhaseRu) {
      robotHtml += "<p class='signal-brief-note'>" + esc(sit.sessionPhaseRu);
      if (sit.shelfLocal) robotHtml += " · фокус сдвинут на ближнюю полку";
      if (sit.touchQ != null) robotHtml += " · качество касания " + esc(String(sit.touchQ));
      robotHtml += ".</p>";
    }

    if (sit.fairPaper && sit.fairPaper.enabled) {
      const fp = sit.fairPaper;
      if (fp.open) {
        robotHtml += "<p class='signal-brief-note'><strong>Fair-paper OPEN</strong> "
          + esc(fp.open.side) + " " + esc(fp.open.mode || "")
          + " avg " + fmtPx(fp.open.avg) + " qty " + fp.open.qty
          + " · SL " + fmtPx(fp.open.sl)
          + (fp.open.tp1 != null ? (" · TP1 " + fmtPx(fp.open.tp1)) : "")
          + ".</p>";
      } else if (fp.pending) {
        robotHtml += "<p class='signal-brief-note'>Fair-paper PENDING "
          + esc(fp.pending.side) + " " + esc(fp.pending.mode || "")
          + ".</p>";
      }
      if (fp.lastClose && fp.lastClose.pnlRub != null) {
        robotHtml += "<p class='signal-brief-note'>Последнее закрытие SANDBOX_FAIR: "
          + esc(fp.lastClose.exitReason || "")
          + " · " + (fp.lastClose.pnlRub >= 0 ? "+" : "")
          + Math.round(fp.lastClose.pnlRub) + " ₽.</p>";
      }
    }

    if (posture === "IN_TRADE") {
      robotHtml += "<p><strong>Почему в сделке:</strong> " + esc(deMark(reason)) + "</p>";
      if (sit.setupLevels) {
        const lv = sit.setupLevels;
        robotHtml += "<p class='signal-brief-note'>Уровни: entry "
          + fmtPx(lv.entry) + " · SL " + fmtPx(lv.stop)
          + " · TP1 " + fmtPx(lv.tp1) + " · TP2 " + fmtPx(lv.tp2)
          + (lv.qty != null ? (" · qty " + lv.qty) : "") + ".</p>";
      }
      if (manage.note) {
        robotHtml += "<p class='signal-brief-note'>Manage: " + esc(deMark(manage.note))
          + (manage.movedToBe ? " · уже BE" : "")
          + (manage.trailing ? " · trail" : "") + ".</p>";
      }
    } else if (posture === "WAITING_FILL") {
      robotHtml += "<p><strong>Почему ждёт fill:</strong> " + esc(deMark(reason)) + "</p>";
      if (sit.activeLock) {
        const lk = sit.activeLock;
        robotHtml += "<p class='signal-brief-note'>Lock зоны "
          + fmtPx(lk.low) + "–" + fmtPx(lk.high)
          + " · mid " + fmtPx(lk.mid)
          + " — unlock ≥40п от mid или новый день.</p>";
      }
      if (sit.setupLevels) {
        const lv = sit.setupLevels;
        robotHtml += "<p class='signal-brief-note'>Сетка: avg "
          + fmtPx(lv.entry) + " · SL " + fmtPx(lv.stop)
          + " · TP1 " + fmtPx(lv.tp1) + ".</p>";
      }
      robotHtml += "<p class='signal-brief-note'>Следующий шаг: дождаться касания лимитов на M5; "
        + "при уходе цены далеко — unlock и новый поиск.</p>";
    } else {
      robotHtml += "<p><strong>Почему не в сделке:</strong> " + esc(deMark(reason)) + "</p>";
      const r = String(reason).toUpperCase();
      let next = "Наблюдение: при выполнении 6–8 появится BUY/SELL.";
      if (r.indexOf("MAX FILLS") >= 0 || r.indexOf("MAX SETUPS") >= 0) {
        next = "Дневной лимит сетапов исчерпан — новых входов сегодня не будет.";
      } else if (r.indexOf("MAX DAY LOSS") >= 0) {
        next = "Сработал дневной лимит убытка — робот в паузе до завтра.";
      } else if (r.indexOf("EVENT") >= 0) {
        next = "Календарный blackout вокруг события — ждите окончания окна.";
      } else if (r.indexOf("SESSION") >= 0) {
        next = "Вне торгового окна playbook — входы откроются в сессии.";
      } else if (r.indexOf("§6") >= 0 || r.indexOf("PROFILE") >= 0) {
        next = "Нет валидного профиля на активном уровне — ждите касание TOP/BOT с объёмом или сброс залипания.";
      } else if (r.indexOf("CLEAR BOT") >= 0 || r.indexOf("PREFER OVER WAIT") >= 0) {
        next = "Ясный reject у полки — робот предпочитает bounce, а не ожидание чужого ретеста.";
      } else if (r.indexOf("TOUCH") >= 0 || r.indexOf("QUALITY") >= 0) {
        next = "Касание полки слабое — нужен wick в зону и закрытие обратно (reject). DOM может дать бонус.";
      } else if (r.indexOf("MACRO") >= 0 || r.indexOf("KNIFE") >= 0 || r.indexOf("FA/") >= 0) {
        if (r.indexOf("ТОРМОЗ") >= 0 || r.indexOf("DECEL") >= 0 || r.indexOf("H1") >= 0 || r.indexOf("MID") >= 0) {
          next = "Dump + HTF DOWN: нужен reject у BOT и торможение H1 или 2 close над mid — тогда bounce можно.";
        } else if (r.indexOf("BOUNCE") >= 0 && r.indexOf("REJECT") >= 0) {
          next = "Dump-день: BOT bounce только после закрытого reject. Ждите подтверждение у полки.";
        } else if (r.indexOf("RETEST") >= 0) {
          next = "Macro режет RETEST BUY против дампа — ждите отскок с reject или смену фазы.";
        } else {
          next = "Macro-proxy: не ловим нож. Подтверждённый BOT bounce (reject + ветер/торможение H1) можно.";
        }
      } else if (r.indexOf("HTF") >= 0 && r.indexOf("COUNTER") >= 0) {
        next = "Против ветра старшего ТФ: RETEST без break+hold закрыт; смотрите bounce у полки или §8 продолжение.";
      } else if (r.indexOf("HTF") >= 0 || htf === "FLAT") {
        next = htfSource === "H1"
          ? "H1 без явного направления — bounce у day-locked TOP/BOT; RETEST после break+hold. Смотрите фазу дня в блоке робота."
          : "Старший ТФ плоский: приоритет bounce у day-locked TOP/BOT; RETEST после break+hold.";
      } else if (posture === "WATCHING_ZONE") {
        next = "Зона размечена — ждите bounce/retest confirm на M5. Учитывайте ветер "
          + (htfSource || "HTF") + "=" + htf
          + (sit.sessionPhaseRu ? (" · " + sit.sessionPhaseRu) : "") + ".";
      } else if (r.indexOf("COOLDOWN") >= 0) {
        next = "Cooldown после стопа — пауза до конца таймера.";
      }
      robotHtml += "<p class='signal-brief-note'>Что делать: " + next + "</p>";
    }

    if (sit.setupsToday != null) {
      robotHtml += "<p class='signal-brief-note'>Квота: fills сегодня "
        + sit.setupsToday
        + (sit.maxSetupsPerDay > 0 ? (" / " + sit.maxSetupsPerDay) : " (без лимита)")
        + (sit.realizedDayPnlRub != null
          ? (" · day PnL engine " + (sit.realizedDayPnlRub >= 0 ? "+" : "")
            + Math.round(sit.realizedDayPnlRub) + " ₽")
          : "")
        + (sit.maxDayLossRub > 0 ? (" · day-loss cap −" + sit.maxDayLossRub + " ₽") : "")
        + ".</p>";
    }

    // ——— 3. Новости / календарь ———
    let newsHtml = "<p class='signal-brief-kicker signal-brief-kicker--gold'>Новости и события</p>";
    const upcoming = events.filter(function (e) { return e.status === "UPCOMING"; }).slice(0, 3);
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
      newsHtml += "<p class='signal-brief-note'>Сессия tradable "
        + esc(sit.sessionOpen || "09:00") + "–" + esc(sit.sessionClose || "23:50")
        + " (с буферами open/close).</p>";
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

    return marketHtml + robotHtml + newsHtml + paperHtml;
  }
  function humanizeDeskReason(raw) {
    const s = deMark(raw || "");
    if (!s) return "";
    const u = s.toUpperCase();
    if (u.indexOf("WAITING RETEST FROM BELOW") >= 0 || (u.indexOf("TREND_HI") >= 0 && u.indexOf("RETEST") >= 0)) {
      const m = s.match(/(\d+[.,]\d+)\s*[–-]\s*(\d+[.,]\d+)/);
      const zone = m ? ("TOP " + m[1] + "–" + m[2]) : "верхней зоне дня (TOP)";
      return "Цена под верхней зоной (" + zone + "). Ждём возврат снизу к TOP после пробоя низа — без касания зоны новый вход не ставим.";
    }
    if (u.indexOf("WAITING RETEST FROM ABOVE") >= 0 || (u.indexOf("TREND_LO") >= 0 && u.indexOf("RETEST") >= 0)) {
      const m = s.match(/(\d+[.,]\d+)\s*[–-]\s*(\d+[.,]\d+)/);
      const zone = m ? ("BOT " + m[1] + "–" + m[2]) : "нижней зоне дня (BOT)";
      return "Цена над нижней зоной (" + zone + "). Ждём возврат сверху к BOT после пробоя верха.";
    }
    if (u.indexOf("BOUNCE") >= 0 && u.indexOf("WAITING") >= 0 && u.indexOf("REJECTION") >= 0) {
      return "Цена у зоны — ждём закрытую свечу-отбой (rejection), чтобы подтвердить bounce.";
    }
    if (u.indexOf("WAITING RETURN TO SHELF") >= 0 || u.indexOf("PRICE ABOVE BOT") >= 0) {
      return "Цена ещё не в зоне BOT — ждём возврат к полке для входа.";
    }
    if (u.indexOf("PRICE BELOW TOP") >= 0) {
      return "Цена ещё не в зоне TOP — ждём возврат к полке для входа.";
    }
    if (u.indexOf("MAX SETUPS") >= 0 || u.indexOf("MAX FILLS") >= 0) {
      return "Дневной лимит сделок исчерпан — новых входов сегодня не будет.";
    }
    if (u.indexOf("MAX DAY LOSS") >= 0) {
      return "Сработал лимит убытка за день — робот на паузе до завтра.";
    }
    if (u.indexOf("NO VALID PROFILE") >= 0 || u.indexOf("PROFILE") >= 0 && u.indexOf("§6") >= 0) {
      return "Нет рабочего объёмного профиля на активном уровне — ждём касание TOP/BOT с объёмом.";
    }
    if (u.indexOf("MACRO") >= 0 || u.indexOf("KNIFE") >= 0 || u.indexOf("NO BUY") >= 0) {
      return "Фильтр дня/тренда режет покупку против сильного дампа (не ловим нож).";
    }
    if (u.indexOf("SESSION") >= 0) {
      return "Вне торгового окна — новые входы закрыты.";
    }
    if (u.indexOf("EVENT") >= 0 || u.indexOf("BLACKOUT") >= 0) {
      return "Календарный blackout вокруг события — ждём окончания окна.";
    }
    if (u.indexOf("COOLDOWN") >= 0) {
      return "Пауза после стопа (cooldown) — ждём таймер.";
    }
    if (u.indexOf("ZONE_READY") >= 0 || u.indexOf("WAITING") >= 0) {
      return "Зона размечена, сетап ещё не подтверждён — наблюдаем, без входа.";
    }
    // fallback: strip jargon tokens, keep readable chunk
    return s
      .replace(/\bTREND_HI\b/gi, "TOP")
      .replace(/\bTREND_LO\b/gi, "BOT")
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
    if (cc && !Array.isArray(cc) && cc.total != null) {
      lines.push("Правила playbook: в ядре " + core + " из " + cc.total
        + (ext ? (", доп. " + ext) : "") + ".");
    } else {
      lines.push("Правила playbook: в ядре " + core + " из 18, доп. ужесточений " + ext + ".");
    }
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
      lines.push("Есть рабочий сигнал — смотрите side/mode выше.");
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
    ov.innerHTML = "";
    const st = overlayStructure || {};
    const items = [];
    if (st.zoneTop) items.push({ z: st.zoneTop, role: "top", title: "TOP" });
    if (st.zoneBottom) items.push({ z: st.zoneBottom, role: "bot", title: "BOT" });
    items.forEach(function (item) {
      if (!finitePrice(item.z.high) || !finitePrice(item.z.low)) return;
      const y1 = candleSeries.priceToCoordinate(item.z.high);
      const y2 = candleSeries.priceToCoordinate(item.z.low);
      if (y1 == null || y2 == null) return;
      const top = Math.min(y1, y2);
      const height = Math.abs(y2 - y1);
      if (!(height >= 1)) return;
      const band = document.createElement("div");
      band.className = "signal-zone-band is-" + item.role;
      band.style.height = Math.max(height, 14) + "px";
      // Keep band centered on true mid when we pad for visibility
      if (height < 14) {
        band.style.top = (top - (14 - height) / 2) + "px";
      } else {
        band.style.top = top + "px";
      }
      const label = document.createElement("span");
      label.className = "signal-zone-label";
      label.textContent = item.title + " "
        + Number(item.z.low).toFixed(2) + "–" + Number(item.z.high).toFixed(2);
      band.appendChild(label);
      ov.appendChild(band);
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
  function applyCombinedMarkers() {
    if (!candleSeries) return;
    const all = (lastSignalMarkers || []).concat(showMacd ? (lastDivMarkers || []) : []);
    try { candleSeries.setMarkers(all); } catch (_) {}
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
    return [
      st && st.lookbackHigh, st && st.lookbackLow,
      st && st.historicalHigh, st && st.historicalLow, st && st.previousZeroPoint,
      zt, zb,
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
      // §3 historical — dashed gray
      if (finitePrice(st.historicalHigh)
          && st.historicalHigh !== st.lookbackHigh) {
        addLine(st.historicalHigh, "#94a3b8", "HIST↑", { lineWidth: 1, lineStyle: 2 });
      }
      if (finitePrice(st.historicalLow)
          && st.historicalLow !== st.lookbackLow) {
        addLine(st.historicalLow, "#94a3b8", "HIST↓", { lineWidth: 1, lineStyle: 2 });
      }
      // §4 zero
      if (finitePrice(st.previousZeroPoint)) {
        addLine(st.previousZeroPoint, "#ca8a04", "ZERO", { lineWidth: 1, lineStyle: 2 });
      }
      // §5 current trend extremes — thick solid red
      if (finitePrice(st.lookbackHigh)) {
        addLine(st.lookbackHigh, HI_LO_COLOR, "HI", { lineWidth: 2, lineStyle: 0 });
      }
      if (finitePrice(st.lookbackLow)) {
        addLine(st.lookbackLow, HI_LO_COLOR, "LO", { lineWidth: 2, lineStyle: 0 });
      }
      // Zone edges as thin purple guides (fill = HTML band)
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
          try { volumeChart.timeScale().setVisibleLogicalRange(range); } catch (_) {}
        }
      });
    }
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
  function rememberUserScale() {
    if (applyingScale || !chart) return;
    const snap = snapshotTimeScale();
    if (!snap || !(snap.barSpacing > 0)) return;
    scaleLocked = true;
    lockedBarSpacing = snap.barSpacing;
    if (followLive) {
      lockedLogical = null;
      userPinned = false;
    } else {
      lockedLogical = snap.logical;
      userPinned = true;
    }
    saveScaleLocal(lastDeskInstrument);
    scheduleSaveDeskLayout();
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
      } else if (!followLive && lockedLogical) {
        chart.timeScale().setVisibleLogicalRange(lockedLogical);
      }
    } catch (_) {}
    if (!nested) applyingScale = false;
  }
  function bindUserScaleCapture(el) {
    if (!el || el._trinityScaleBound) return;
    el._trinityScaleBound = true;
    const remember = function () {
      requestAnimationFrame(rememberUserScale);
    };
    el.addEventListener("wheel", remember, { passive: true });
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
      rightPriceScale: { borderColor: "#d5dde2" },
      timeScale: {
        borderColor: "#d5dde2",
        timeVisible: true,
        secondsVisible: false,
        rightOffset: currentRightOffset(),
        barSpacing: 8,
        lockVisibleTimeRangeOnResize: true
      },
      handleScroll: { mouseWheel: true, pressedMouseMove: true, horzTouchDrag: true },
      handleScale: { axisPressedMouseMove: true, mouseWheel: true, pinch: true }
    });
    syncPadButtons();
    candleSeries = chart.addCandlestickSeries({
      upColor: "#16a34a", downColor: "#dc2626",
      borderUpColor: "#16a34a", borderDownColor: "#dc2626",
      wickUpColor: "#16a34a", wickDownColor: "#dc2626"
    });
    chart.timeScale().subscribeVisibleLogicalRangeChange(function () {
      layoutMarketOverlays();
      syncMacdTimeScale();
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
        onChange: scheduleSaveDeskLayout
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
    if (!scaleLocked) adoptSavedScale(secid);
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
      if (lockedBarSpacing > 0) {
        try { chart.timeScale().applyOptions({ barSpacing: lockedBarSpacing }); } catch (_) {}
      }
      applyingScale = false;
      if (chartNeedsFit && !scaleLocked) {
        chartNeedsFit = false;
        applyingScale = true;
        try { chart.timeScale().fitContent(); } catch (_) {}
        try { chart.timeScale().applyOptions({ rightOffset: currentRightOffset() }); } catch (_) {}
        try { chart.timeScale().scrollToRealTime(); } catch (_) {}
        applyingScale = false;
      } else {
        chartNeedsFit = false;
      }
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
      const o = Number(c.open), h = Number(c.high), l = Number(c.low), cl = Number(c.close);
      if (![o, h, l, cl].every(Number.isFinite)) return;
      if (prev != null && t <= prev) return;
      out.push({ time: t, open: o, high: h, low: l, close: cl });
      prev = t;
    });
    return out;
  }
  function updateCandles(candles, forceFit) {
    candles = sanitizeCandles(candles);
    if (!candleSeries || !candles.length) return;
    resizeChartToHost();
    const host = $("signal-chart");
    const hostW = host ? (host.clientWidth || host.offsetWidth || 0) : 0;
    if (forceFit && hostW < 40 && !scaleLocked) chartNeedsFit = true;
    const prevRange = chart.timeScale().getVisibleLogicalRange();
    const stickRight = followLive && !userPinned;
    applyingScale = true;
    try {
      if (lastCandleTime == null || forceFit || candles.length < 3) {
        candleSeries.setData(candles);
      } else {
        const last = candles[candles.length - 1];
        const prev = candles[candles.length - 2];
        if (last.time === lastCandleTime) {
          candleSeries.update(last);
        } else if (prev && prev.time === lastCandleTime) {
          candleSeries.update(last);
        } else {
          candleSeries.setData(candles);
        }
      }
      lastCandleTime = candles[candles.length - 1].time;
      if (forceFit && !scaleLocked) {
        try { candleSeries.priceScale().applyOptions({ autoScale: true }); } catch (_) {}
      }
    } catch (err) {
      try {
        candleSeries.setData(candles);
        lastCandleTime = candles[candles.length - 1].time;
      } catch (e2) {
        if (typeof console !== "undefined") console.warn("chart setData failed", e2);
      }
    }
    if (forceFit && !scaleLocked) {
      try { chart.timeScale().fitContent(); } catch (_) {}
      userPinned = false;
      try { chart.timeScale().applyOptions({ rightOffset: currentRightOffset() }); } catch (_) {}
      try { chart.timeScale().scrollToRealTime(); } catch (_) {}
    } else if (scaleLocked) {
      restoreTimeScale(stickRight);
    } else if (stickRight) {
      try { chart.timeScale().applyOptions({ rightOffset: currentRightOffset() }); } catch (_) {}
      try { chart.timeScale().scrollToRealTime(); } catch (_) {}
    } else if (prevRange) {
      try { chart.timeScale().setVisibleLogicalRange(prevRange); } catch (_) {}
    }
    applyingScale = false;
    requestAnimationFrame(function () {
      resizeChartToHost();
      if (scaleLocked && followLive && !userPinned) {
        restoreTimeScale(true);
      } else if (scaleLocked && lockedBarSpacing > 0) {
        applyingScale = true;
        try { chart.timeScale().applyOptions({ barSpacing: lockedBarSpacing }); } catch (_) {}
        applyingScale = false;
      }
      layoutMarketOverlays();
      syncMacdTimeScale();
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
  function paintLastCandle(px) {
    if (!candleSeries || !(px > 0) || lastCandleTime == null) return;
    const raw = lastBarsRaw && lastBarsRaw.length ? lastBarsRaw[lastBarsRaw.length - 1] : null;
    if (!raw) return;
    const o = Number(raw.open);
    let h = Number(raw.high);
    let l = Number(raw.low);
    if (![o, h, l].every(Number.isFinite)) return;
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
    paintLastCandle(livePxFromBook(book));
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
  async function loadBook() {
    try {
      const res = await fetch("/api/marketdata/book", { headers: { Accept: "application/json" } });
      if (!res.ok) return;
      renderDom(await res.json());
    } catch (_) {}
  }
  let deskInFlight = false;
  async function loadDesk(forceFit) {
    if (deskInFlight) return;
    deskInFlight = true;
    const meta = $("signal-desk-meta");
    try {
      const instSel = $("sig-instrument");
      const q = (instSel && instSel.value) ? ("?instrument=" + encodeURIComponent(instSel.value)) : "";
      // GET /api/** is public — do NOT send Bearer here: an expired Supabase token
      // makes Spring OAuth2 return 401 even though anonymous access is allowed.
      const res = await fetch("/api/trend/desk" + q, { headers: { Accept: "application/json" } });
      if (!res.ok) throw new Error("HTTP " + res.status);
      const data = await res.json();
      if (meta) {
        meta.textContent = (data.playbookName || data.playbookId || "playbook")
          + " · " + (data.instrument || "BR")
          + " · " + (data.timeframe || "M5")
          + " · bars=" + (data.barCount || 0)
          + " · source=" + (data.barsSource || "?")
          + " · " + (data.engineState || "")
          + (followLive && !userPinned ? " · follow" : " · zoom locked");
      }
      fillDeskSelects(data);
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
      if (chartLabel) {
        chartLabel.textContent = "График · " + chartInst + " " + chartTf
          + (chartSource ? (" · " + chartSource) : "");
      }
      const instrumentChanged = !!chartInst && chartInst !== lastDeskInstrument;
      if (instrumentChanged) {
        lastDeskInstrument = chartInst;
        lastOverlayKey = "";
        lastCandleTime = null;
        scaleLocked = false;
        lockedBarSpacing = null;
        lockedLogical = null;
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
        return { time: t, open: b.open, high: b.high, low: b.low, close: b.close };
      }).filter(Boolean);
      const livePx = livePxFromBook(data.book);
      if (livePx > 0 && candles.length) {
        const last = candles[candles.length - 1];
        last.close = livePx;
        if (livePx > last.high) last.high = livePx;
        if (livePx < last.low) last.low = livePx;
      }
      if (meta && rawBars.length && !candles.length) {
        meta.textContent = (meta.textContent || "") + " · chart: bad bar times";
      } else if (meta && candles.length) {
        meta.textContent = (meta.textContent || "") + " · candles=" + candles.length;
      }
      const sit = data.situation || {};
      const fp = sit.fairPaper || data.fairPaper || {};
      const overlayPb = sit.playbookId
        || (data.parallelPlaybooks ? "levels-profile-br-m5" : data.playbookId)
        || "";
      const overlayOpen = fairPaperLaneOpen(fp, overlayPb);
      if (candles.length) {
        updateCandles(candles, !!forceFit || instrumentChanged || chartNeedsFit);
        applyOverlays(plan, sig, candles, data.structure || {}, overlayOpen);
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
      layoutMarketOverlays();
      if (data.book) renderDom(data.book);
      // After chart: compliance shape differs for positional (object+items) vs BR (array).
      try { renderCompliance(data); } catch (compErr) {
        if (typeof console !== "undefined") console.warn("renderCompliance", compErr);
      }
    } catch (err) {
      if (meta) meta.textContent = "Ошибка desk: " + (err.message || err);
    } finally {
      deskInFlight = false;
    }
  }
  const btn = $("signal-desk-refresh");
  if (btn) btn.addEventListener("click", function () { loadDesk(false); });
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
    if (ev.key === "Escape") {
      const gate = $("signal-guide-modal");
      if (gate && !gate.hidden) {
        closeStrategyGuide();
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
      if (usableWhy) {
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
      detail = usableWhy || "Нового сетапа сейчас нет";
    } else {
      cls = "is-scan";
      status = "Сканирует";
      if (usableWhy) {
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
    if (detail.length > 160) detail = detail.slice(0, 158) + "…";
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
    }
    // Don't clobber the select while a save is in flight (poll would snap back to "both").
    if (!deskSaveInFlight) {
      if (activePb) pbSel.value = activePb;
      if (activeInst) {
        let matched = false;
        for (let i = 0; i < instSel.options.length; i++) {
          if (instSel.options[i].value === activeInst) {
            instSel.value = activeInst;
            matched = true;
            break;
          }
        }
        if (!matched) {
          const fam = String(activeInst).slice(0, 2).toUpperCase();
          for (let i = 0; i < instSel.options.length; i++) {
            if (String(instSel.options[i].value).toUpperCase().indexOf(fam) === 0) {
              instSel.value = instSel.options[i].value;
              break;
            }
          }
        }
      }
    }
    const pb = pbSel.value;
    for (let i = 0; i < instSel.options.length; i++) {
      const ids = (instSel.options[i].dataset.playbookIds || "").split(",");
      // "both" arms every playbook — show all instruments
      const ok = !pb || pb === "both" || !ids[0] || ids.indexOf(pb) >= 0;
      instSel.options[i].hidden = !ok;
      instSel.options[i].disabled = !ok;
    }
    if (!deskSelectsWired) {
      deskSelectsWired = true;
      pbSel.addEventListener("change", function () {
        saveDeskSelection({ playbookId: pbSel.value });
      });
      instSel.addEventListener("change", function () {
        saveDeskSelection({ instrumentId: instSel.value });
      });
      if (window.TrinityPlaques && typeof window.TrinityPlaques.refresh === "function") {
        window.TrinityPlaques.refresh();
      }
    }
  }
  async function saveDeskSelection(patch) {
    deskSaveInFlight = true;
    try {
      const cur = await fetch("/api/trend/settings", { headers: { Accept: "application/json" } });
      const view = cur.ok ? await cur.json() : {};
      const body = {
        autoExecution: view.autoExecution,
        liveExecution: view.liveExecution,
        playbookId: patch.playbookId || view.playbookId,
        instrumentId: patch.instrumentId || view.instrumentId
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
      await loadDesk(true);
    } catch (e) {
      console.warn("saveDeskSelection failed", e);
      alert("Не удалось сменить плейбук/инструмент: " + (e && e.message ? e.message : e)
        + "\nВойдите в кабинет (/view) — POST /api/trend/settings требует авторизацию.");
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
    if (!window.TrinityChartKit || !chartTools) return;
    try {
      deskLayoutDoc = await TrinityChartKit.loadLayouts();
      const st = (deskLayoutDoc.desk || {}).tools || null;
      if (st) chartTools.setState(st);
      const sc = (deskLayoutDoc.desk || {}).scale;
      if (!scaleLocked && sc && sc.barSpacing > 0
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
  }


  const toolVap = $("tool-vap-stretch");
  if (toolVap) {
    toolVap.addEventListener("click", function () {
      if (!chartTools) return;
      const on = chartTools.getMode() !== "vap";
      if (on && fpToolActive) toggleFpTool();
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
      chartTools.setMode(on ? "trend" : null);
      syncDrawToolButtons();
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

  async function applyUrlPlaybookOnce() {
    try {
      const q = new URLSearchParams(location.search);
      const pb = (q.get("playbook") || "").trim();
      if (!pb || pb === "pairs-daily") return;
      if (pb.indexOf("levels-profile") < 0 && pb.indexOf("positional") < 0 && pb !== "both") return;
      await saveDeskSelection({ playbookId: pb });
    } catch (_) {}
  }

  applyUrlPlaybookOnce().finally(function () {
    loadDesk(true);
  });
  loadBook();
  setInterval(function () { loadDesk(false); }, DESK_MS);
  setInterval(loadBook, BOOK_MS);
})();