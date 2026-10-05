#!/usr/bin/env node
/**
 * Fetch yearly MSFO series from Smart-Lab for the investments universe
 * and write IMOEX/data/investments-fundamentals-msfo.json for as-of replay.
 *
 * Usage: node IMOEX/scripts/fetch-investments-msfo.mjs
 */
"use strict";

const fs = require("fs");
const path = require("path");
const Fetch = require("../../trinity-landing/js/lib/invest-fund-fetch.js");

const UNIVERSE = [
  "ROSN", "NVTK", "GAZP", "LKOH", "SIBN", "GMKN", "PLZL", "SNGS", "TATN", "NLMK",
  "CHMF", "PHOR", "TRNFP", "YDEX", "AKRN", "RUAL", "ALRS", "X5", "MAGN", "MGNT",
  "MTSS", "OZON", "PIKK", "SBER", "VTBR", "T", "MOEX",
];

const ALIASES = {
  YDEX: ["YDEX", "YNDX"],
  X5: ["X5", "FIVE"],
  T: ["T", "TCSG"],
};

const OUT = path.resolve(__dirname, "../data/investments-fundamentals-msfo.json");
const SERIES_KEYS = [
  "revenue", "debt", "equity", "op_profit", "fcf", "ebitda", "st_liab",
  "reserves", "loans", "deposits", "car", "noi",
];

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

function bankish(ticker, sector) {
  if (sector === "fin") return true;
  return ["SBER", "VTBR", "T", "BSPB", "CBOM"].includes(ticker);
}

function sectorLabel(ticker, sector) {
  if (bankish(ticker, sector)) return "Financial";
  const energy = new Set(["ROSN", "NVTK", "GAZP", "LKOH", "SIBN", "SNGS", "TATN", "TRNFP"]);
  if (energy.has(ticker)) return "Energy";
  const metals = new Set(["GMKN", "NLMK", "CHMF", "MAGN", "ALRS", "RUAL", "PLZL"]);
  if (metals.has(ticker)) return "Materials";
  return "Other";
}

async function pullOne(ticker) {
  const tries = ALIASES[ticker] || [ticker];
  let lastErr = null;
  for (const id of tries) {
    try {
      const packed = await Fetch.pullFundamentals(id, {
        timeoutMs: 20000,
        config: { fundReader: "https://r.jina.ai/" },
      });
      // Prefer parsed series from smartlab raw — re-fetch page parse if needed
      const pageUrl = Fetch.SMARTLAB_MSFO.replace("{TICKER}", encodeURIComponent(id));
      const page = await Fetch.fetchPage(pageUrl, {
        timeoutMs: 20000,
        config: { fundReader: "https://r.jina.ai/" },
      });
      const parsed = Fetch.parseSmartLab(page.text);
      if (!parsed.years || parsed.years.length < 2) {
        lastErr = "few years for " + id;
        continue;
      }
      const row = {
        ticker,
        sourceTicker: id,
        bank: bankish(ticker, packed.sector),
        sector: sectorLabel(ticker, packed.sector),
        years: parsed.years,
        sourceUrl: pageUrl,
        via: page.via,
        fieldsLatest: packed.fields || {},
      };
      for (const k of SERIES_KEYS) {
        const s = parsed.series[k];
        if (!s || s._list) continue;
        row[k] = s;
      }
      return row;
    } catch (e) {
      lastErr = e && e.message ? e.message : String(e);
    }
  }
  return { ticker, error: lastErr || "failed", bank: bankish(ticker, null), sector: sectorLabel(ticker, null), years: [] };
}

async function main() {
  const out = {
    _meta: {
      source: "smart-lab",
      fetchedAt: new Date().toISOString(),
      unitNote: "Smart-Lab MSFO annual, usually млрд ₽; car in fraction or % (normalized in scorecard)",
      asOfRule: "annual year Y available from 1 Apr Y+1; curr=latest published ≤ asOf, prev=prior year in series",
    },
  };
  for (const t of UNIVERSE) {
    process.stdout.write("MSFO " + t + " … ");
    const row = await pullOne(t);
    if (row.error) {
      console.log("FAIL", row.error);
    } else {
      console.log("ok years=" + (row.years || []).join(","), "via=" + row.via);
    }
    out[t] = row;
    await sleep(800);
  }
  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, JSON.stringify(out, null, 2));
  const ok = UNIVERSE.filter((t) => out[t] && out[t].years && out[t].years.length >= 2).length;
  console.log("Wrote", OUT, "ok", ok + "/" + UNIVERSE.length);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
