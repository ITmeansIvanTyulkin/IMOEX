/**
 * TrinityFastBoot — permanent fast-load contract for ALL strategy desks.
 *
 * Rules (do not regress):
 * 1. Paint local cache / SSR boot JSON immediately — never wait on the network for first pixels.
 * 2. Fire secondary fetches (DOM/book/status) in parallel; never block the chart on them.
 * 3. Abort hung primary fetches (default 25s desk / 12s book) — never leave «Загрузка…» forever.
 * 4. Same timeouts and AbortController pattern on Exclusive, positional, pairs, calendar-arb, charts.
 */
(function (global) {
  "use strict";

  var DESK_MS = 25000;
  var BOOK_MS = 12000;
  var STATUS_MS = 8000;

  function jwtExpired(token) {
    if (!token) return true;
    try {
      var parts = String(token).split(".");
      if (parts.length < 2) return true;
      var json = parts[1].replace(/-/g, "+").replace(/_/g, "/");
      json += "=".repeat((4 - (json.length % 4)) % 4);
      var payload = JSON.parse(atob(json));
      return !(payload.exp > (Date.now() / 1000) + 15);
    } catch (_) {
      return true;
    }
  }

  function apiUrlOf(input) {
    if (typeof input === "string") return input;
    if (input && typeof input.url === "string") return input.url;
    try { return String(input); } catch (_) { return ""; }
  }

  function apiNeedsAuth(url) {
    var s = apiUrlOf(url);
    var path = s;
    try {
      if (/^https?:/i.test(s)) path = new URL(s).pathname || s;
    } catch (_) {}
    if (path.indexOf("/api/") < 0) return false;
    if (/\/api\/auth\/(mode|login|logout)(\?|$)/.test(path)) return false;
    if (/\/api\/upsell\/events(\?|$)/.test(path)) return false;
    if (/\/api\/trend\/ws\//.test(path)) return false;
    return true;
  }

  function injectAuthHeaders(headers) {
    var h = {};
    if (headers && typeof headers.forEach === "function") {
      headers.forEach(function (v, k) { h[k] = v; });
    } else {
      h = Object.assign({}, headers || {});
    }
    if (h.Authorization || h.authorization) return h;
    try {
      var token = localStorage.getItem("trinity.supabase.access_token");
      if (token && !jwtExpired(token)) {
        h.Authorization = "Bearer " + token;
        return h;
      }
      var user = (localStorage.getItem("trinity.supabase.user_email")
        || localStorage.getItem("imoex.ops.user") || "").trim();
      var pass = localStorage.getItem("imoex.ops.pass") || "";
      if (user && pass && user.indexOf("@") < 0) {
        h.Authorization = "Basic " + btoa(unescape(encodeURIComponent(user + ":" + pass)));
      }
    } catch (_) {}
    return h;
  }

  if (!global.__trinityApiAuthFetch && typeof global.fetch === "function") {
    var rawFetch = global.fetch.bind(global);
    global.fetch = function (input, init) {
      if (!apiNeedsAuth(input)) {
        return rawFetch(input, init);
      }
      init = init ? Object.assign({}, init) : {};
      init.headers = injectAuthHeaders(init.headers);
      if (input && typeof input === "object" && typeof input.url === "string" && typeof Request === "function") {
        try {
          return rawFetch(new Request(input, init));
        } catch (_) {}
      }
      return rawFetch(input, init);
    };
    global.__trinityApiAuthFetch = true;
  }

  function fetchJson(url, opts) {
    opts = opts || {};
    var ms = opts.ms != null ? opts.ms : DESK_MS;
    var headers = opts.headers || { Accept: "application/json" };
    var ac = typeof AbortController !== "undefined" ? new AbortController() : null;
    var timer = ac ? setTimeout(function () {
      try { ac.abort(); } catch (_) {}
    }, ms) : null;
    var init = {
      headers: headers,
      credentials: opts.credentials || "same-origin",
      signal: ac ? ac.signal : undefined
    };
    if (opts.method) init.method = opts.method;
    if (opts.body != null) init.body = opts.body;
    return fetch(url, init).then(function (res) {
      if (!res.ok) {
        var err = new Error("HTTP " + res.status);
        err.status = res.status;
        throw err;
      }
      return res.json();
    }).finally(function () {
      if (timer) clearTimeout(timer);
    });
  }

  /** Abortable raw fetch (caller reads body). */
  function fetchAbort(url, opts) {
    opts = opts || {};
    var ms = opts.ms != null ? opts.ms : DESK_MS;
    var ac = typeof AbortController !== "undefined" ? new AbortController() : null;
    var timer = ac ? setTimeout(function () {
      try { ac.abort(); } catch (_) {}
    }, ms) : null;
    var init = Object.assign({}, opts, {
      signal: ac ? ac.signal : (opts.signal || undefined)
    });
    return fetch(url, init).finally(function () {
      if (timer) clearTimeout(timer);
    });
  }

  function isAbort(err) {
    return !!(err && (err.name === "AbortError" || /abort/i.test(String(err.message || err))));
  }

  global.TrinityFastBoot = {
    DESK_MS: DESK_MS,
    BOOK_MS: BOOK_MS,
    STATUS_MS: STATUS_MS,
    fetchJson: fetchJson,
    fetchAbort: fetchAbort,
    isAbort: isAbort
  };
})(typeof window !== "undefined" ? window : globalThis);
