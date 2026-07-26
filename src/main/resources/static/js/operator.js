(function () {
  const LOG_KEY = "imoex.ops.log";
  const USER_KEY = "imoex.ops.user";
  const PASS_KEY = "imoex.ops.pass";

  function $(id) {
    return document.getElementById(id);
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

  async function apiPost(path, okMessage) {
    saveCreds();
    setBusy(true);
    appendLog("POST " + path + " …", "info");
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
        appendLog("Ошибка " + res.status + ": " + (typeof body === "string" ? body : JSON.stringify(body)), "err");
        return;
      }
      appendLog(okMessage || ("OK " + res.status), "ok");
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
          appendLog("Ответ: " + body.length + " записей", "ok");
        }
      }
      appendLog("Обновляю страницу…", "info");
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
        return apiPost("/api/analysis/run?refresh=false", "Анализ завершён (без скачивания свечей). Paper sync выполнен.");
      },
      "run-full": function () {
        if (!confirm("Полный refresh скачает свечи с MOEX — может занять много минут. Продолжить?")) return;
        return apiPost("/api/analysis/run?refresh=true", "Полный анализ с refresh завершён.");
      },
      "news-refresh": function () {
        return apiPost("/api/analysis/news-refresh", "Новости и paper sync обновлены.");
      },
      "data-refresh": function () {
        if (!confirm("Скачать свечи IMOEX с биржи?")) return;
        return apiPost("/api/data/refresh", "Свечи обновлены.");
      },
      "walk-forward": function () {
        return apiPost("/api/analysis/walk-forward?maxPairs=10", "Walk-forward пересчитан.");
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
        appendLog("Логин сохранён в этом браузере (localStorage).", "ok");
      });
    }

    appendLog("Операторская панель готова. POST требует Basic Auth (пароль из application-local.yml).", "info");
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", bind);
  } else {
    bind();
  }
})();
