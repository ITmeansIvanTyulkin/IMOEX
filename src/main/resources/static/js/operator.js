(function () {
  const USER_KEY = "imoex.ops.user";
  const PASS_KEY = "imoex.ops.pass";

  const SECTION_TITLES = {
    "/view": "Дашборд",
    "/view/": "Дашборд",
    "/view/recommendations": "Рекомендации",
    "/view/signals": "Сигналы",
    "/view/final": "Итог + новости",
    "/view/paper": "Paper journal",
    "/view/walk-forward": "Walk-forward",
    "/view/strategy": "Описание стратегии"
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

  function setBusy(on) {
    const bar = $("ops-busy");
    if (bar) bar.classList.toggle("on", !!on);
    document.querySelectorAll("[data-ops-action]").forEach(function (btn) {
      btn.disabled = !!on;
    });
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

    appendLog("Операторская панель готова.", "info");
    appendLog("Раздел: " + currentSectionTitle() + ".", "info");
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", bind);
  } else {
    bind();
  }
})();
