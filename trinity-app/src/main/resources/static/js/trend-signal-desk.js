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
  let userPinned = false;
  let followLive = true;
  let rightPadOn = true;
  let showVolume = false;
  let showProfile = true;
  let showMacd = false;
  let fpToolActive = false;
  let fpPinned = [];
  let fpHoverTime = null;
  let footprintByTime = {};
  let lastProfile = [];
  let lastFootprint = [];
  let macdChart = null;
  let macdHistSeries = null;
  let macdLineSeries = null;
  let macdSignalSeries = null;
  let macdSynced = false;
  let lastDivMarkers = [];
  let lastSignalMarkers = [];
  let lastDivMarkersKey = "";
  let domFollowMid = true;
  let domScrollBound = false;
  const DESK_MS = 8000;
  const BOOK_MS = 2000;
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
  function renderPaper(paper) {
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
    const rows = raw.filter(function (t) {
      return isSameMskDay(t && (t.closedAt || t.openedAt), today);
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
        + "<td>" + shortTime(t.openedAt) + "</td>"
        + "<td>" + shortTime(t.closedAt) + "</td>"
        + "<td>" + (t.side || "—") + "</td>"
        + "<td>" + (t.qty != null ? t.qty : "—") + "</td>"
        + "<td>" + (t.exitReason || "—") + "</td>"
        + "<td class='" + cls + "'>" + fmtPnl(pnl) + "</td>"
        + "<td>" + (t.tag || "—") + "</td>"
        + "</tr>";
    }).join("");
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
        robotHtml += "<p class='signal-brief-note'>Fair-paper OPEN "
          + esc(fp.open.side) + " " + esc(fp.open.mode || "")
          + " avg " + fmtPx(fp.open.avg) + " qty " + fp.open.qty
          + " · SL " + fmtPx(fp.open.sl) + ".</p>";
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
  function renderCompliance(data) {
    const el = $("signal-compliance-meta");
    if (!el) return;
    const rows = data.checklistCompliance || [];
    let core = 0, ext = 0;
    rows.forEach(function (r) {
      if (r.status === "IMPLEMENTED") core++;
      else if (r.status === "EXTENSION") ext++;
    });
    const lines = [];
    lines.push("Правила playbook: в ядре " + core + " из 18, доп. ужесточений " + ext + ".");
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
    if (!fpToolActive) fpHoverTime = null;
    setToolPressed("tool-footprint", fpToolActive);
    syncChartCursor();
    layoutFootprint();
  }
  function clearFpPins() {
    fpPinned = [];
    fpHoverTime = null;
    layoutFootprint();
  }
  function toggleFpPin(timeSec) {
    if (timeSec == null) return;
    const i = fpPinned.indexOf(timeSec);
    if (i >= 0) fpPinned.splice(i, 1);
    else {
      fpPinned.push(timeSec);
      if (fpPinned.length > FP_PIN_MAX) fpPinned.shift();
    }
    layoutFootprint();
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
                          barSpacing: 8
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
    const times = {};
    fpPinned.forEach(function (t) { times[t] = "pin"; });
    if (fpToolActive && fpHoverTime != null && !times[fpHoverTime]) {
      times[fpHoverTime] = "hover";
    }
    const keys = Object.keys(times).map(Number).sort(function (a, b) { return a - b; });
    if (!keys.length) {
      ov.hidden = true;
      return;
    }
    ov.hidden = false;
    const ts = chart.timeScale();
    keys.forEach(function (t) {
      const fb = footprintByTime[t];
      const x = ts.timeToCoordinate(t);
      if (x == null) return;
      const col = document.createElement("div");
      col.className = "signal-fp-col" + (times[t] === "hover" ? " is-hover" : " is-pinned");
      col.style.left = (x - 22) + "px";
      if (!fb || !(fb.levels || []).length) {
        const miss = document.createElement("div");
        miss.className = "signal-fp-miss";
        miss.textContent = "нет ленты";
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
  function layoutMarketOverlays() {
    layoutZoneBands();
    layoutProfile(lastProfile);
    layoutFootprint();
  }
  function overlayKey(plan, sig, st) {
    const zt = st && st.zoneTop ? (st.zoneTop.low + "/" + st.zoneTop.high) : "";
    const zb = st && st.zoneBottom ? (st.zoneBottom.low + "/" + st.zoneBottom.high) : "";
    return [
      st && st.lookbackHigh, st && st.lookbackLow,
      st && st.historicalHigh, st && st.historicalLow, st && st.previousZeroPoint,
      zt, zb,
      plan && plan.side, plan && plan.entry, plan && plan.stopLoss,
      plan && plan.tp1, plan && plan.actionable, sig && sig.side
    ].join("|");
  }
  function applyOverlays(plan, sig, candles, structure) {
    const st = structure || {};
    overlayStructure = st;
    const key = overlayKey(plan, sig, st);
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
      if (plan) {
        const entry = plan.entry || (plan.grid && plan.grid.avg);
        if (finitePrice(entry)) addLine(entry, "#0f766e", "ENTRY", { lineWidth: 2, lineStyle: 0 });
        if (finitePrice(plan.stopLoss)) addLine(plan.stopLoss, "#b91c1c", "SL", { lineWidth: 1, lineStyle: 2 });
        if (finitePrice(plan.tp1)) addLine(plan.tp1, "#16a34a", "TP1", { lineWidth: 1, lineStyle: 2 });
        if (finitePrice(plan.tp2)) addLine(plan.tp2, "#15803d", "TP2", { lineWidth: 1, lineStyle: 2 });
      }
      if (plan && plan.actionable && candles && candles.length) {
        const last = candles[candles.length - 1];
        const buy = plan.buy === true || (sig && sig.side === "BUY");
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
      return barsInfo.barsAfter != null && barsInfo.barsAfter < 3;
    } catch (_) {
      return true;
    }
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
    try {
      chart.timeScale().applyOptions({ rightOffset: currentRightOffset() });
    } catch (_) {}
    if (scrollLive !== false) {
      try { chart.timeScale().scrollToRealTime(); } catch (_) {}
      userPinned = false;
    }
    syncPadButtons();
    syncMacdTimeScale();
  }
  function toggleRightPad() {
    rightPadOn = !rightPadOn;
    applyRightPad(true);
  }
  function ensureChart() {
    const el = $("signal-chart");
    if (!el || chart) return;
    chart = LightweightCharts.createChart(el, {
      width: el.clientWidth,
      height: 420,
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
        barSpacing: 8
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
      if (!fpToolActive || !param || param.time == null) return;
      const t = typeof param.time === "number" ? param.time : null;
      if (t == null) return;
      toggleFpPin(t);
    });
    chart.subscribeCrosshairMove(function (param) {
      if (!fpToolActive) {
        if (fpHoverTime != null) {
          fpHoverTime = null;
          layoutFootprint();
        }
        return;
      }
      const t = param && typeof param.time === "number" ? param.time : null;
      if (t === fpHoverTime) return;
      fpHoverTime = t;
      layoutFootprint();
    });
    window.addEventListener("resize", function () {
      if (chart && el) chart.applyOptions({ width: el.clientWidth });
      if (macdChart) {
        const mEl = $("signal-macd");
        if (mEl) macdChart.applyOptions({ width: mEl.clientWidth || el.clientWidth });
      }
      layoutMarketOverlays();
    });
    ensureZoneOverlay();
    ensureProfileOverlay();
    ensureFootprintOverlay();
  }
  function toChartTime(iso) {
    if (!iso) return null;
    const d = new Date(iso.includes("T") ? iso : iso.replace(" ", "T"));
    if (isNaN(d.getTime())) return null;
    return Math.floor(d.getTime() / 1000);
  }
  function updateCandles(candles, forceFit) {
    if (!candleSeries || !candles.length) return;
    const prevRange = chart.timeScale().getVisibleLogicalRange();
    const stickRight = forceFit || (followLive && !userPinned);
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
    if (forceFit) {
      chart.timeScale().fitContent();
      userPinned = false;
      try { chart.timeScale().applyOptions({ rightOffset: currentRightOffset() }); } catch (_) {}
      try { chart.timeScale().scrollToRealTime(); } catch (_) {}
    } else if (stickRight) {
      try { chart.timeScale().applyOptions({ rightOffset: currentRightOffset() }); } catch (_) {}
      try { chart.timeScale().scrollToRealTime(); } catch (_) {}
    } else if (prevRange) {
      try { chart.timeScale().setVisibleLogicalRange(prevRange); } catch (_) {}
    }
    requestAnimationFrame(function () {
      layoutMarketOverlays();
      syncMacdTimeScale();
    });
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
  async function loadDesk(forceFit) {
    const meta = $("signal-desk-meta");
    try {
      const res = await fetch("/api/trend/desk", { headers: { Accept: "application/json" } });
      if (!res.ok) throw new Error("HTTP " + res.status);
      const data = await res.json();
      if (meta) {
        meta.textContent = (data.instrument || "BR") + " · bars=" + (data.barCount || 0)
          + " · source=" + (data.barsSource || "?")
          + " · " + (data.engineState || "")
          + (followLive && !userPinned ? " · follow" : " · zoom locked");
      }
      $("sig-instrument").textContent = data.instrument || "—";
      $("sig-delivery").textContent = data.delivery || "—";
      const sig = data.signal || {};
      const plan = data.plan || {};
      $("sig-side").textContent = sig.side || plan.side || "—";
      $("sig-mode").textContent = sig.mode || plan.mode || "—";
      $("sig-potential").textContent = fmtPot(data.potentialPnlRub);
      $("sig-summary").textContent = data.summary || sig.summary || "—";
      $("sig-side").classList.toggle("is-buy", (sig.side || plan.side) === "BUY");
      $("sig-side").classList.toggle("is-sell", (sig.side || plan.side) === "SELL");
      renderPaper(data.paper);
      const briefBody = $("signal-desk-brief-body");
      if (briefBody) briefBody.innerHTML = buildOperatorBrief(data);
      renderCompliance(data);
      syncDomPressureFab(data);

      ensureChart();
      const candles = (data.bars || []).map(function (b) {
        const t = toChartTime(b.time);
        if (t == null) return null;
        return { time: t, open: b.open, high: b.high, low: b.low, close: b.close };
      }).filter(Boolean);
      if (candles.length) {
        updateCandles(candles, !!forceFit);
        applyOverlays(plan, sig, candles, data.structure || {});
      }
      lastBarsRaw = data.bars || [];
      lastProfile = data.profile || [];
      lastFootprint = data.footprint || [];
      indexFootprints(lastFootprint);
      updateVolume(lastBarsRaw);
      updateMacd(lastBarsRaw);
      layoutMarketOverlays();
      if (data.book) renderDom(data.book);
    } catch (err) {
      if (meta) meta.textContent = "Ошибка desk: " + (err.message || err);
    }
  }
  const btn = $("signal-desk-refresh");
  if (btn) btn.addEventListener("click", function () { loadDesk(true); });
  const kickBtn = $("sig-kick-btn");
  if (kickBtn) kickBtn.addEventListener("click", kickRobot);
  const fitBtn = $("signal-desk-fit");
  if (fitBtn) fitBtn.addEventListener("click", function () {
    userPinned = false;
    followLive = true;
    const follow = $("signal-desk-follow");
    if (follow) follow.checked = true;
    if (chart) {
      chart.timeScale().fitContent();
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
    });
  }
  document.addEventListener("keydown", function (ev) {
    if (ev.key === "Escape") {
      const gate = $("signal-guide-modal");
      if (gate && !gate.hidden) {
        closeStrategyGuide();
        return;
      }
      if (fpToolActive || fpPinned.length) {
        fpToolActive = false;
        setToolPressed("tool-footprint", false);
        syncChartCursor();
        clearFpPins();
      }
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
  function syncDomPressureFab(data) {
    const fab = $("signal-pressure-fab");
    const fabText = $("signal-pressure-fab-text");
    const target = $("signal-dom-pressure");
    if (!fab) return;
    if (!target) {
      fab.hidden = true;
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
    if (fabText) fabText.textContent = label;
    fab.hidden = pressureTargetVisible;
    if (!pressureFabObserver && typeof IntersectionObserver === "function") {
      pressureFabObserver = new IntersectionObserver(function (entries) {
        const e = entries[0];
        pressureTargetVisible = !!(e && e.isIntersecting && e.intersectionRatio > 0.15);
        const f = $("signal-pressure-fab");
        const t = $("signal-dom-pressure");
        if (f) f.hidden = !t || pressureTargetVisible;
      }, { root: null, threshold: [0, 0.15, 0.4] });
    }
    if (pressureFabObserver) {
      pressureFabObserver.disconnect();
      pressureFabObserver.observe(target);
    }
  }
  const pressureFab = $("signal-pressure-fab");
  if (pressureFab) {
    pressureFab.addEventListener("click", function () {
      const target = $("signal-dom-pressure") || $("signal-desk-brief");
      if (!target || typeof target.scrollIntoView !== "function") return;
      target.scrollIntoView({ behavior: "smooth", block: "center" });
    });
  }
  loadDesk(true);
  loadBook();
  setInterval(function () { loadDesk(false); }, DESK_MS);
  setInterval(loadBook, BOOK_MS);
})();