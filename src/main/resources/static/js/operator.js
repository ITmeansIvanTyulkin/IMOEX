(function () {
  const USER_KEY = "imoex.ops.user";
  const PASS_KEY = "imoex.ops.pass";
  const SB_TOKEN_KEY = "trinity.supabase.access_token";
  const SB_EMAIL_KEY = "trinity.supabase.user_email";
  const ALERTS_ENABLED_KEY = "imoex.alerts.enabled";
  const ALERTS_SOUND_KEY = "imoex.alerts.sound";
  const SEEN_IDS_KEY = "imoex.alerts.seenIds";
  const UPSELL_SHOWN_KEY = "imoex.upsell.shownId";
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
    "/view/paper": "Paper journal",
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

  function authHeader() {
    const token = localStorage.getItem(SB_TOKEN_KEY);
    if (token) {
      return "Bearer " + token;
    }
    const user = ($("ops-user") && $("ops-user").value) || localStorage.getItem(USER_KEY) || "imoex";
    const pass = ($("ops-pass") && $("ops-pass").value) || localStorage.getItem(PASS_KEY) || "";
    return "Basic " + btoa(unescape(encodeURIComponent(user + ":" + pass)));
  }

  function saveCreds() {
    if ($("ops-user")) localStorage.setItem(USER_KEY, $("ops-user").value || "imoex");
    if ($("ops-pass")) localStorage.setItem(PASS_KEY, $("ops-pass").value || "");
  }

  function loadCreds() {
    const user =
      localStorage.getItem(SB_EMAIL_KEY) ||
      localStorage.getItem(USER_KEY) ||
      "imoex";
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
    const sb = authMode.supabase || {};
    const userLabel = document.querySelector('label[for="ops-user"]');
    const passLabel = document.querySelector('label[for="ops-pass"]');
    if (sb.enabled) {
      if (userLabel) userLabel.textContent = "Email (Supabase / кабинет)";
      if (passLabel) passLabel.textContent = "Пароль";
      if ($("ops-user")) {
        $("ops-user").placeholder = "you@example.com";
        $("ops-user").type = "email";
      }
      if ($("ops-save-creds")) {
        $("ops-save-creds").textContent = "Войти / сохранить";
      }
    }
  }

  async function ensureSupabaseSession() {
    const sb = authMode.supabase || {};
    if (!sb.enabled || !sb.url || !sb.anonKey) return;
    if (localStorage.getItem(SB_TOKEN_KEY)) return;

    const email = (($("ops-user") && $("ops-user").value) || "").trim();
    const password = ($("ops-pass") && $("ops-pass").value) || "";
    if (!email || !password) return;

    const base = String(sb.url).replace(/\/$/, "");
    const res = await fetch(base + "/auth/v1/token?grant_type=password", {
      method: "POST",
      headers: {
        apikey: sb.anonKey,
        Authorization: "Bearer " + sb.anonKey,
        "Content-Type": "application/json",
        Accept: "application/json"
      },
      body: JSON.stringify({ email: email, password: password })
    });
    const text = await res.text();
    let data = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch (_) {
      data = null;
    }
    if (!res.ok || !data || !data.access_token) {
      const msg =
        (data && (data.error_description || data.msg || data.error)) ||
        ("Supabase login HTTP " + res.status);
      throw new Error(msg);
    }
    localStorage.setItem(SB_TOKEN_KEY, data.access_token);
    localStorage.setItem(SB_EMAIL_KEY, email);
    appendLog("Supabase-сессия: вход выполнен (общий логин с кабинетом).", "ok");
  }

  async function prepareAuth() {
    saveCreds();
    try {
      await ensureSupabaseSession();
    } catch (e) {
      appendLog(
        "Supabase: " + (e && e.message ? e.message : e) + " — пробую Basic fallback.",
        "info"
      );
    }
  }

  function prefersReducedMotion() {
    return window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  }

  /** Ignition-style gauge: sweep --p from empty to target on load/update. */
  function setDonut(id, pct, colorVar) {
    const el = $(id);
    if (!el) return;
    const p = Math.max(0, Math.min(100, Number(pct) || 0));
    const prev = el.dataset.p != null ? Number(el.dataset.p) : NaN;
    if (colorVar) el.style.setProperty("--c", colorVar);

    const same = Number.isFinite(prev) && Math.abs(prev - p) < 0.5;
    if (same) {
      el.style.setProperty("--p", String(p));
      return;
    }

    el.dataset.p = String(p);

    if (prefersReducedMotion()) {
      el.style.setProperty("--p", String(p));
      return;
    }

    const started = el.dataset.ignited === "1";
    if (!started || (Number.isFinite(prev) && Math.abs(prev - p) >= 0.5)) {
      el.style.transition = "none";
      el.style.setProperty("--p", "0");
      void el.offsetWidth;
      el.style.transition = "";
      el.dataset.ignited = "1";
    }
    requestAnimationFrame(function () {
      el.style.setProperty("--p", String(p));
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
    if (!box) return;
    const line = document.createElement("div");
    line.className = cls || "info";
    const ts = new Date().toLocaleTimeString();
    line.textContent = "[" + ts + "] " + msg;
    box.prepend(line);
    const lines = box.querySelectorAll("div");
    if (lines.length > 40) lines[lines.length - 1].remove();
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

  function playAlertSound() {
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
        g.gain.value = 0.12;
        o.connect(g);
        g.connect(ctx.destination);
        o.start(start);
        o.stop(start + dur);
      }
      beep(880, ctx.currentTime, 0.18);
      beep(1100, ctx.currentTime + 0.22, 0.22);
    } catch (_) {
      // ignore
    }
  }

  function showNativeNotification(alert) {
    if (!("Notification" in window) || Notification.permission !== "granted") {
      return;
    }
    try {
      const body = alert.summary || (alert.tickerY + "/" + alert.tickerX);
      new Notification("TRINITY — новая paper-сделка", {
        body: body,
        tag: alert.id,
        requireInteraction: false
      });
    } catch (_) {
      // ignore
    }
  }

  function showToast(alert) {
    const stack = $("trinity-toast-stack");
    if (!stack) return;

    const el = document.createElement("div");
    el.className = "toast";
    el.setAttribute("role", "alert");

    const y = alert.tickerY || "?";
    const x = alert.tickerX || "?";
    const book = alert.book || "DAILY";
    const sig = alert.signal || "?";
    const z = typeof alert.entryZ === "number" ? alert.entryZ.toFixed(2) : "?";

    el.innerHTML =
      '<button type="button" class="toast-close" aria-label="Закрыть">&times;</button>' +
      "<strong>Новая paper-сделка · " + book + "</strong>" +
      "<div>" + sig + " " + y + " / " + x + " · Z=" + z + "</div>" +
      '<div class="toast-meta"><a href="/view/paper">Paper journal</a> · ' +
      '<a href="/view/charts/' + encodeURIComponent(y) + "/" + encodeURIComponent(x) + '">График</a></div>';

    el.querySelector(".toast-close").addEventListener("click", function () {
      el.remove();
    });

    stack.prepend(el);
    setTimeout(function () {
      if (el.parentNode) el.remove();
    }, 20000);
  }

  function handleNewAlert(alert) {
    if (!alertsEnabled()) return;
    playAlertSound();
    showToast(alert);
    showNativeNotification(alert);
    appendLog("Новая paper: " + (alert.summary || alert.tickerY + "/" + alert.tickerX), "ok");
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
        headers: {
          Authorization: authHeader(),
          Accept: "application/json",
          "Content-Type": "application/json"
        },
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
        headers: {
          Authorization: authHeader(),
          Accept: "application/json",
          "Content-Type": "application/json"
        },
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

  async function pollPaperAlerts() {
    if (!alertsEnabled()) return;
    try {
      const res = await fetch("/api/ops/paper-alerts", { headers: { Accept: "application/json" } });
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

  async function pollAutoRunStatus() {
    try {
      const res = await fetch("/api/ops/auto-run/status", { headers: { Accept: "application/json" } });
      if (!res.ok) return;
      const st = await res.json();
      if (st && st.lastIntradayRunAt && st.lastIntradayRunStatus) {
        const key = "imoex.lastIntraLog";
        const msg = st.lastIntradayRunAt + " " + st.lastIntradayRunStatus;
        if (localStorage.getItem(key) !== msg && st.lastIntradayRunStatus !== "RUNNING") {
          localStorage.setItem(key, msg);
          appendLog("INTRADAY cron: " + st.lastIntradayRunStatus + " @ " + st.lastIntradayRunAt, "info");
        }
      }
    } catch (_) {
      // ignore
    }
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
      }
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
  }

  function fmtMoneyRub(v) {
    if (v == null || typeof v !== "number" || !isFinite(v)) return "—";
    return (v >= 0 ? "+" : "") + v.toFixed(0) + " ₽";
  }

  async function loadDashboardConsolidatedSummary() {
    if (!$("dash-paper-open") && !$("widget-paper")) return;
    try {
      const [paperRes, finalRes] = await Promise.all([
        fetch("/api/paper/journal", { headers: { Accept: "application/json" } }),
        fetch("/api/analysis/final", { headers: { Accept: "application/json" } })
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
      }
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
        headers: {
          Authorization: authHeader(),
          Accept: "application/json"
        }
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
        headers: {
          Authorization: authHeader(),
          Accept: "application/json",
          "Content-Type": "application/json"
        },
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
        headers: {
          Authorization: authHeader(),
          Accept: "application/json",
          "Content-Type": "application/json"
        },
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
        headers: {
          Authorization: authHeader(),
          Accept: "application/json"
        }
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
      loadDashboardConsolidatedSummary();
      // Double-rAF: paint empty rings, then ignition sweep (incl. regime ADX).
      requestAnimationFrame(function () {
        requestAnimationFrame(igniteRegimeDonut);
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
        headers: {
          Authorization: authHeader(),
          Accept: "application/json"
        }
      });
      const text = await res.text();
      let body;
      try { body = JSON.parse(text); } catch (_) { body = text; }
      if (!res.ok) {
        if (res.status === 401 || res.status === 403) {
          appendLog(
            "Нет доступа — войдите email/паролем кабинета (Supabase) или Basic operator.",
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

  function bind() {
    loadCreds();
    loadAuthMode().then(function () {
      if (authMode.supabase && authMode.supabase.enabled && localStorage.getItem(SB_TOKEN_KEY)) {
        appendLog("Найдена Supabase-сессия кабинета — Bearer для API.", "ok");
      }
    });
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
        try {
          await ensureSupabaseSession();
          if (localStorage.getItem(SB_TOKEN_KEY)) {
            appendLog("Вход сохранён (Supabase token + localStorage).", "ok");
          } else {
            appendLog("Логин сохранён в этом браузере (Basic).", "ok");
          }
        } catch (e) {
          appendLog("Логин сохранён; Supabase: " + (e && e.message ? e.message : e), "info");
        }
      });
    }

    if ($("broker-save-settings")) {
      $("broker-save-settings").addEventListener("click", saveBrokerSettings);
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
    startAlertPolling();
    beaconUpsell("page_view", currentPagePath());
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", bind);
  } else {
    bind();
  }
})();
