(function () {
  const USER_KEY = "imoex.ops.user";
  const PASS_KEY = "imoex.ops.pass";
  const ALERTS_ENABLED_KEY = "imoex.alerts.enabled";
  const ALERTS_SOUND_KEY = "imoex.alerts.sound";
  const SEEN_IDS_KEY = "imoex.alerts.seenIds";
  const POLL_MS = 60000;

  const SECTION_TITLES = {
    "/view": "Дашборд",
    "/view/": "Дашборд",
    "/view/recommendations": "Рекомендации",
    "/view/signals": "Сигналы",
    "/view/final": "Итог + новости",
    "/view/paper": "Paper journal",
    "/view/walk-forward": "Walk-forward",
    "/view/strategy": "Описание стратегии",
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
    const user = ($("ops-user") && $("ops-user").value) || localStorage.getItem(USER_KEY) || "imoex";
    const pass = ($("ops-pass") && $("ops-pass").value) || localStorage.getItem(PASS_KEY) || "";
    return "Basic " + btoa(unescape(encodeURIComponent(user + ":" + pass)));
  }

  function saveCreds() {
    if ($("ops-user")) localStorage.setItem(USER_KEY, $("ops-user").value || "imoex");
    if ($("ops-pass")) localStorage.setItem(PASS_KEY, $("ops-pass").value || "");
  }

  function loadCreds() {
    if ($("ops-user")) $("ops-user").value = localStorage.getItem(USER_KEY) || "imoex";
    if ($("ops-pass")) $("ops-pass").value = localStorage.getItem(PASS_KEY) || "";
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
    if (!$("broker-widget")) return;
    try {
      const [statusRes, reconcileRes, reportsRes] = await Promise.all([
        fetch("/api/broker/status", { headers: { Accept: "application/json" } }),
        fetch("/api/broker/reconcile", { headers: { Accept: "application/json" } }),
        fetch("/api/broker/reports", { headers: { Accept: "application/json" } })
      ]);
      if (statusRes.ok) {
        const status = await statusRes.json();
        setText("broker-status-line",
          "Статус: " + (status.summary || status.provider || "broker") +
          " · mode=" + (status.mode || "?") +
          " · sandbox=" + (!!status.sandbox));
      }
      if (reconcileRes.ok) {
        const rec = await reconcileRes.json();
        setText("broker-reconcile-line",
          "Reconcile: " + (rec.summary || "—"));
      }
      if (reportsRes.ok) {
        const reports = await reportsRes.json();
        const last = Array.isArray(reports) && reports.length ? reports[0] : null;
        setText("broker-journal-line",
          last
            ? "Последний broker report: " + last.status + " · " + last.summary
            : "Broker journal пока пуст.");
      }
    } catch (_) {
      setText("broker-status-line", "Статус брокера временно недоступен.");
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
        ? "Token сохранён: " + (view.maskedToken || "скрыт") + ". Оставьте поле пустым, если не меняете его."
        : "Token пока не сохранён.");
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
    if (!$("broker-save-settings")) return;
    saveCreds();
    setBusy(true);
    appendLog("Сохраняю broker settings…", "info");
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
        appendLog("Не удалось сохранить broker settings.", "err");
        return;
      }
      fillBrokerSettings(body);
      await loadBrokerWidget();
      appendLog("Broker settings сохранены.", "ok");
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
    if (!$("ops-panel")) return;
    bindAlertPrefs();
    seedSeenFromJournal().then(function () {
      pollPaperAlerts();
      pollAutoRunStatus();
      loadBrokerWidget();
      loadBrokerSettings();
    });
    setInterval(pollPaperAlerts, POLL_MS);
    setInterval(pollAutoRunStatus, POLL_MS * 5);
    setInterval(loadBrokerWidget, POLL_MS * 2);
  }

  async function apiPost(path, startMessage, okMessage) {
    saveCreds();
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
          appendLog("Нет доступа — проверьте логин и пароль оператора.", "err");
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
    const map = {
      "run-fast": function () {
        return apiPost(
          "/api/analysis/run?refresh=false",
          ACTION_START["run-fast"],
          "Анализ завершён. Paper обновлён."
        );
      },
      "run-full": function () {
        if (!confirm("Полный refresh скачает свечи с MOEX — может занять много минут. Продолжить?")) return;
        return apiPost(
          "/api/analysis/run?refresh=true",
          ACTION_START["run-full"],
          "Полный анализ завершён. Paper обновлён."
        );
      },
      "news-refresh": function () {
        return apiPost(
          "/api/analysis/news-refresh",
          ACTION_START["news-refresh"],
          "Новости и paper обновлены."
        );
      },
      "data-refresh": function () {
        if (!confirm("Скачать свечи IMOEX с биржи?")) return;
        return apiPost(
          "/api/data/refresh",
          ACTION_START["data-refresh"],
          "Свечи обновлены."
        );
      },
      "walk-forward": function () {
        return apiPost(
          "/api/analysis/walk-forward?maxPairs=10",
          ACTION_START["walk-forward"],
          "Walk-forward пересчитан."
        );
      },
      "broker-reconcile": async function () {
        saveCreds();
        appendLog("Запрашиваю broker reconcile…", "info");
        await loadBrokerWidget();
        appendLog("Broker reconcile обновлён.", "ok");
      },
      "broker-flatten": function () {
        if (!confirm("Аварийно снять все активные ордера и позиции у брокера?")) return;
        return apiPost(
          "/api/broker/flatten-all",
          "Запускаю broker flatten-all…",
          "Broker flatten-all отправлен."
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
      $("ops-save-creds").addEventListener("click", function () {
        saveCreds();
        appendLog("Логин сохранён в этом браузере.", "ok");
      });
    }

    if ($("broker-save-settings")) {
      $("broker-save-settings").addEventListener("click", saveBrokerSettings);
    }

    appendLog("Операторская панель готова.", "info");
    appendLog("Раздел: " + currentSectionTitle() + ".", "info");
    startAlertPolling();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", bind);
  } else {
    bind();
  }
})();
