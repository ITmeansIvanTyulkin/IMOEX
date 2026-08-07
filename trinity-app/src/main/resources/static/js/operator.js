(function () {
  const USER_KEY = "imoex.ops.user";
  const PASS_KEY = "imoex.ops.pass";
  const SB_TOKEN_KEY = "trinity.supabase.access_token";
  const SB_EMAIL_KEY = "trinity.supabase.user_email";
  const WELCOME_SESSION_KEY = "trinity.welcome.played";
  const ALERTS_ENABLED_KEY = "imoex.alerts.enabled";
  const ALERTS_SOUND_KEY = "imoex.alerts.sound";
  const SEEN_IDS_KEY = "imoex.alerts.seenIds";
  const UPSELL_SHOWN_KEY = "imoex.upsell.shownId";
  const STRATEGY_KEY = "trinity.activeStrategy";
  const POLL_MS = 60000;

  /** Filled from GET /api/auth/mode — Supabase shares IdP with trinity-landing cabinet. */
  let authMode = {
    basicEnabled: true,
    supabase: { enabled: false, configured: false, url: "", anonKey: "" }
  };

  const SECTION_TITLES = {
    "/view": "Дашборд",
    "/view/": "Дашборд",
    "/view/settings": "Настройки",
    "/view/recommendations": "Рекомендации",
    "/view/signals": "Сигналы",
    "/view/final": "Итог + новости",
    "/view/paper": "Statement",
    "/view/statement": "Statement",
    "/view/trend-signal": "Сигнал Trend",
    "/view/walk-forward": "Walk-forward",
    "/view/strategy": "Описание стратегии",
    "/view/full-core": "Full Core",
    "/view/guide": "Как пользоваться системой"
  };

  const ACTION_START = {
    "run-fast": "Запускаю: Анализ + paper…",
    "run-full": "Запускаю: Анализ + скачать свечи…",
    "news-refresh": "Запускаю: новости и paper…",
    "data-refresh": "Запускаю: скачивание свечей…",
    "walk-forward": "Запускаю: Walk-forward…"
  };

  function $(id) {
    return document.getElementById(id);
  }

  function currentSectionTitle() {
    const path = (location.pathname || "").replace(/\/+$/, "") || "/view";
    if (SECTION_TITLES[path]) return SECTION_TITLES[path];
    if (path.indexOf("/view/charts/") === 0) return "График пары";
    return "Текущий раздел";
  }

  function looksLikeEmail(value) {
    return typeof value === "string" && value.indexOf("@") > 0;
  }

  function hasLoginForm() {
    return !!( ($("ops-save-creds") && $("ops-user") && $("ops-pass"))
      || ($("trinity-auth-form") && $("gate-user") && $("gate-pass")) );
  }

  function readLoginEmail() {
    if ($("gate-user") && $("gate-user").value) return String($("gate-user").value).trim();
    if ($("ops-user") && $("ops-user").value) return String($("ops-user").value).trim();
    return (localStorage.getItem(SB_EMAIL_KEY) || localStorage.getItem(USER_KEY) || "").trim();
  }

  function readLoginPassword() {
    if ($("gate-pass") && $("gate-pass").value) return String($("gate-pass").value);
    if ($("ops-pass") && $("ops-pass").value) return String($("ops-pass").value);
    return localStorage.getItem(PASS_KEY) || "";
  }

  function authHeader() {
    const token = localStorage.getItem(SB_TOKEN_KEY);
    if (token) {
      return "Bearer " + token;
    }
    const user = readLoginEmail() || "imoex";
    const pass = readLoginPassword();
    /* Email/password = Supabase only; never send them as HTTP Basic. */
    if (looksLikeEmail(user)) {
      return null;
    }
    if (!pass) {
      return null;
    }
    return "Basic " + btoa(unescape(encodeURIComponent(user + ":" + pass)));
  }

  function withAuthHeaders(base) {
    const headers = Object.assign({ Accept: "application/json" }, base || {});
    const auth = authHeader();
    if (auth) headers.Authorization = auth;
    return headers;
  }

  function saveCreds() {
    if (!hasLoginForm()) return;
    if ($("ops-user")) localStorage.setItem(USER_KEY, $("ops-user").value || "");
    if ($("ops-pass")) localStorage.setItem(PASS_KEY, $("ops-pass").value || "");
  }

  function loadCreds() {
    if (!hasLoginForm()) return;
    const user =
      localStorage.getItem(SB_EMAIL_KEY) ||
      localStorage.getItem(USER_KEY) ||
      "";
    const pass = localStorage.getItem(PASS_KEY) || "";
    if ($("ops-user")) $("ops-user").value = user;
    if ($("ops-pass")) $("ops-pass").value = pass;
  }

  async function loadAuthMode() {
    try {
      const res = await fetch("/api/auth/mode", { headers: { Accept: "application/json" } });
      if (!res.ok) return;
      const body = await res.json();
      if (body && typeof body === "object") {
        authMode = body;
      }
      applyAuthModeUi();
    } catch (_) {
      /* ignore — fall back to Basic */
    }
  }

  function applyAuthModeUi() {
    updateSessionBar();
    /* Login form lives only on /view/settings — do not mutate hidden fields elsewhere. */
    if (!hasLoginForm()) {
      const sb = authMode.supabase || {};
      if (sb.enabled && !localStorage.getItem(SB_TOKEN_KEY)) {
        appendLog(
          "Нет сессии — войдите один раз в Настройках (email/пароль кабинета).",
          "info"
        );
      } else if (sb.enabled && localStorage.getItem(SB_TOKEN_KEY)) {
        appendLog("Сессия Supabase активна (вход из Настроек).", "ok");
      }
      return;
    }
    const sb = authMode.supabase || {};
    const userLabel = document.querySelector('label[for="ops-user"]');
    const passLabel = document.querySelector('label[for="ops-pass"]');
    if (sb.enabled) {
      if (userLabel) userLabel.textContent = "Email (как в кабинете TRINITY)";
      if (passLabel) passLabel.textContent = "Пароль кабинета";
      if ($("ops-user")) {
        $("ops-user").placeholder = "you@example.com";
        $("ops-user").type = "email";
        $("ops-user").autocomplete = "username";
      }
      if ($("ops-save-creds")) {
        $("ops-save-creds").textContent = "Войти";
      }
      if (localStorage.getItem(SB_TOKEN_KEY)) {
        appendLog("Сессия Supabase активна — тот же аккаунт, что кабинет.", "ok");
      } else {
        appendLog(
          "Войдите email/паролем кабинета (один раз здесь; на других страницах поля не нужны).",
          "info"
        );
      }
    }
  }

  async function ensureSupabaseSession(force) {
    const sb = authMode.supabase || {};
    if (!sb.enabled) return;
    if (!force && localStorage.getItem(SB_TOKEN_KEY)) return;

    const email = readLoginEmail();
    const password = readLoginPassword();
    if (!email || !password) {
      throw new Error(
        hasLoginForm()
          ? "Укажите email и пароль кабинета."
          : "Нет сессии — откройте Настройки и нажмите «Войти»."
      );
    }

    /* Same-origin proxy — avoids Safari/WebKit «Load failed» on direct supabase.co fetch. */
    let res;
    try {
      res = await fetch("/api/auth/login", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Accept: "application/json"
        },
        body: JSON.stringify({ email: email, password: password })
      });
    } catch (e) {
      const raw = e && e.message ? e.message : String(e);
      throw new Error(
        raw === "Load failed" || raw === "Failed to fetch"
          ? "Сеть: не достучались до /api/auth/login (сервер не запущен или блокировка)."
          : ("Сеть: " + raw)
      );
    }
    const text = await res.text();
    let data = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch (_) {
      data = null;
    }
    if (!res.ok || !data || !data.access_token) {
      const msg =
        (data && (data.message || data.error_description || data.msg || data.error)) ||
        ("Login HTTP " + res.status);
      throw new Error(msg);
    }
    localStorage.setItem(SB_TOKEN_KEY, data.access_token);
    localStorage.setItem(SB_EMAIL_KEY, (data.email || email));
    /* Don't reuse cabinet password as HTTP Basic operator password. */
    try {
      localStorage.removeItem(PASS_KEY);
    } catch (_) { /* ignore */ }
    if ($("ops-pass")) $("ops-pass").value = "";
    if ($("gate-pass")) $("gate-pass").value = "";
    let alg = "";
    try {
      const part = String(data.access_token).split(".")[0] || "";
      const padded = part + "=".repeat((4 - (part.length % 4)) % 4);
      alg = JSON.parse(atob(padded.replace(/-/g, "+").replace(/_/g, "/"))).alg || "";
    } catch (_) { /* ignore */ }
    updateSessionBar();
    appendLog(
      "Вход выполнен — тот же email/пароль, что в кабинете TRINITY"
        + (alg ? " (JWT " + alg + ")" : "")
        + ".",
      "ok"
    );
  }

  async function prepareAuth() {
    saveCreds();
    const sb = authMode.supabase || {};
    if (sb.enabled) {
      try {
        await ensureSupabaseSession(false);
      } catch (e) {
        if (!localStorage.getItem(SB_TOKEN_KEY)) {
          appendLog(
            "Supabase: " + (e && e.message ? e.message : e),
            "err"
          );
        }
      }
      return;
    }
  }

  function prefersReducedMotion() {
    return window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  }

  /** Медленный приятный sweep кольца (--p). Первый раз — с нуля; дальше — к новому значению. */
  function setDonut(id, pct, colorVar) {
    const el = $(id);
    if (!el) return;
    const p = Math.max(0, Math.min(100, Number(pct) || 0));
    const prev = el.dataset.p != null && el.dataset.p !== "" ? Number(el.dataset.p) : NaN;
    if (colorVar) el.style.setProperty("--c", colorVar);

    const same = el.dataset.ignited === "1" && Number.isFinite(prev) && Math.abs(prev - p) < 0.35;
    if (same) {
      el.style.setProperty("--p", String(p));
      return;
    }

    if (prefersReducedMotion()) {
      el.dataset.p = String(p);
      el.dataset.ignited = "1";
      el.style.setProperty("--p", String(p));
      return;
    }

    const first = el.dataset.ignited !== "1";
    el.dataset.p = String(p);
    el.style.transition = "--p 2.1s cubic-bezier(0.22, 0.8, 0.24, 1)";

    if (first) {
      el.style.transition = "none";
      el.style.setProperty("--p", "0");
      void el.offsetWidth;
      el.style.transition = "--p 2.1s cubic-bezier(0.22, 0.8, 0.24, 1)";
      el.dataset.ignited = "1";
      requestAnimationFrame(function () {
        requestAnimationFrame(function () {
          el.style.setProperty("--p", String(p));
        });
      });
      return;
    }

    el.style.setProperty("--p", String(p));
  }

  function igniteAllDonuts() {
    document.querySelectorAll(".widget-card .donut[id]").forEach(function (el) {
      let target = NaN;
      const attr = el.getAttribute("data-target-p");
      if (attr != null && attr !== "") target = Number(attr);
      if (!Number.isFinite(target) && el.dataset.p != null && el.dataset.p !== "") {
        target = Number(el.dataset.p);
      }
      if (!Number.isFinite(target)) {
        const inline = el.style.getPropertyValue("--p").trim();
        target = inline !== "" ? Number(inline) : 0;
      }
      if (!Number.isFinite(target)) target = 0;
      el.dataset.ignited = "0";
      el.dataset.p = "";
      const color = el.style.getPropertyValue("--c").trim();
      setDonut(el.id, target, color || undefined);
    });
  }

  function igniteRegimeDonut() {
    const el = $("widget-regime-donut");
    if (!el) return;
    const target = el.getAttribute("data-target-p");
    const p = target != null && target !== "" ? Number(target) : 100;
    setDonut("widget-regime-donut", Number.isFinite(p) ? p : 100);
  }

  function appendLog(msg, cls) {
    const box = $("ops-log");
    if (box) {
      const line = document.createElement("div");
      line.className = cls || "info";
      const ts = new Date().toLocaleTimeString();
      line.textContent = "[" + ts + "] " + msg;
      box.prepend(line);
      const lines = box.querySelectorAll("div");
      if (lines.length > 40) lines[lines.length - 1].remove();
    }
  }

  function updateSessionBar() {
    const bar = $("auth-session-bar");
    if (!bar) return;
    const sb = authMode.supabase || {};
    if (!sb.enabled) {
      bar.hidden = true;
      bar.innerHTML = "";
      return;
    }
    bar.hidden = false;
    const email = (localStorage.getItem(SB_EMAIL_KEY) || "").trim();
    const token = localStorage.getItem(SB_TOKEN_KEY);
    if (token) {
      bar.className = "auth-session-bar ok";
      bar.innerHTML =
        '<span class="auth-session-text">Сессия: <strong>' +
        escapeHtml(email || "кабинет") +
        "</strong> — API с Bearer JWT</span>" +
        '<button type="button" class="btn btn-ghost auth-session-btn" id="auth-logout-btn">Выйти</button>';
      const btn = $("auth-logout-btn");
      if (btn) {
        btn.addEventListener("click", function () {
          try {
            localStorage.removeItem(SB_TOKEN_KEY);
            localStorage.removeItem(SB_EMAIL_KEY);
            sessionStorage.removeItem(WELCOME_SESSION_KEY);
          } catch (_) { /* ignore */ }
          updateSessionBar();
          appendLog("Сессия сброшена — войдите снова.", "info");
          maybeShowAuthGate();
        });
      }
    } else {
      bar.className = "auth-session-bar need";
      bar.innerHTML =
        '<span class="auth-session-text"><strong>Нужен вход</strong> — email и пароль кабинета TRINITY (кнопки анализа без сессии вернут 401).</span>' +
        '<a class="btn btn-primary auth-session-btn" href="/view/settings">Войти в Настройках</a>';
    }
  }

  function escapeHtml(s) {
    return String(s)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  let authCanvasRaf = 0;
  let authCanvasStop = null;

  function isDashboardPath() {
    const path = (location.pathname || "").replace(/\/+$/, "") || "/view";
    return path === "/view" || path === "";
  }

  function stopAuthCanvas() {
    if (authCanvasRaf) {
      cancelAnimationFrame(authCanvasRaf);
      authCanvasRaf = 0;
    }
    if (typeof authCanvasStop === "function") {
      authCanvasStop();
      authCanvasStop = null;
    }
  }

  function startAuthCanvas() {
    const canvas = $("trinity-auth-canvas");
    if (!canvas || !canvas.getContext) return;
    stopAuthCanvas();
    const ctx = canvas.getContext("2d");
    const green = "#1f9a68";
    const red = "#c94a4a";
    const gold = "rgba(196,163,90,0.85)";
    const cyan = "rgba(126,182,212,0.75)";
    let w = 0;
    let h = 0;
    let dpr = 1;
    const candles = [];
    const CANDLE_N = 42;

    function resize() {
      dpr = Math.min(window.devicePixelRatio || 1, 2);
      w = window.innerWidth;
      h = window.innerHeight;
      canvas.width = Math.floor(w * dpr);
      canvas.height = Math.floor(h * dpr);
      canvas.style.width = w + "px";
      canvas.style.height = h + "px";
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    }

    function seedCandles() {
      candles.length = 0;
      let price = 100;
      for (let i = 0; i < CANDLE_N; i++) {
        const drift = (Math.sin(i * 0.35) + Math.cos(i * 0.17)) * 1.1;
        const open = price;
        const close = open + drift + (Math.random() - 0.48) * 2.4;
        const high = Math.max(open, close) + Math.random() * 1.8;
        const low = Math.min(open, close) - Math.random() * 1.8;
        candles.push({ open: open, close: close, high: high, low: low });
        price = close;
      }
    }

    function pushCandle() {
      const last = candles[candles.length - 1];
      const open = last.close;
      const close = open + (Math.random() - 0.47) * 3.2 + Math.sin(Date.now() / 900) * 0.4;
      candles.push({
        open: open,
        close: close,
        high: Math.max(open, close) + Math.random() * 1.6,
        low: Math.min(open, close) - Math.random() * 1.6
      });
      if (candles.length > CANDLE_N) candles.shift();
    }

    function ema(period) {
      const k = 2 / (period + 1);
      const out = [];
      let prev = candles[0].close;
      for (let i = 0; i < candles.length; i++) {
        prev = candles[i].close * k + prev * (1 - k);
        out.push(prev);
      }
      return out;
    }

    let lastPush = 0;
    let t0 = performance.now();

    function draw(now) {
      const elapsed = (now - t0) / 1000;
      if (now - lastPush > 420) {
        pushCandle();
        lastPush = now;
      }

      ctx.clearRect(0, 0, w, h);
      const g = ctx.createLinearGradient(0, 0, w * 0.2, h);
      g.addColorStop(0, "#0a1218");
      g.addColorStop(0.4, "#152028");
      g.addColorStop(0.75, "#1a2a34");
      g.addColorStop(1, "#0e171e");
      ctx.fillStyle = g;
      ctx.fillRect(0, 0, w, h);

      /* warm vignette + gold rim light */
      const vig = ctx.createRadialGradient(w * 0.5, h * 0.42, h * 0.1, w * 0.5, h * 0.5, h * 0.85);
      vig.addColorStop(0, "rgba(0,0,0,0)");
      vig.addColorStop(0.7, "rgba(8,12,16,0.15)");
      vig.addColorStop(1, "rgba(4,8,12,0.55)");
      ctx.fillStyle = vig;
      ctx.fillRect(0, 0, w, h);

      /* soft grid — more readable */
      ctx.strokeStyle = "rgba(196,163,90,0.06)";
      ctx.lineWidth = 1;
      const grid = 48;
      const ox = (elapsed * 14) % grid;
      for (let x = -grid + ox; x < w + grid; x += grid) {
        ctx.beginPath();
        ctx.moveTo(x, 0);
        ctx.lineTo(x, h);
        ctx.stroke();
      }
      ctx.strokeStyle = "rgba(255,255,255,0.045)";
      for (let y = 0; y < h; y += grid) {
        ctx.beginPath();
        ctx.moveTo(0, y);
        ctx.lineTo(w, y);
        ctx.stroke();
      }

      let minP = Infinity;
      let maxP = -Infinity;
      for (let i = 0; i < candles.length; i++) {
        minP = Math.min(minP, candles[i].low);
        maxP = Math.max(maxP, candles[i].high);
      }
      const pad = (maxP - minP) * 0.18 || 1;
      minP -= pad;
      maxP += pad;
      const chartTop = h * 0.14;
      const chartH = h * 0.56;
      const chartLeft = w * 0.07;
      const chartW = w * 0.86;
      const slot = chartW / CANDLE_N;

      function yOf(p) {
        return chartTop + ((maxP - p) / (maxP - minP)) * chartH;
      }

      /* volume bars */
      for (let i = 0; i < candles.length; i++) {
        const c = candles[i];
        const vol = Math.abs(c.close - c.open) * 10 + 10;
        const x = chartLeft + i * slot + slot * 0.18;
        const bw = Math.max(2, slot * 0.58);
        ctx.fillStyle = c.close >= c.open ? "rgba(31,154,104,0.22)" : "rgba(201,74,74,0.2)";
        ctx.fillRect(x, chartTop + chartH + 16, bw, Math.min(56, vol));
      }

      const emaFast = ema(5);
      const emaSlow = ema(13);

      /* area fill under slow EMA — depth */
      ctx.beginPath();
      for (let i = 0; i < emaSlow.length; i++) {
        const x = chartLeft + i * slot + slot * 0.5;
        const y = yOf(emaSlow[i]);
        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      }
      ctx.lineTo(chartLeft + (emaSlow.length - 1) * slot + slot * 0.5, chartTop + chartH);
      ctx.lineTo(chartLeft + slot * 0.5, chartTop + chartH);
      ctx.closePath();
      const area = ctx.createLinearGradient(0, chartTop, 0, chartTop + chartH);
      area.addColorStop(0, "rgba(196,163,90,0.14)");
      area.addColorStop(1, "rgba(196,163,90,0)");
      ctx.fillStyle = area;
      ctx.fill();

      function strokeSeries(series, color, width) {
        ctx.beginPath();
        ctx.strokeStyle = color;
        ctx.lineWidth = width;
        ctx.lineJoin = "round";
        ctx.lineCap = "round";
        for (let i = 0; i < series.length; i++) {
          const x = chartLeft + i * slot + slot * 0.5;
          const y = yOf(series[i]);
          if (i === 0) ctx.moveTo(x, y);
          else ctx.lineTo(x, y);
        }
        ctx.stroke();
      }
      strokeSeries(emaSlow, gold, 2.1);
      strokeSeries(emaFast, cyan, 2.2);

      /* Bollinger-ish envelope */
      ctx.beginPath();
      ctx.strokeStyle = "rgba(255,255,255,0.18)";
      ctx.setLineDash([5, 7]);
      for (let i = 0; i < candles.length; i++) {
        const x = chartLeft + i * slot + slot * 0.5;
        const mid = emaSlow[i];
        const y = yOf(mid + (maxP - minP) * 0.08);
        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      }
      ctx.stroke();
      ctx.beginPath();
      for (let i = 0; i < candles.length; i++) {
        const x = chartLeft + i * slot + slot * 0.5;
        const mid = emaSlow[i];
        const y = yOf(mid - (maxP - minP) * 0.08);
        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      }
      ctx.stroke();
      ctx.setLineDash([]);

      for (let i = 0; i < candles.length; i++) {
        const c = candles[i];
        const x = chartLeft + i * slot + slot * 0.5;
        const up = c.close >= c.open;
        const col = up ? green : red;
        ctx.strokeStyle = col;
        ctx.fillStyle = col;
        ctx.lineWidth = 1.6;
        ctx.beginPath();
        ctx.moveTo(x, yOf(c.high));
        ctx.lineTo(x, yOf(c.low));
        ctx.stroke();
        const bodyTop = yOf(Math.max(c.open, c.close));
        const bodyBot = yOf(Math.min(c.open, c.close));
        const bw = Math.max(3.5, slot * 0.55);
        ctx.shadowColor = up ? "rgba(31,154,104,0.45)" : "rgba(201,74,74,0.4)";
        ctx.shadowBlur = 8;
        ctx.globalAlpha = 0.95;
        ctx.fillRect(x - bw / 2, bodyTop, bw, Math.max(2.5, bodyBot - bodyTop));
        ctx.shadowBlur = 0;
        ctx.globalAlpha = 1;
      }

      /* floating markers */
      const pulse = 0.5 + 0.5 * Math.sin(elapsed * 2.2);
      const last = candles[candles.length - 1];
      const lx = chartLeft + (candles.length - 1) * slot + slot * 0.5;
      const ly = yOf(last.close);
      ctx.beginPath();
      ctx.fillStyle = last.close >= last.open ? green : red;
      ctx.shadowColor = ctx.fillStyle;
      ctx.shadowBlur = 12;
      ctx.arc(lx, ly, 4 + pulse * 1.8, 0, Math.PI * 2);
      ctx.fill();
      ctx.shadowBlur = 0;
      ctx.beginPath();
      ctx.strokeStyle = "rgba(255,255,255,0.45)";
      ctx.lineWidth = 1.2;
      ctx.arc(lx, ly, 12 + pulse * 7, 0, Math.PI * 2);
      ctx.stroke();

      /* crosshair */
      ctx.strokeStyle = "rgba(196,163,90,0.22)";
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(chartLeft, ly);
      ctx.lineTo(chartLeft + chartW, ly);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(lx, chartTop);
      ctx.lineTo(lx, chartTop + chartH);
      ctx.stroke();

      /* price tag */
      const tag = (last.close * 10 + 2140).toFixed(1);
      ctx.font = "600 12px IBM Plex Mono, ui-monospace, monospace";
      const tw = ctx.measureText(tag).width + 14;
      ctx.fillStyle = "rgba(196,163,90,0.92)";
      if (ctx.roundRect) {
        ctx.beginPath();
        ctx.roundRect(chartLeft + chartW + 6, ly - 10, tw, 20, 4);
        ctx.fill();
      } else {
        ctx.fillRect(chartLeft + chartW + 6, ly - 10, tw, 20);
      }
      ctx.fillStyle = "#12181e";
      ctx.fillText(tag, chartLeft + chartW + 13, ly + 4);

      /* RSI strip */
      const rsiY = chartTop + chartH + 88;
      ctx.fillStyle = "rgba(255,255,255,0.045)";
      ctx.fillRect(chartLeft, rsiY, chartW, 58);
      ctx.strokeStyle = "rgba(196,163,90,0.2)";
      ctx.strokeRect(chartLeft + 0.5, rsiY + 0.5, chartW - 1, 57);
      ctx.beginPath();
      ctx.strokeStyle = "rgba(126,182,212,0.75)";
      ctx.lineWidth = 1.8;
      for (let i = 0; i < candles.length; i++) {
        const c = candles[i];
        const rsi = 50 + Math.tanh((c.close - c.open) / 2) * 28 + Math.sin(i * 0.4 + elapsed) * 6;
        const x = chartLeft + i * slot + slot * 0.5;
        const y = rsiY + 58 - ((rsi - 20) / 60) * 58;
        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      }
      ctx.stroke();
      ctx.strokeStyle = "rgba(196,163,90,0.4)";
      ctx.beginPath();
      ctx.moveTo(chartLeft, rsiY + 18);
      ctx.lineTo(chartLeft + chartW, rsiY + 18);
      ctx.stroke();

      /* corner monogram feel */
      ctx.fillStyle = "rgba(196,163,90,0.28)";
      ctx.font = "600 11px IBM Plex Sans, sans-serif";
      ctx.fillText("TRINITY  ·  LIVE DESK", chartLeft, chartTop - 18);

      if (!prefersReducedMotion()) {
        authCanvasRaf = requestAnimationFrame(draw);
      }
    }

    resize();
    seedCandles();
    const onResize = function () {
      resize();
    };
    window.addEventListener("resize", onResize);
    authCanvasStop = function () {
      window.removeEventListener("resize", onResize);
    };
    lastPush = performance.now();
    if (prefersReducedMotion()) {
      draw(performance.now());
    } else {
      authCanvasRaf = requestAnimationFrame(draw);
    }
  }

  function setGateError(msg) {
    const el = $("trinity-auth-error");
    if (!el) return;
    if (!msg) {
      el.hidden = true;
      el.textContent = "";
      return;
    }
    el.hidden = false;
    el.textContent = msg;
  }

  function openAuthGate() {
    const gate = $("trinity-auth-gate");
    if (!gate) return;
    gate.hidden = false;
    gate.setAttribute("aria-hidden", "false");
    gate.classList.remove("is-leaving", "is-welcome", "is-done");
    gate.classList.add("is-open");
    document.body.classList.add("trinity-gate-lock");
    const welcome = $("trinity-welcome");
    const modal = $("trinity-auth-modal");
    if (welcome) welcome.hidden = true;
    if (modal) modal.hidden = false;
    setGateError("");
    startAuthCanvas();
    const email = localStorage.getItem(SB_EMAIL_KEY) || localStorage.getItem(USER_KEY) || "";
    if ($("gate-user") && !$("gate-user").value) $("gate-user").value = email;
    setTimeout(function () {
      if ($("gate-user") && !$("gate-user").value) $("gate-user").focus();
      else if ($("gate-pass")) $("gate-pass").focus();
    }, 120);
  }

  function closeAuthGateHard() {
    const gate = $("trinity-auth-gate");
    if (!gate) return;
    stopAuthCanvas();
    gate.hidden = true;
    gate.setAttribute("aria-hidden", "true");
    gate.classList.remove("is-open", "is-leaving", "is-welcome", "is-done");
    document.body.classList.remove("trinity-gate-lock");
  }

  function playWelcomeThenClose() {
    const gate = $("trinity-auth-gate");
    const modal = $("trinity-auth-modal");
    const welcome = $("trinity-welcome");
    if (!gate) return;
    try {
      sessionStorage.setItem(WELCOME_SESSION_KEY, "1");
    } catch (_) { /* ignore */ }

    gate.classList.add("is-leaving");
    gate.classList.remove("is-open");
    setTimeout(function () {
      if (modal) modal.hidden = true;
      if (welcome) welcome.hidden = false;
      gate.classList.add("is-welcome");
      gate.classList.remove("is-leaving");
    }, prefersReducedMotion() ? 0 : 520);

    setTimeout(function () {
      gate.classList.add("is-done");
    }, prefersReducedMotion() ? 3900 : 6200);

    setTimeout(function () {
      closeAuthGateHard();
    }, prefersReducedMotion() ? 4100 : 6900);
  }

  function maybeShowAuthGate() {
    const sb = authMode.supabase || {};
    if (!sb.enabled) {
      closeAuthGateHard();
      return;
    }
    if (!isDashboardPath()) {
      /* Gate is a dashboard entrance ritual; other pages keep the session bar. */
      if (localStorage.getItem(SB_TOKEN_KEY)) closeAuthGateHard();
      return;
    }
    if (localStorage.getItem(SB_TOKEN_KEY)) {
      closeAuthGateHard();
      return;
    }
    openAuthGate();
  }

  async function submitAuthGate(ev) {
    if (ev) ev.preventDefault();
    const sb = authMode.supabase || {};
    if (!sb.enabled) return;
    setGateError("");
    const btn = $("gate-login-btn");
    if (btn) btn.disabled = true;
    try {
      if ($("gate-user")) localStorage.setItem(USER_KEY, $("gate-user").value || "");
      await ensureSupabaseSession(true);
      if (localStorage.getItem(SB_TOKEN_KEY)) {
        playWelcomeThenClose();
      }
    } catch (e) {
      setGateError(e && e.message ? e.message : String(e));
    } finally {
      if (btn) btn.disabled = false;
    }
  }

  function setText(id, text) {
    const el = $(id);
    if (el) el.textContent = text;
  }

  function setBusy(on) {
    const bar = $("ops-busy");
    if (bar) bar.classList.toggle("on", !!on);
    document.querySelectorAll("[data-ops-action]").forEach(function (btn) {
      btn.disabled = !!on;
    });
    ["broker-save-settings", "broker-sandbox-account", "broker-sandbox-payin"].forEach(function (id) {
      if ($(id)) $(id).disabled = !!on;
    });
  }

  function alertsEnabled() {
    const el = $("ops-alerts-enabled");
    if (el) return el.checked;
    return localStorage.getItem(ALERTS_ENABLED_KEY) !== "off";
  }

  function soundEnabled() {
    const el = $("ops-alerts-sound");
    if (el) return el.checked;
    return localStorage.getItem(ALERTS_SOUND_KEY) !== "off";
  }

  function loadSeenIds() {
    try {
      return new Set(JSON.parse(localStorage.getItem(SEEN_IDS_KEY) || "[]"));
    } catch (_) {
      return new Set();
    }
  }

  function saveSeenIds(set) {
    const arr = Array.from(set).slice(-120);
    localStorage.setItem(SEEN_IDS_KEY, JSON.stringify(arr));
  }

  function playAlertSound(kind, pnlRub) {
    if (!soundEnabled()) return;
    try {
      const Ctx = window.AudioContext || window.webkitAudioContext;
      if (!Ctx) return;
      const ctx = new Ctx();
      function beep(freq, start, dur) {
        const o = ctx.createOscillator();
        const g = ctx.createGain();
        o.type = "sine";
        o.frequency.value = freq;
        g.gain.value = 0.11;
        o.connect(g);
        g.connect(ctx.destination);
        o.start(start);
        o.stop(start + dur);
      }
      const k = String(kind || "").toUpperCase();
      if (k === "CLOSE" && typeof pnlRub === "number" && pnlRub < 0) {
        beep(520, ctx.currentTime, 0.16);
        beep(360, ctx.currentTime + 0.18, 0.22);
      } else if (k === "CLOSE") {
        beep(660, ctx.currentTime, 0.14);
        beep(990, ctx.currentTime + 0.16, 0.2);
        beep(1320, ctx.currentTime + 0.34, 0.16);
      } else if (k === "SIGNAL") {
        beep(740, ctx.currentTime, 0.12);
        beep(980, ctx.currentTime + 0.14, 0.18);
      } else {
        beep(880, ctx.currentTime, 0.16);
        beep(1100, ctx.currentTime + 0.2, 0.2);
      }
    } catch (_) {
      // ignore
    }
  }

  function toastToneClass(evt) {
    const kind = String(evt.kind || "").toUpperCase();
    if (kind === "SIGNAL") return "toast--signal";
    if (kind === "CLOSE") {
      const pnl = evt.pnlRub;
      if (typeof pnl === "number" && isFinite(pnl) && pnl < 0) return "toast--exit-loss";
      return "toast--exit-win";
    }
    return "toast--enter";
  }

  function formatToastPnl(v, approx) {
    if (v == null || typeof v !== "number" || !isFinite(v)) return null;
    const sign = v > 0 ? "+" : "";
    const prefix = approx ? "~" : "";
    return prefix + sign + Math.round(v).toLocaleString("ru-RU") + " ₽";
  }

  function showNativeNotification(evt) {
    if (!("Notification" in window) || Notification.permission !== "granted") {
      return;
    }
    try {
      const title = evt.title || "TRINITY";
      const body = evt.summary || evt.instrument || "";
      new Notification(title, {
        body: body,
        tag: evt.id,
        requireInteraction: false
      });
    } catch (_) {
      // ignore
    }
  }

  function showPremiumToast(evt) {
    const stack = $("trinity-toast-stack");
    if (!stack || !evt) return;

    const el = document.createElement("div");
    el.className = "toast " + toastToneClass(evt);
    el.setAttribute("role", "alert");

    const kind = String(evt.kind || "").toUpperCase();
    let pnlLine = "";
    if (kind === "CLOSE") {
      const fact = formatToastPnl(evt.pnlRub, false);
      if (fact) {
        const cls = (typeof evt.pnlRub === "number" && evt.pnlRub < 0) ? "toast-pnl is-loss" : "toast-pnl is-win";
        pnlLine = '<div class="' + cls + '">' + fact + "</div>";
      }
    } else {
      const pot = formatToastPnl(evt.potentialPnlRub, true);
      if (pot) {
        pnlLine = '<div class="toast-pnl is-potential">потенциал ' + pot + "</div>";
      }
    }

    const href = evt.href || (evt.strategy === "TREND" ? "/view/trend-signal" : "/view/statement");
    const linkLabel = evt.strategy === "TREND" ? "Trend" : "Paper journal";
    const metaExtra = evt.instrument
      ? '<span class="toast-instrument">' + escapeHtml(evt.instrument) + "</span>"
      : "";

    el.innerHTML =
      '<button type="button" class="toast-close" aria-label="Закрыть">&times;</button>' +
      '<div class="toast-kicker">' + escapeHtml(evt.strategy || "TRINITY") + "</div>" +
      "<strong>" + escapeHtml(evt.title || "Событие") + "</strong>" +
      '<div class="toast-body">' + escapeHtml(evt.summary || "") + "</div>" +
      pnlLine +
      '<div class="toast-meta">' + metaExtra +
      (evt.side ? " · " + escapeHtml(evt.side) : "") +
      ' · <a href="' + href + '">' + linkLabel + "</a></div>";

    el.querySelector(".toast-close").addEventListener("click", function () {
      el.remove();
    });

    stack.prepend(el);
    setTimeout(function () {
      if (el.parentNode) el.remove();
    }, 22000);
  }

  function handleNewAlert(evt) {
    if (!alertsEnabled()) return;
    const kind = String(evt.kind || "OPEN").toUpperCase();
    playAlertSound(kind, evt.pnlRub);
    showPremiumToast(evt);
    showNativeNotification(evt);
    appendLog((evt.title || "Toast") + ": " + (evt.summary || evt.instrument || evt.id), 
      kind === "CLOSE" && typeof evt.pnlRub === "number" && evt.pnlRub < 0 ? "err" : "ok");
  }

  /** @deprecated use showPremiumToast */
  function showToast(alert) {
    showPremiumToast(alert);
  }

  function currentPagePath() {
    return (location.pathname || "/view").replace(/\/+$/, "") || "/view";
  }

  function escapeHtml(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  async function recordUpsellEvent(action, page) {
    try {
      await fetch("/api/upsell/events", {
        method: "POST",
        headers: withAuthHeaders({ "Content-Type": "application/json" }),
        body: JSON.stringify({
          action: action || "unknown",
          page: page || currentPagePath(),
          tierHint: null
        })
      });
    } catch (_) {
      // soft beacon — never break ops
    }
  }

  function hideUpsellCard() {
    const host = $("trinity-upsell-host");
    if (host) host.innerHTML = "";
  }

  async function dismissUpsell(prompt) {
    hideUpsellCard();
    try {
      sessionStorage.removeItem(UPSELL_SHOWN_KEY);
    } catch (_) {}
    try {
      await fetch("/api/upsell/dismiss", {
        method: "POST",
        headers: withAuthHeaders({ "Content-Type": "application/json" }),
        body: JSON.stringify({
          promptId: prompt && prompt.id,
          featureKey: (prompt && prompt.featureKey) || "CALENDAR_ARB"
        })
      });
    } catch (_) {
      // ignore
    }
  }

  function showUpsellPrompt(prompt, force) {
    const host = $("trinity-upsell-host");
    if (!host || !prompt || !prompt.id) return;
    if (!force && sessionStorage.getItem(UPSELL_SHOWN_KEY) === prompt.id) return;
    if (!force) sessionStorage.setItem(UPSELL_SHOWN_KEY, prompt.id);

    const href = prompt.ctaHref || "/view/full-core";
    const cta = prompt.ctaLabel || "Подробнее";
    const card = document.createElement("aside");
    card.className = "upsell-card";
    card.setAttribute("role", "complementary");
    card.innerHTML =
      '<button type="button" class="upsell-close" aria-label="Закрыть">&times;</button>' +
      "<strong>" + escapeHtml(prompt.title) + "</strong>" +
      "<p>" + escapeHtml(prompt.body) + "</p>" +
      '<div class="upsell-actions">' +
      '<a href="' + escapeHtml(href) + '">' + escapeHtml(cta) + "</a>" +
      '<button type="button" class="upsell-dismiss">Скрыть</button>' +
      "</div>";

    card.querySelector(".upsell-close").addEventListener("click", function () {
      dismissUpsell(prompt);
    });
    card.querySelector(".upsell-dismiss").addEventListener("click", function () {
      dismissUpsell(prompt);
    });

    host.innerHTML = "";
    host.appendChild(card);
  }

  async function maybeShowUpsell() {
    try {
      const res = await fetch("/api/upsell/prompt", { headers: { Accept: "application/json" } });
      if (res.status === 204 || !res.ok) return;
      const prompt = await res.json();
      if (prompt && prompt.id) showUpsellPrompt(prompt, false);
    } catch (_) {
      // ignore
    }
  }

  async function showFullCoreTip() {
    try {
      const res = await fetch("/api/upsell/tip", { headers: { Accept: "application/json" } });
      if (res.status === 204 || !res.ok) return;
      const prompt = await res.json();
      if (prompt && prompt.id) showUpsellPrompt(prompt, true);
    } catch (_) {
      // ignore
    }
  }

  async function beaconUpsell(action, page) {
    await recordUpsellEvent(action, page);
    await maybeShowUpsell();
  }

  function bindFullCoreTeasers() {
    document.addEventListener("click", function (ev) {
      const el = ev.target && ev.target.closest
        ? ev.target.closest("[data-core-upsell]")
        : null;
      if (!el) return;
      const kind = el.getAttribute("data-core-upsell") || "teaser";
      recordUpsellEvent("full_core_" + kind, currentPagePath());
      if (el.tagName === "BUTTON" || kind === "cta") {
        ev.preventDefault();
        showFullCoreTip();
      }
    });
  }

  async function seedSeenFromJournal() {
    if (localStorage.getItem(SEEN_IDS_KEY)) {
      return;
    }
    try {
      const res = await fetch("/api/paper/journal", { headers: { Accept: "application/json" } });
      if (!res.ok) return;
      const journal = await res.json();
      const ids = (journal.entries || []).map(function (e) { return e.id; });
      localStorage.setItem(SEEN_IDS_KEY, JSON.stringify(ids));
    } catch (_) {
      // ignore
    }
  }

  async function pollTradeToasts() {
    if (!alertsEnabled()) return;
    try {
      const res = await fetch("/api/ops/trade-toasts", { headers: { Accept: "application/json" } });
      if (!res.ok) return;
      const alerts = await res.json();
      if (!Array.isArray(alerts)) return;

      const seen = loadSeenIds();
      let anyNew = false;
      alerts.forEach(function (alert) {
        if (!alert || !alert.id || seen.has(alert.id)) return;
        seen.add(alert.id);
        anyNew = true;
        handleNewAlert(alert);
      });
      if (anyNew) {
        saveSeenIds(seen);
      }
    } catch (_) {
      // ignore transient network errors
    }
  }

  async function pollPaperAlerts() {
    return pollTradeToasts();
  }

  async function pollAutoRunStatus() {
    try {
      const res = await fetch("/api/ops/auto-run/status", { headers: { Accept: "application/json" } });
      if (!res.ok) return;
      const st = await res.json();
      // INTRADAY cron detached from operator UX — ignore lastIntraday* fields
      if (st && st.lastDailyRunAt && st.lastDailyRunStatus) {
        const key = "imoex.lastDailyLog";
        const msg = st.lastDailyRunAt + " " + st.lastDailyRunStatus;
        if (localStorage.getItem(key) !== msg && st.lastDailyRunStatus !== "RUNNING") {
          localStorage.setItem(key, msg);
          appendLog("DAILY cron: " + st.lastDailyRunStatus + " @ " + st.lastDailyRunAt, "info");
        }
      }
    } catch (_) {
      // ignore
    }
  }

  function fmtMoneyRub(v) {
    if (v == null || typeof v !== "number" || !isFinite(v)) return "—";
    return (v >= 0 ? "+" : "") + v.toFixed(0) + " ₽";
  }

  function fmtWhen(iso) {
    if (!iso) return "—";
    try {
      const d = new Date(iso);
      if (isNaN(d.getTime())) return String(iso);
      return d.toLocaleString("ru-RU", {
        day: "2-digit",
        month: "short",
        year: "numeric",
        hour: "2-digit",
        minute: "2-digit"
      });
    } catch (_) {
      return String(iso);
    }
  }

  function setBackStats(id, rows) {
    const el = $(id);
    if (!el) return;
    el.innerHTML = (rows || []).map(function (r) {
      return '<div class="widget-stat"><span class="k">' + escapeHtml(r.k) +
        '</span><span class="v">' + escapeHtml(r.v) + "</span></div>";
    }).join("");
  }

  function bindWidgetFlips() {
    document.querySelectorAll(".widget-card.is-flippable").forEach(function (card) {
      if (card.dataset.flipBound) return;
      card.dataset.flipBound = "1";
      card.classList.add("flip-settled");
      const flip = card.querySelector(".widget-flip");
      if (flip) {
        flip.addEventListener("transitionend", function (ev) {
          if (ev.propertyName !== "transform") return;
          card.classList.add("flip-settled");
        });
      }
      function toggle() {
        card.classList.remove("flip-settled");
        const on = card.classList.toggle("is-flipped");
        card.setAttribute("aria-pressed", on ? "true" : "false");
        if (prefersReducedMotion()) {
          card.classList.add("flip-settled");
        }
      }
      card.addEventListener("click", function (ev) {
        if (ev.target && ev.target.closest && ev.target.closest("a,button,input,select,textarea,label")) {
          return;
        }
        toggle();
      });
      card.addEventListener("keydown", function (ev) {
        if (ev.key === "Enter" || ev.key === " ") {
          ev.preventDefault();
          toggle();
        }
      });
    });
    syncWidgetCardHeights();
    if (!window.__trinityFlipResizeBound) {
      window.__trinityFlipResizeBound = true;
      let t = 0;
      window.addEventListener("resize", function () {
        clearTimeout(t);
        t = setTimeout(syncWidgetCardHeights, 120);
      });
    }
  }

  /** Все карточки дашборда — одна высота (= max по всем рядам). */
  function syncWidgetCardHeights() {
    const cards = Array.prototype.slice.call(
      document.querySelectorAll(".widget-grid .widget-card.is-flippable")
    );
    if (!cards.length) return;

    let globalMax = 360;
    const measured = cards.map(function (card) {
      const flip = card.querySelector(".widget-flip");
      const front = card.querySelector(".widget-front");
      const back = card.querySelector(".widget-back");
      if (!flip || !front || !back) return null;

      function naturalHeight(face) {
        const probe = face.cloneNode(true);
        probe.removeAttribute("id");
        probe.querySelectorAll("[id]").forEach(function (n) { n.removeAttribute("id"); });
        probe.style.cssText = [
          "position:static",
          "transform:none",
          "-webkit-transform:none",
          "visibility:hidden",
          "opacity:1",
          "pointer-events:none",
          "height:auto",
          "min-height:0",
          "inset:auto",
          "display:block",
          "width:" + flip.clientWidth + "px"
        ].join(";");
        flip.appendChild(probe);
        const h = Math.ceil(probe.getBoundingClientRect().height);
        probe.remove();
        return h;
      }

      const h = Math.max(360, naturalHeight(front), naturalHeight(back)) + 4;
      globalMax = Math.max(globalMax, h);
      return { card: card, flip: flip, front: front, back: back };
    });

    measured.forEach(function (m) {
      if (!m) return;
      m.flip.style.height = globalMax + "px";
      m.card.style.height = globalMax + "px";
      m.card.style.minHeight = globalMax + "px";
      m.front.style.height = globalMax + "px";
      m.back.style.height = globalMax + "px";
      m.front.style.width = "100%";
      m.back.style.width = "100%";
    });
  }

  function bookLabel(book) {
    if (!book) return "Pairs / DAILY";
    const b = String(book).toUpperCase();
    if (b === "INTRADAY") return "Pairs / INTRADAY (research)";
    return "Pairs / DAILY";
  }

  async function loadBrokerWidget() {
    if (!$("broker-widget") && !$("widget-broker") && !$("dash-broker-status")) return;
    try {
      const [statusRes, reconcileRes, reportsRes] = await Promise.all([
        fetch("/api/broker/status", { headers: { Accept: "application/json" } }),
        fetch("/api/broker/reconcile", { headers: { Accept: "application/json" } }),
        fetch("/api/broker/reports", { headers: { Accept: "application/json" } })
      ]);
      if (statusRes.ok) {
        const status = await statusRes.json();
        if ($("broker-status-line")) {
          setText("broker-status-line",
            "Статус: " + (status.summary || status.provider || "брокер") +
            " · режим=" + (status.mode || "?") +
            " · песочница=" + (!!status.sandbox));
        }
        const armed = !!(status.enabled && status.tokenPresent && status.accountConfigured && !status.killSwitch);
        const readyPct = armed ? 100 : (status.tokenPresent ? 55 : (status.enabled ? 25 : 8));
        setDonut("widget-broker-donut", readyPct, armed ? "var(--ok)" : "var(--info)");
        setText("widget-broker-center", armed ? "OK" : (status.enabled ? "…" : "off"));
        if ($("dash-broker-status")) {
          const token = status.tokenPresent
            ? (status.accountConfigured ? "токен/счёт OK" : "нет счёта")
            : "нет токена";
          setText("dash-broker-status", token);
        }
        setText("widget-broker-mode",
          (status.mode || "?") + (status.sandbox ? " · sandbox" : " · live"));

        const lead = $("widget-broker-back-lead");
        if (lead) {
          lead.textContent = armed
            ? "Брокер подключён и готов к контуру исполнения."
            : (status.enabled
              ? "Брокер включён, но контур ещё не полностью готов."
              : "Брокер выключен — paper работает без live-исполнения.");
        }
        setBackStats("widget-broker-back-stats", [
          { k: "Провайдер", v: status.provider || "T-Invest" },
          { k: "Режим", v: (status.mode || "—") + (status.sandbox ? " · sandbox" : " · live") },
          { k: "Токен", v: status.tokenPresent ? "сохранён" : "нет" },
          { k: "Счёт", v: status.accountConfigured ? (status.accountId || "OK") : "не задан" },
          { k: "Kill-switch", v: status.killSwitch ? "ВКЛ" : "выкл" },
          { k: "Автоисполнение", v: status.autoExecuteAfterAnalysis ? "да" : "нет" }
        ]);
      }
      await loadMarketDataWidget();
      if (reconcileRes.ok && $("broker-reconcile-line")) {
        const rec = await reconcileRes.json();
        setText("broker-reconcile-line",
          "Сверка: " + (rec.summary || "—"));
      }
      if (reportsRes.ok && $("broker-journal-line")) {
        const reports = await reportsRes.json();
        const last = Array.isArray(reports) && reports.length ? reports[0] : null;
        setText("broker-journal-line",
          last
            ? "Последний отчёт брокера: " + last.status + " · " + last.summary
            : "Журнал брокера пока пуст.");
      }
    } catch (_) {
      if ($("broker-status-line")) {
        setText("broker-status-line", "Статус брокера временно недоступен.");
      }
    }
    syncWidgetCardHeights();
  }

  async function loadDashboardConsolidatedSummary() {
    if (!$("dash-paper-open") && !$("widget-paper")) return;
    try {
      const [paperRes, finalRes, signalsRes] = await Promise.all([
        fetch("/api/paper/journal", { headers: { Accept: "application/json" } }),
        fetch("/api/analysis/final", { headers: { Accept: "application/json" } }),
        fetch("/api/analysis/signals", { headers: { Accept: "application/json" } })
      ]);

      if (paperRes.ok) {
        const paper = await paperRes.json();
        const openCount = paper.openCount != null ? Number(paper.openCount) : 0;
        setText("dash-paper-open", String(openCount));
        setText("widget-paper-open-label", String(openCount));
        setDonut("widget-paper-donut", Math.min(100, openCount * 20), "var(--accent)");
        const realized = paper.realizedPnlRub;
        const unrealized = paper.unrealizedPnlRub;
        const hasAny = (typeof realized === "number" && isFinite(realized)) || (typeof unrealized === "number" && isFinite(unrealized));
        const pnlSum = hasAny ? (realized || 0) + (unrealized || 0) : null;
        const pnlEl = $("dash-paper-pnl");
        if (pnlEl) {
          pnlEl.textContent = pnlSum == null ? "—" : fmtMoneyRub(pnlSum);
          pnlEl.classList.toggle("bad", pnlSum != null && pnlSum < 0);
          pnlEl.classList.toggle("accent", pnlSum != null && pnlSum >= 0);
        }

        const entries = Array.isArray(paper.entries) ? paper.entries.slice() : [];
        const closed = entries.filter(function (e) { return e && String(e.status || "").toUpperCase() === "CLOSED"; });
        const last = closed.sort(function (a, b) {
          return String(b.closedAt || "").localeCompare(String(a.closedAt || ""));
        })[0] || entries.sort(function (a, b) {
          return String(b.openedAt || "").localeCompare(String(a.openedAt || ""));
        })[0];
        const lead = $("widget-paper-back-lead");
        if (lead) {
          if (!last) {
            lead.textContent = "Пока не было paper-сделок по стратегиям. После «Анализ + paper» здесь появится последняя запись.";
          } else if (String(last.status || "").toUpperCase() === "CLOSED") {
            lead.textContent =
              "Последняя сделка " + bookLabel(last.book) + " · " +
              (last.tickerY || "?") + "/" + (last.tickerX || "?") +
              " закрыта " + fmtWhen(last.closedAt) + ".";
          } else {
            lead.textContent =
              "Открыта позиция " + bookLabel(last.book) + " · " +
              (last.tickerY || "?") + "/" + (last.tickerX || "?") +
              " с " + fmtWhen(last.openedAt) + ".";
          }
        }
        setBackStats("widget-paper-back-stats", last ? [
          { k: "Стратегия", v: bookLabel(last.book) },
          { k: "Пара", v: (last.tickerY || "?") + " / " + (last.tickerX || "?") },
          { k: "Сигнал", v: last.signal || "—" },
          { k: "Статус", v: last.status || "—" },
          { k: "PnL", v: last.pnlRub != null ? fmtMoneyRub(Number(last.pnlRub))
            : (last.unrealizedPnlRub != null ? fmtMoneyRub(Number(last.unrealizedPnlRub)) + " MTM" : "—") },
          { k: "Закрытых / открытых", v: String(paper.closedCount != null ? paper.closedCount : closed.length)
            + " / " + String(openCount) }
        ] : [
          { k: "Открытых", v: String(openCount) },
          { k: "Закрытых", v: String(paper.closedCount != null ? paper.closedCount : 0) },
          { k: "PnL ₽", v: pnlSum == null ? "—" : fmtMoneyRub(pnlSum) }
        ]);
      }

      if (finalRes.ok) {
        const finals = await finalRes.json();
        const list = Array.isArray(finals) ? finals : [];
        const cnt = (decision) => list.filter(f => f && f.decision === decision).length;
        const actionable = cnt("ENTER") + cnt("REDUCE_SIZE");
        const watch = cnt("WATCH");
        const block = cnt("BLOCK");
        const total = Math.max(1, actionable + watch + block);
        setText("dash-final-actionable", String(actionable));
        setText("widget-final-enter", String(actionable));
        setText("dash-final-watch", String(watch));
        setText("dash-final-block", String(block));
        setDonut("widget-final-donut", Math.round((actionable / total) * 100), actionable > 0 ? "var(--ok)" : "var(--slate)");

        const top = list.filter(function (f) {
          return f && (f.decision === "ENTER" || f.decision === "REDUCE_SIZE");
        }).slice(0, 3);
        const fLead = $("widget-final-back-lead");
        if (fLead) {
          fLead.textContent = actionable > 0
            ? "Горячие решения после FA: " + actionable + " на вход/reduce."
            : (list.length
              ? "Сейчас нет ENTER — рынок/новости держат пары в WATCH или BLOCK."
              : "Итог ещё пуст. Запустите анализ с FA.");
        }
        setBackStats("widget-final-back-stats", top.length ? top.map(function (f) {
          const t = f.recommendation || f;
          const pair = (t.tickerY || "?") + "/" + (t.tickerX || "?");
          return { k: f.decision, v: pair };
        }) : [
          { k: "ENTER/REDUCE", v: String(actionable) },
          { k: "WATCH", v: String(watch) },
          { k: "BLOCK", v: String(block) }
        ]);
      }

      if (signalsRes.ok) {
        const signals = await signalsRes.json();
        const list = Array.isArray(signals) ? signals : [];
        const longs = list.filter(function (s) { return s && s.signal === "LONG_SPREAD"; }).length;
        const shorts = list.filter(function (s) { return s && s.signal === "SHORT_SPREAD"; }).length;
        setText("widget-signals-center", String(list.length));
        setText("widget-signals-long", String(longs));
        setText("widget-signals-short", String(shorts));
        setDonut("widget-signals-donut", Math.min(100, list.length * 15), list.length ? "var(--accent)" : "var(--slate)");
        const sLead = $("widget-signals-back-lead");
        if (sLead) {
          sLead.textContent = list.length
            ? "Технические сигналы до финального FA-гейта."
            : "Нет LONG/SHORT — режим или качество пар не дали входов.";
        }
        setBackStats("widget-signals-back-stats", [
          { k: "LONG_SPREAD", v: String(longs) },
          { k: "SHORT_SPREAD", v: String(shorts) },
          { k: "Всего", v: String(list.length) }
        ]);
      }

      const regimeLabel = ($("widget-regime-label") && $("widget-regime-label").textContent) || "";
      setBackStats("widget-regime-back-stats", [
        { k: "Режим", v: regimeLabel || "—" },
        { k: "Pairs paper", v: regimeLabel === "TREND" ? "новые входы блок" : "разрешены (после FA)" },
        { k: "TREND / ARB", v: regimeLabel === "TREND" ? "research в приоритете" : "research / ожидание" }
      ]);
      syncWidgetCardHeights();
    } catch (_) {
      // ignore intermittent network errors
    }
  }

  async function testBrokerConnection() {
    await prepareAuth();
    setBusy(true);
    appendLog("Проверяю подключение брокера…", "info");
    try {
      const res = await fetch("/api/broker/test-connection", {
        method: "POST",
        headers: withAuthHeaders()
      });
      const text = await res.text();
      const body = text ? JSON.parse(text) : null;
      if (!res.ok || !body) {
        appendLog("Не удалось проверить подключение брокера.", "err");
        return;
      }
      setText("broker-test-line", body.summary || "Проверка подключения выполнена.");
      appendLog(body.summary || "Проверка подключения выполнена.", body.snapshotAvailable ? "ok" : "info");
      await loadBrokerWidget();
    } catch (e) {
      appendLog(String(e && e.message ? e.message : e), "err");
    } finally {
      setBusy(false);
    }
  }

  function brokerSettingsPayload() {
    return {
      enabled: $("broker-enabled") ? $("broker-enabled").checked : false,
      provider: $("broker-provider") ? $("broker-provider").value : "T_INVEST",
      mode: $("broker-mode") ? $("broker-mode").value : "AUTO",
      sandbox: $("broker-sandbox") ? $("broker-sandbox").checked : true,
      token: $("broker-token") ? $("broker-token").value : "",
      accountId: $("broker-account-id") ? $("broker-account-id").value : "",
      autoExecuteAfterAnalysis: $("broker-auto-execute") ? $("broker-auto-execute").checked : true,
      preferLimitOrders: $("broker-prefer-limit") ? $("broker-prefer-limit").checked : true,
      allowMarketFallback: $("broker-allow-market") ? $("broker-allow-market").checked : false,
      emergencyMarketExitEnabled: $("broker-emergency-exit") ? $("broker-emergency-exit").checked : false,
      passivePriceOffsetBps: $("broker-passive-bps") ? Number($("broker-passive-bps").value || 0) : 15,
      secondLegTimeoutSeconds: $("broker-timeout-seconds") ? Number($("broker-timeout-seconds").value || 60) : 60,
      maxLegDriftBps: 35,
      killSwitch: $("broker-kill-switch") ? $("broker-kill-switch").checked : false
    };
  }

  function fillBrokerSettings(view) {
    if (!view || !$("broker-provider")) return;
    $("broker-enabled").checked = !!view.enabled;
    $("broker-provider").value = view.provider || "T_INVEST";
    $("broker-mode").value = view.mode || "AUTO";
    $("broker-sandbox").checked = !!view.sandbox;
    $("broker-account-id").value = view.accountId || "";
    $("broker-token").value = "";
    $("broker-auto-execute").checked = !!view.autoExecuteAfterAnalysis;
    $("broker-prefer-limit").checked = !!view.preferLimitOrders;
    $("broker-allow-market").checked = !!view.allowMarketFallback;
    $("broker-emergency-exit").checked = !!view.emergencyMarketExitEnabled;
    $("broker-passive-bps").value = view.passivePriceOffsetBps != null ? view.passivePriceOffsetBps : 15;
    $("broker-timeout-seconds").value = view.secondLegTimeoutSeconds != null ? view.secondLegTimeoutSeconds : 60;
    $("broker-kill-switch").checked = !!view.killSwitch;
    setText("broker-token-hint",
      view.tokenConfigured
        ? "Токен сохранён: " + (view.maskedToken || "скрыт") + ". Оставьте поле пустым, если не меняете его."
        : "Токен пока не сохранён.");
  }

  function formatOperatorTime(value) {
    if (!value) return "";
    try {
      const d = new Date(value);
      if (Number.isNaN(d.getTime())) return String(value);
      return d.toLocaleString("ru-RU", {
        timeZone: "Europe/Moscow",
        day: "numeric",
        month: "short",
        hour: "2-digit",
        minute: "2-digit"
      }) + " МСК";
    } catch (_) {
      return String(value);
    }
  }

  async function loadMarketDataWidget() {
    try {
      const res = await fetch("/api/marketdata/status", { headers: { Accept: "application/json" } });
      if (!res.ok) return;
      const md = await res.json();
      const tapeLabel = md.streaming
        ? ("live " + (md.liveTapeSize || 0) + " · day " + (md.archivedTapeLines || 0))
        : "нет стрима";
      setText("widget-broker-tape", tapeLabel);
      setText("pillar-trend-tape", md.streaming
        ? ((md.instrument || "BR") + " · " + (md.archivedTapeLines || md.liveTapeSize || 0) + " prints")
        : "offline");
      if (md.summary) {
        setBackStats("widget-broker-back-stats", [
          { k: "Исполнение", v: ($("widget-broker-mode") && $("widget-broker-mode").textContent) || "—" },
          { k: "Marketdata", v: md.streaming ? "STREAM" : "idle" },
          { k: "Инструмент", v: md.instrument || "—" },
          { k: "Tape (live)", v: String(md.liveTapeSize != null ? md.liveTapeSize : "—") },
          { k: "Tape (архив дня)", v: String(md.archivedTapeLines != null ? md.archivedTapeLines : "—") },
          { k: "DOM snaps", v: String(md.archivedDomSnapshots != null ? md.archivedDomSnapshots : "—") },
          { k: "Depth", v: String(md.orderbookDepth != null ? md.orderbookDepth : "—") },
          { k: "Сводка", v: md.summary || "—" }
        ]);
      }
      if ($("pillar-trend-back-stats") && md.summary) {
        setBackStats("pillar-trend-back-stats", [
          { k: "Playbook", v: "levels-profile-br-m5" },
          { k: "Стрим", v: md.streaming ? "live" : "off" },
          { k: "Tape сегодня", v: String(md.archivedTapeLines || 0) },
          { k: "DOM snaps", v: String(md.archivedDomSnapshots || 0) },
          { k: "Depth", v: String(md.orderbookDepth || 50) }
        ]);
      }
    } catch (_) {
      setText("widget-broker-tape", "n/a");
      setText("pillar-trend-tape", "n/a");
    }
  }

  async function loadTrendDeliverySettings() {
    const toggle = $("trend-auto-execution");
    if (!toggle) return;
    try {
      const res = await fetch("/api/trend/settings", { headers: { Accept: "application/json" } });
      if (!res.ok) throw new Error("HTTP " + res.status);
      applyTrendDeliveryView(await res.json());
    } catch (err) {
      const status = $("trend-delivery-status");
      if (status) status.textContent = "Не удалось загрузить режим trend: " + (err.message || err);
    }
  }

  function applyTrendDeliveryView(view) {
    const toggle = $("trend-auto-execution");
    if (!toggle || !view) return;
    const auto = !!view.autoExecution;
    toggle.checked = auto;
    toggle.setAttribute("aria-checked", auto ? "true" : "false");
    const wrap = toggle.closest(".mode-switch");
    if (wrap) {
      wrap.classList.toggle("is-auto", auto);
      wrap.classList.toggle("is-signal", !auto);
    }
    const title = $("trend-delivery-title");
    const hint = $("trend-delivery-hint");
    const status = $("trend-delivery-status");
    if (title) title.textContent = auto ? "Автоторговля" : "Только сигнал";
    if (hint) {
      hint.textContent = auto
        ? "Планы уходят в sandbox journal (submit). Live FORTS — только с live-execution."
        : "Тикер + BUY/SELL без заявок. Включите автоторговлю для journal/ордеров.";
    }
    if (status) {
      status.textContent = "Режим: " + (view.delivery || (auto ? "AUTO" : "SIGNAL_ONLY"))
        + (view.updatedAt
          ? " · переключено " + formatOperatorTime(view.updatedAt) + " (не дата торгов)"
          : "");
    }
  }

  async function setTrendAutoExecution(enabled) {
    const toggle = $("trend-auto-execution");
    if (!toggle) return;
    toggle.disabled = true;
    try {
      const res = await fetch("/api/trend/settings/auto-execution", {
        method: "POST",
        headers: withAuthHeaders({ "Content-Type": "application/json", Accept: "application/json" }),
        body: JSON.stringify({ enabled: !!enabled })
      });
      if (!res.ok) {
        const errBody = await res.json().catch(function () { return {}; });
        throw new Error(errBody.message || errBody.error || ("HTTP " + res.status));
      }
      applyTrendDeliveryView(await res.json());
      appendLog(enabled ? "Trend: автоторговля включена." : "Trend: только сигнал.", "ok");
    } catch (err) {
      appendLog("Не удалось переключить trend: " + (err.message || err), "err");
      await loadTrendDeliverySettings();
    } finally {
      toggle.disabled = false;
    }
  }

  async function loadBrokerSettings() {
    if (!$("broker-save-settings")) return;
    try {
      const res = await fetch("/api/broker/settings", { headers: { Accept: "application/json" } });
      if (!res.ok) return;
      fillBrokerSettings(await res.json());
    } catch (_) {
      setText("broker-token-hint", "Настройки брокера временно недоступны.");
    }
  }

  async function saveBrokerSettings() {
    if (!$("broker-save-settings")) return false;
    await prepareAuth();
    setBusy(true);
    appendLog("Сохраняю настройки брокера…", "info");
    try {
      const res = await fetch("/api/broker/settings", {
        method: "POST",
        headers: withAuthHeaders({ "Content-Type": "application/json" }),
        body: JSON.stringify(brokerSettingsPayload())
      });
      const text = await res.text();
      const body = text ? JSON.parse(text) : null;
      if (!res.ok) {
        appendLog("Не удалось сохранить настройки брокера.", "err");
        return false;
      }
      fillBrokerSettings(body);
      await loadBrokerWidget();
      appendLog("Настройки брокера сохранены.", "ok");
      return true;
    } catch (e) {
      appendLog(String(e && e.message ? e.message : e), "err");
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function ensureSandboxAccount() {
    if (!$("broker-sandbox-account")) return;
    await prepareAuth();
    setBusy(true);
    appendLog("Подтягиваю счёт песочницы…", "info");
    try {
      // Один запрос: сначала сохраняем форму (включая токен), затем создаём/берём accountId.
      const res = await fetch("/api/broker/sandbox-account", {
        method: "POST",
        headers: withAuthHeaders({ "Content-Type": "application/json" }),
        body: JSON.stringify(brokerSettingsPayload())
      });
      const text = await res.text();
      let body = null;
      try {
        body = text ? JSON.parse(text) : null;
      } catch (_) {
        body = null;
      }
      if (!res.ok || !body) {
        if (res.status === 401) {
          appendLog("Нет доступа — сохраните логин и пароль оператора сверху, затем повторите.", "err");
          setText("broker-sandbox-account-line", "Нет доступа (401): сохраните логин/пароль оператора и нажмите снова.");
          return;
        }
        const detail = (body && body.summary) || text || ("HTTP " + res.status);
        appendLog("Не удалось получить счёт песочницы: " + detail, "err");
        setText("broker-sandbox-account-line", "Не удалось получить счёт песочницы: " + detail);
        return;
      }
      if (body.accountId && $("broker-account-id")) {
        $("broker-account-id").value = body.accountId;
      }
      setText("broker-sandbox-account-line", body.summary || "Счёт песочницы обновлён.");
      appendLog(body.summary || "Счёт песочницы обновлён.", body.ok ? "ok" : "err");
      await loadBrokerSettings();
      await loadBrokerWidget();
    } catch (e) {
      appendLog(String(e && e.message ? e.message : e), "err");
      setText("broker-sandbox-account-line", String(e && e.message ? e.message : e));
    } finally {
      setBusy(false);
    }
  }

  async function sandboxPayIn() {
    if (!$("broker-sandbox-payin")) return;
    await prepareAuth();
    setBusy(true);
    const amount = $("broker-sandbox-payin-amount")
      ? Number($("broker-sandbox-payin-amount").value || 200000)
      : 200000;
    appendLog("Пополняю песочницу на " + amount + " ₽…", "info");
    try {
      const res = await fetch("/api/broker/sandbox-pay-in?amountRub=" + encodeURIComponent(String(amount)), {
        method: "POST",
        headers: withAuthHeaders()
      });
      const text = await res.text();
      let body = null;
      try {
        body = text ? JSON.parse(text) : null;
      } catch (_) {
        body = null;
      }
      if (!res.ok || !body) {
        if (res.status === 401) {
          appendLog("Нет доступа — сохраните логин и пароль оператора сверху.", "err");
          return;
        }
        const detail = (body && body.summary) || text || ("HTTP " + res.status);
        appendLog("Не удалось пополнить песочницу: " + detail, "err");
        setText("broker-sandbox-account-line", "Пополнение не удалось: " + detail);
        return;
      }
      setText("broker-sandbox-account-line", body.summary || "Песочница пополнена.");
      appendLog(body.summary || "Песочница пополнена.", body.ok ? "ok" : "err");
      await loadBrokerWidget();
    } catch (e) {
      appendLog(String(e && e.message ? e.message : e), "err");
    } finally {
      setBusy(false);
    }
  }

  function bindAlertPrefs() {
    const en = $("ops-alerts-enabled");
    const snd = $("ops-alerts-sound");
    const perm = $("ops-notify-permission");

    if (en) {
      en.checked = localStorage.getItem(ALERTS_ENABLED_KEY) !== "off";
      en.addEventListener("change", function () {
        localStorage.setItem(ALERTS_ENABLED_KEY, en.checked ? "on" : "off");
      });
    }
    if (snd) {
      snd.checked = localStorage.getItem(ALERTS_SOUND_KEY) !== "off";
      snd.addEventListener("change", function () {
        localStorage.setItem(ALERTS_SOUND_KEY, snd.checked ? "on" : "off");
      });
    }
    if (perm) {
      perm.addEventListener("click", function () {
        if (!("Notification" in window)) {
          appendLog("Браузер не поддерживает системные уведомления.", "err");
          return;
        }
        Notification.requestPermission().then(function (p) {
          appendLog("Уведомления ОС: " + p, p === "granted" ? "ok" : "info");
        });
      });
    }
  }

  function startAlertPolling() {
    // ops-panel = полный пульт (settings); dash-cta = дискретная кнопка на дашборде
    if (!$("ops-panel") && !$("dash-cta")) return;
    bindAlertPrefs();
    seedSeenFromJournal().then(function () {
      pollPaperAlerts();
      pollAutoRunStatus();
      if ($("broker-save-settings")) {
        loadBrokerSettings();
      }
      if ($("broker-save-settings") || $("widget-broker") || $("dash-broker-status")) {
        loadBrokerWidget();
      }
      loadDashboardConsolidatedSummary().then(function () {
        requestAnimationFrame(function () {
          requestAnimationFrame(igniteAllDonuts);
        });
      });
      // Double-rAF fallback if summary skipped
      requestAnimationFrame(function () {
        requestAnimationFrame(function () {
          if (!$("widget-paper")) return;
          /* capital/strategies already in HTML — ignite if summary did not */
          setTimeout(function () {
            document.querySelectorAll(".widget-card .donut[id]").forEach(function (el) {
              if (el.dataset.ignited === "1") return;
              igniteAllDonuts();
            });
          }, 400);
        });
      });
    });
    setInterval(pollPaperAlerts, POLL_MS);
    setInterval(pollAutoRunStatus, POLL_MS * 5);
    if ($("broker-save-settings") || $("widget-broker") || $("dash-broker-status")) {
      setInterval(loadBrokerWidget, POLL_MS * 2);
    }
    setInterval(loadDashboardConsolidatedSummary, POLL_MS * 2);
  }

  async function apiPost(path, startMessage, okMessage) {
    await prepareAuth();
    setBusy(true);
    appendLog(startMessage || "Выполняю запрос…", "info");
    try {
      const res = await fetch(path, {
        method: "POST",
        headers: withAuthHeaders()
      });
      const text = await res.text();
      let body;
      try { body = JSON.parse(text); } catch (_) { body = text; }
      if (!res.ok) {
        if (res.status === 401 || res.status === 403) {
          const detail =
            body && typeof body === "object" && body.message
              ? String(body.message)
              : "";
          appendLog(
            "Нет доступа — нажмите «Войти» (email/пароль кабинета)"
              + (detail ? ": " + detail : "."),
            "err"
          );
          try {
            localStorage.removeItem(SB_TOKEN_KEY);
          } catch (_) { /* ignore */ }
        } else {
          appendLog(
            "Не удалось выполнить действие (" + res.status + ").",
            "err"
          );
        }
        return;
      }
      appendLog(okMessage || "Готово.", "ok");
      if (body && typeof body === "object") {
        if (body.tickersAnalyzed != null) {
          appendLog(
            "Тикеры " + body.tickersAnalyzed +
            ", пар " + body.pairsTested +
            ", коинт. " + body.cointegratedPairs +
            ", сигналов " + (body.actionableSignalsCount != null ? body.actionableSignalsCount : "—"),
            "ok"
          );
        }
        if (body.tickersLoaded != null) {
          appendLog("Загружено тикеров: " + body.tickersLoaded, "ok");
        }
        if (Array.isArray(body)) {
          appendLog("Получено записей: " + body.length, "ok");
        }
      }
      await pollPaperAlerts();
      appendLog("Обновляю раздел…", "info");
      setTimeout(function () { location.reload(); }, 900);
    } catch (e) {
      appendLog(String(e && e.message ? e.message : e), "err");
    } finally {
      setBusy(false);
    }
  }

  function hasTrendAccess() {
    return document.body.getAttribute("data-has-trend") === "1";
  }
  function hasArbAccess() {
    return document.body.getAttribute("data-has-arb") === "1";
  }

  function showStrategyLockModal(strategy) {
    const host = $("strategy-lock-host");
    if (!host) return;
    const key = (strategy || "TREND").toUpperCase();
    fetch("/api/ops/strategy-lock?strategy=" + encodeURIComponent(key), {
      headers: { Accept: "application/json" }
    }).then(function (res) {
      return res.ok ? res.json() : null;
    }).then(function (data) {
      if (!data) return;
      host.innerHTML =
        '<div class="strategy-lock-modal" role="dialog" aria-modal="true">' +
        '<button type="button" class="upsell-close" aria-label="Закрыть">&times;</button>' +
        "<strong>" + escapeHtml(data.title || "Заблокировано") + "</strong>" +
        "<p>" + escapeHtml(data.body || "") + "</p>" +
        '<div class="upsell-actions">' +
        '<a class="btn btn-primary" href="' + escapeHtml(data.ctaHref || "/view/full-core") + '">' +
        escapeHtml(data.ctaLabel || "Подробнее") + "</a>" +
        '<a class="btn btn-ghost" href="/view/settings#product-edition-settings">Версия (демо)</a>' +
        '<button type="button" class="upsell-dismiss">Закрыть</button>' +
        "</div></div>" +
        '<div class="strategy-lock-backdrop"></div>';
      const close = function () { host.innerHTML = ""; };
      host.querySelector(".upsell-close").addEventListener("click", close);
      host.querySelector(".upsell-dismiss").addEventListener("click", close);
      const bd = host.querySelector(".strategy-lock-backdrop");
      if (bd) bd.addEventListener("click", close);
    }).catch(function () {});
  }

  function resolveActiveStrategy() {
    const page = (document.body.getAttribute("data-nav-strategy") || "").trim();
    if (page === "pairs" || page === "trend" || page === "arb") return page;
    try {
      const stored = localStorage.getItem(STRATEGY_KEY);
      if (stored === "pairs" || stored === "trend" || stored === "arb") return stored;
    } catch (_) {}
    return "pairs";
  }

  function applyStrategyNav(strategy) {
    let s = strategy || "pairs";
    if (s === "trend" && !hasTrendAccess()) {
      showStrategyLockModal("TREND");
      s = "pairs";
    }
    if (s === "arb" && !hasArbAccess()) {
      showStrategyLockModal("ARB");
      s = resolveActiveStrategy() === "arb" ? "pairs" : resolveActiveStrategy();
      if (s === "arb") s = "pairs";
    }
    try { localStorage.setItem(STRATEGY_KEY, s); } catch (_) {}
    document.querySelectorAll(".strategy-switch-btn").forEach(function (btn) {
      const on = btn.getAttribute("data-strategy") === s;
      btn.classList.toggle("active", on);
      btn.setAttribute("aria-selected", on ? "true" : "false");
    });
    document.querySelectorAll(".topnav-secondary").forEach(function (nav) {
      const show = nav.getAttribute("data-for") === s;
      if (show) nav.removeAttribute("hidden");
      else nav.setAttribute("hidden", "");
    });
  }

  function bindStrategyNav() {
    document.querySelectorAll(".strategy-switch-btn").forEach(function (btn) {
      btn.addEventListener("click", function () {
        const s = btn.getAttribute("data-strategy") || "pairs";
        if (btn.getAttribute("data-locked") === "true") {
          showStrategyLockModal(s === "arb" ? "ARB" : "TREND");
          return;
        }
        applyStrategyNav(s);
        if (s === "trend") {
          location.href = "/view/trend-signal";
        } else if (s === "arb") {
          location.href = "/view/full-core?feature=calendar-arb";
        } else if (s === "pairs" && location.pathname.indexOf("/view/trend") === 0) {
          location.href = "/view/final";
        }
      });
    });
    document.addEventListener("click", function (ev) {
      const openBtn = ev.target && ev.target.closest
        ? ev.target.closest("[data-strategy-lock-open]")
        : null;
      if (openBtn) {
        ev.preventDefault();
        showStrategyLockModal(openBtn.getAttribute("data-strategy-lock-open") || "TREND");
        return;
      }
      const req = ev.target && ev.target.closest
        ? ev.target.closest("a[data-requires]")
        : null;
      if (!req) return;
      const need = req.getAttribute("data-requires");
      if (need === "trend" && !hasTrendAccess()) {
        ev.preventDefault();
        showStrategyLockModal("TREND");
      } else if (need === "arb" && !hasArbAccess()) {
        ev.preventDefault();
        showStrategyLockModal("ARB");
      }
    });
    applyStrategyNav(resolveActiveStrategy());
    const lockPage = document.querySelector(".strategy-lock-page[data-strategy-lock]");
    if (lockPage) {
      showStrategyLockModal(lockPage.getAttribute("data-strategy-lock"));
    }
  }

  function bindProductEditionSettings() {
    const save = $("product-edition-save");
    const reset = $("product-edition-reset");
    const select = $("product-edition-select");
    const status = $("product-edition-status");
    if (!save || !select) return;
    async function apply(body) {
      try {
        const res = await fetch("/api/ops/product-edition", {
          method: "POST",
          headers: withAuthHeaders({ "Content-Type": "application/json", Accept: "application/json" }),
          body: JSON.stringify(body)
        });
        if (!res.ok) {
          appendLog("Не удалось сменить версию: HTTP " + res.status, "err");
          return;
        }
        const data = await res.json();
        if (status) status.textContent = "текущая: " + (data.label || data.edition);
        appendLog("Версия продукта: " + (data.edition || "?") + " — перезагрузка…", "ok");
        setTimeout(function () { location.reload(); }, 400);
      } catch (ex) {
        appendLog("Ошибка смены версии: " + ex, "err");
      }
    }
    save.addEventListener("click", function () {
      apply({ edition: select.value });
    });
    if (reset) {
      reset.addEventListener("click", function () {
        apply({ clear: true });
      });
    }
  }

  function bind() {
    loadCreds();
    const map = {
      "run-fast": function () {
        beaconUpsell("run-fast", currentPagePath());
        return apiPost(
          "/api/analysis/run?refresh=false",
          ACTION_START["run-fast"],
          "Анализ завершён. Paper обновлён."
        );
      },
      "run-full": function () {
        if (!confirm("Полный refresh скачает свечи с MOEX — может занять много минут. Продолжить?")) return;
        beaconUpsell("run-full", currentPagePath());
        return apiPost(
          "/api/analysis/run?refresh=true",
          ACTION_START["run-full"],
          "Полный анализ завершён. Paper обновлён."
        );
      },
      "news-refresh": function () {
        beaconUpsell("news-refresh", currentPagePath());
        return apiPost(
          "/api/analysis/news-refresh",
          ACTION_START["news-refresh"],
          "Новости и paper обновлены."
        );
      },
      "data-refresh": function () {
        if (!confirm("Скачать свечи IMOEX с биржи?")) return;
        beaconUpsell("data-refresh", currentPagePath());
        return apiPost(
          "/api/data/refresh",
          ACTION_START["data-refresh"],
          "Свечи обновлены."
        );
      },
      "walk-forward": function () {
        beaconUpsell("walk-forward", currentPagePath());
        return apiPost(
          "/api/analysis/walk-forward?maxPairs=10",
          ACTION_START["walk-forward"],
          "Walk-forward пересчитан."
        );
      },
      "broker-test": function () {
        return testBrokerConnection();
      },
      "broker-reconcile": async function () {
        await prepareAuth();
        appendLog("Запрашиваю сверку с брокером…", "info");
        await loadBrokerWidget();
        appendLog("Сверка с брокером обновлена.", "ok");
      },
      "broker-flatten": function () {
        if (!confirm("Аварийно снять все активные ордера и позиции у брокера?")) return;
        return apiPost(
          "/api/broker/flatten-all",
          "Запускаю закрытие всех позиций брокера…",
          "Команда на закрытие позиций отправлена."
        );
      }
    };

    document.querySelectorAll("[data-ops-action]").forEach(function (btn) {
      btn.addEventListener("click", function () {
        const key = btn.getAttribute("data-ops-action");
        const fn = map[key];
        if (fn) fn();
      });
    });

    if ($("ops-save-creds")) {
      $("ops-save-creds").addEventListener("click", async function () {
        saveCreds();
        const sb = authMode.supabase || {};
        if (sb.enabled) {
          try {
            await ensureSupabaseSession(true);
            updateSessionBar();
          } catch (e) {
            appendLog("Вход не удался: " + (e && e.message ? e.message : e), "err");
          }
          return;
        }
        if (authHeader()) {
          appendLog("Basic-логин сохранён в этом браузере.", "ok");
        } else {
          appendLog("Укажите operator user/password для Basic.", "err");
        }
      });
    }

    if ($("trinity-auth-form")) {
      $("trinity-auth-form").addEventListener("submit", submitAuthGate);
    }

    if ($("broker-save-settings")) {
      $("broker-save-settings").addEventListener("click", saveBrokerSettings);
    }
    if ($("trend-auto-execution")) {
      loadTrendDeliverySettings();
      $("trend-auto-execution").addEventListener("change", function () {
        setTrendAutoExecution($("trend-auto-execution").checked);
      });
    }
    if ($("broker-sandbox-account")) {
      $("broker-sandbox-account").addEventListener("click", ensureSandboxAccount);
    }
    if ($("broker-sandbox-payin")) {
      $("broker-sandbox-payin").addEventListener("click", sandboxPayIn);
    }

    appendLog("Операторская панель готова.", "info");
    appendLog("Раздел: " + currentSectionTitle() + ".", "info");
    bindFullCoreTeasers();
    bindWidgetFlips();
    bindStrategyNav();
    bindProductEditionSettings();
    startAlertPolling();
    loadAuthMode().then(function () {
      updateSessionBar();
      maybeShowAuthGate();
      if (authMode.supabase && authMode.supabase.enabled && localStorage.getItem(SB_TOKEN_KEY)) {
        appendLog("Найдена Supabase-сессия — Bearer для API.", "ok");
      }
      beaconUpsell("page_view", currentPagePath());
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", bind);
  } else {
    bind();
  }
})();
