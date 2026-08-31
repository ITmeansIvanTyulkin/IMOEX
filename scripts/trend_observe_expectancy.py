#!/usr/bin/env python3
"""Exclusive BR M5 fair-paper expectancy rollup for observePathToReal phase B.

Read-only. Merges into data/trend-observe-path-log.json under phaseB_expectancy.
Does not tune strategy.

Usage: python3 scripts/trend_observe_expectancy.py [--since YYYY-MM-DD]
Default since = first full observe day after gates (2026-08-25).
"""
from __future__ import annotations

import argparse
import json
import statistics
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data"
JOURNAL = DATA / "trend-paper-journal.json"
LOG = DATA / "trend-observe-path-log.json"
DEFAULT_SINCE = "2026-08-25"


def load_trades():
    raw = json.loads(JOURNAL.read_text(encoding="utf-8"))
    if isinstance(raw, list):
        return raw
    return raw.get("trades") or []


def classify_notes(notes: str) -> str | None:
    n = notes or ""
    for k in ("SWEEP", "THROUGH", "GAP"):
        if f"SL={k}" in n or f"slKind={k}" in n:
            return k
    if "SWEEP" in n:
        return "SWEEP"
    if "THROUGH" in n:
        return "THROUGH"
    return None


def exclusive_closed_since(since: str):
    out = []
    for t in load_trades():
        if str(t.get("tag")) != "SANDBOX_FAIR":
            continue
        if "levels-profile-br-m5" not in str(t.get("id", "")):
            continue
        closed = str(t.get("closedAt") or "")
        if len(closed) < 10 or closed[:10] < since:
            continue
        out.append(t)
    return out


def rollup(trades: list) -> dict:
    pnls = [float(t.get("pnlRub") or 0) for t in trades]
    wins = [p for p in pnls if p > 0]
    losses = [p for p in pnls if p < 0]
    kinds = {"SWEEP": 0, "THROUGH": 0, "GAP": 0, "UNLABELED_SL": 0, "TP": 0, "OTHER": 0}
    by_day: dict[str, float] = {}
    for t in trades:
        er = str(t.get("exitReason") or "")
        kind = classify_notes(str(t.get("notes") or ""))
        if er in ("SL", "BE_STOP"):
            key = kind or "UNLABELED_SL"
            kinds[key] = kinds.get(key, 0) + 1
        elif er.startswith("TP"):
            kinds["TP"] += 1
        else:
            kinds["OTHER"] += 1
        day = str(t.get("closedAt") or "")[:10]
        by_day[day] = by_day.get(day, 0.0) + float(t.get("pnlRub") or 0)

    n = len(pnls)
    avg = statistics.mean(pnls) if pnls else 0.0
    win_rate = (len(wins) / n) if n else 0.0
    avg_win = statistics.mean(wins) if wins else 0.0
    avg_loss = statistics.mean(losses) if losses else 0.0
    labeled_sl = kinds["SWEEP"] + kinds["THROUGH"] + kinds["GAP"]
    unlabeled = kinds["UNLABELED_SL"]
    return {
        "asOf": datetime.now().strftime("%Y-%m-%dT%H:%M+03"),
        "trades": n,
        "tradingDays": len(by_day),
        "pnlRub": round(sum(pnls), 2),
        "expectancyRubPerTrade": round(avg, 2),
        "winRate": round(win_rate, 3),
        "avgWinRub": round(avg_win, 2),
        "avgLossRub": round(avg_loss, 2),
        "kinds": kinds,
        "labeledSlShare": round(labeled_sl / max(1, labeled_sl + unlabeled), 3)
        if (labeled_sl + unlabeled)
        else None,
        "pnlByDay": {k: round(v, 2) for k, v in sorted(by_day.items())},
        "readyForPhaseC": False,
        "readyReason": None,
    }


def phase_c_ready(r: dict, full_days: list[str], target_n: int = 3) -> tuple[bool, str]:
    """Conservative gate: enough full days + positive expectancy + labeled SLs."""
    if len(full_days) < target_n:
        return False, f"нужно {target_n} полных FORMING_BAR дней (есть {len(full_days)}: {full_days})"
    if r["trades"] < 6:
        return False, f"нужно ≥6 Exclusive closes для грубой expectancy (есть {r['trades']})"
    if r["expectancyRubPerTrade"] <= 0:
        return False, f"expectancy ≤0 ({r['expectancyRubPerTrade']} ₽/trade) — продолжать наблюдение"
    if r.get("labeledSlShare") is not None and r["labeledSlShare"] < 0.8:
        return False, f"доля labeled SL {r['labeledSlShare']} < 0.8"
    return (
        True,
        "дней phase A + положительная expectancy + labeled SL — перед FORTS SL нужен human OOS review",
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--since", default=DEFAULT_SINCE)
    args = ap.parse_args()
    trades = exclusive_closed_since(args.since)
    r = rollup(trades)
    r["since"] = args.since

    log = {"goal": "observePathToReal after gates 2026-08-24", "doNotTuneOnSight": True, "days": []}
    if LOG.exists():
        try:
            log = json.loads(LOG.read_text(encoding="utf-8"))
        except Exception:
            pass

    full_days = []
    for d in log.get("days") or []:
        eod = d.get("eod") or {}
        if eod.get("countAsFullObserveDay"):
            full_days.append(d.get("date"))
    prog = log.get("phaseA_progress") or {}
    for d in prog.get("fullObserveDays") or []:
        if d not in full_days:
            full_days.append(d)
    full_days = sorted(x for x in full_days if x)

    ready, reason = phase_c_ready(r, full_days)
    r["readyForPhaseC"] = ready
    r["readyReason"] = reason
    r["fullObserveDays"] = full_days
    r["tune"] = "none — doNotTuneOnSight"

    log["phaseB_expectancy"] = r
    log["phaseB_gate"] = (
        "2–3+ полных FORMING_BAR дней + положительная fair-paper expectancy + labeled SL "
        "до малого FORTS SL (human OOS review обязателен)"
    )
    LOG.write_text(json.dumps(log, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(r, ensure_ascii=False, indent=2))
    print("wrote", LOG)


if __name__ == "__main__":
    main()
