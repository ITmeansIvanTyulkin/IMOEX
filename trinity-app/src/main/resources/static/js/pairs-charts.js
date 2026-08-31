(function () {
  "use strict";

  function $(id) { return document.getElementById(id); }

  function escapeHtml(s) {
    return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }

  function commonOpts(height) {
    return {
      height: height,
      layout: { backgroundColor: "#ffffff", textColor: "#1a1a2e" },
      grid: { vertLines: { color: "#eee" }, horzLines: { color: "#eee" } },
      rightPriceScale: { borderColor: "#ddd" },
      timeScale: { borderColor: "#ddd" },
      handleScroll: { mouseWheel: false, pressedMouseMove: true, horzTouchDrag: true, vertTouchDrag: false },
      handleScale: { axisPressedMouseMove: true, mouseWheel: false, pinch: true }
    };
  }

  function bindNav(chart, series, host, getBars, barSec, pointSize) {
    const kit = window.TrinityChartKit;
    if (!kit || typeof kit.attachFriendlyNav !== "function" || !chart || !series || !host) return;
    kit.attachFriendlyNav({
      chart: chart,
      series: series,
      hostEl: host,
      barSec: barSec > 0 ? barSec : 86400,
      pointSize: pointSize > 0 ? pointSize : 0.01,
      getBars: getBars
    });
  }

  async function boot() {
    const root = $("pairs-charts-root");
    const yEl = $("chart-price");
    const xEl = $("chart-price-x");
    if (!yEl || typeof LightweightCharts === "undefined") return;
    const meta = $("chart-meta");
    const explain = $("chart-explain");
    let tickerY = (root && root.getAttribute("data-y")) || "";
    let tickerX = (root && root.getAttribute("data-x")) || "";
    if (!tickerY || !tickerX) {
      const m = /График пары\s+(\S+)\s+\/\s+(\S+)/.exec((document.querySelector(".chart-head h2") || {}).textContent || "");
      if (m) {
        tickerY = m[1];
        tickerX = m[2];
      }
    }
    if (!tickerY || !tickerX) {
      if (meta) meta.textContent = "Нет тикеров пары";
      return;
    }

    const resp = await fetch("/api/charts/" + encodeURIComponent(tickerY) + "/" + encodeURIComponent(tickerX) + "/data");
    if (!resp.ok) {
      const err = await resp.json().catch(function () { return { error: resp.statusText }; });
      if (meta) meta.textContent = "Ошибка: " + (err.error || resp.status);
      return;
    }
    const data = await resp.json();
    if (meta) {
      meta.textContent = "Сигнал: " + data.signal
        + " | Z=" + Number(data.currentZScore).toFixed(2)
        + " | beta=" + Number(data.hedgeRatio).toFixed(3)
        + " | half-life≈" + Number(data.halfLifeDays).toFixed(0) + "д"
        + " | Sharpe=" + Number(data.sharpeRatio).toFixed(2);
    }
    if (explain) {
      explain.innerHTML = '<div class="summary">' + escapeHtml(data.summary || "") + "</div>"
        + "<div>" + escapeHtml(data.details || "").replace(/\n/g, "<br>") + "</div>";
    }

    const candlesY = (data.candlesY || []).map(function (b) {
      return { time: b.time, open: b.open, high: b.high, low: b.low, close: b.close };
    });
    const candlesX = (data.candlesX || []).map(function (b) {
      return { time: b.time, open: b.open, high: b.high, low: b.low, close: b.close };
    });
    const normY = (data.normalizedY || []).map(function (p) { return { time: p.time, value: p.value }; });
    const normX = (data.normalizedX || []).map(function (p) { return { time: p.time, value: p.value }; });
    const spreadPts = (data.spread || []).map(function (p) { return { time: p.time, value: p.value }; });
    const kamaPts = (data.kama || []).map(function (p) { return { time: p.time, value: p.value }; });
    const zPts = (data.zScore || []).map(function (p) { return { time: p.time, value: p.value }; });

    const priceChart = LightweightCharts.createChart(yEl, commonOpts(320));
    const candles = priceChart.addCandlestickSeries({
      upColor: "#16a34a", downColor: "#dc2626", borderVisible: false,
      wickUpColor: "#16a34a", wickDownColor: "#dc2626"
    });
    candles.setData(candlesY);
    bindNav(priceChart, candles, yEl, function () { return candlesY; }, 86400, 0.01);

    const priceXEl = $("chart-price-x");
    const priceXChart = LightweightCharts.createChart(priceXEl, commonOpts(280));
    const candlesXs = priceXChart.addCandlestickSeries({
      upColor: "#16a34a", downColor: "#dc2626", borderVisible: false,
      wickUpColor: "#16a34a", wickDownColor: "#dc2626"
    });
    candlesXs.setData(candlesX);
    bindNav(priceXChart, candlesXs, priceXEl, function () { return candlesX; }, 86400, 0.01);

    const divEl = $("chart-divergence");
    const divChart = LightweightCharts.createChart(divEl, commonOpts(260));
    const ny = divChart.addLineSeries({ color: "#0f3460", lineWidth: 2, title: tickerY });
    const nx = divChart.addLineSeries({ color: "#e94560", lineWidth: 2, title: tickerX });
    ny.setData(normY);
    nx.setData(normX);
    bindNav(divChart, ny, divEl, function () { return normY; }, 86400, 0.01);

    const spEl = $("chart-spread");
    const spChart = LightweightCharts.createChart(spEl, commonOpts(260));
    const spread = spChart.addLineSeries({ color: "#0f3460", lineWidth: 2, title: "Spread" });
    const kama = spChart.addLineSeries({ color: "#f59e0b", lineWidth: 2, title: "KAMA" });
    spread.setData(spreadPts);
    kama.setData(kamaPts);
    bindNav(spChart, spread, spEl, function () { return spreadPts; }, 86400, 0.01);

    const zEl = $("chart-z");
    const zChart = LightweightCharts.createChart(zEl, commonOpts(360));
    const zSeries = zChart.addLineSeries({ color: "#7c3aed", lineWidth: 2, title: "Z" });
    zSeries.setData(zPts);
    zSeries.createPriceLine({ price: 0, color: "#94a3b8", lineWidth: 1, lineStyle: 2, title: "0" });
    zSeries.createPriceLine({ price: data.zEntry, color: "#dc2626", lineWidth: 1, lineStyle: 2, title: "+" + data.zEntry });
    zSeries.createPriceLine({ price: -data.zEntry, color: "#16a34a", lineWidth: 1, lineStyle: 2, title: "-" + data.zEntry });

    const markers = (data.markers || [])
      .filter(function (m) { return m.series === "zscore"; })
      .map(function (m) {
        return {
          time: m.time,
          position: m.position,
          color: m.color,
          shape: m.shape,
          text: m.text
        };
      });
    const byTime = {};
    markers.forEach(function (m) { byTime[m.time] = m; });
    zSeries.setMarkers(Object.values(byTime).sort(function (a, b) {
      return String(a.time).localeCompare(String(b.time));
    }));
    bindNav(zChart, zSeries, zEl, function () { return zPts; }, 86400, 0.01);

    [priceChart, priceXChart, divChart, spChart, zChart].forEach(function (c) {
      try { c.timeScale().fitContent(); } catch (_) {}
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", function () {
      boot().catch(function (e) {
        const meta = $("chart-meta");
        if (meta) meta.textContent = "Ошибка: " + (e && e.message ? e.message : e);
      });
    });
  } else {
    boot().catch(function (e) {
      const meta = $("chart-meta");
      if (meta) meta.textContent = "Ошибка: " + (e && e.message ? e.message : e);
    });
  }
})();
