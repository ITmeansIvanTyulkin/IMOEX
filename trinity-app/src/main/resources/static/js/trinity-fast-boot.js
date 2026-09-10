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
