#!/usr/bin/env python3
"""Refresh similar-day scan for training observations (no-chase above TOP).

Updates data/trend-training-observations.json similarDayScan.
Does not tune strategy or apply gates.

Usage: python3 scripts/trend_training_obs_scan.py
"""
from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CORPUS = ROOT / "research-corpus" / "exclusive-m5"
OUT = ROOT / "data" / "trend-training-observations.json"


def scan(since: str = "2026-08-25", until: str | None = None) -> list[dict]:
    until = until or datetime.now().strftime("%Y-%m-%d")
    similar: list[dict] = []
    for p in sorted(CORPUS.glob("events-*.jsonl")):
        if "(1)" in p.name:
            continue
        day = p.name.replace("events-", "").replace(".jsonl", "")
        if day < since or day > until:
            continue
        lines = p.read_text(encoding="utf-8").splitlines()
        closes, fills = [], []
        for line in lines:
            if not line.strip():
                continue
            o = json.loads(line)
            ts = o.get("ts") or ""
            if o.get("kind") == "CLOSE":
                closes.append(ts)
            if o.get("kind") == "FILL":
                fills.append(ts)

        if not closes and not fills:
            top_wait = 0
            for line in lines:
                if not line.strip():
                    continue
                o = json.loads(line)
                lab = o.get("label") or {}
                rr = str(lab.get("rejectReason") or "")
                if (
                    o.get("kind") == "PLAN_REJECTED"
                    and ("TREND_HI" in rr or "TOP" in rr)
                    and "waiting" in rr.lower()
                ):
                    top_wait += 1
            if top_wait >= 10:
                similar.append(
                    {
                        "date": day,
                        "firstClose": None,
                        "fillsAfterFirstClose": 0,
                        "topWaitRejectsAfter": top_wait,
                        "pattern": "no_fill_all_day + sustained TOP wait",
                    }
                )
            continue

        if not closes:
            continue
        first_close = min(closes)
        fills_after = [t for t in fills if t > first_close]
        top_wait_after = 0
        for line in lines:
            if not line.strip():
                continue
            o = json.loads(line)
            ts = o.get("ts") or ""
            if ts <= first_close:
                continue
            lab = o.get("label") or {}
            rr = str(lab.get("rejectReason") or "")
            if (
                o.get("kind") == "PLAN_REJECTED"
                and ("TREND_HI" in rr or "TOP" in rr)
                and "waiting" in rr.lower()
            ):
                top_wait_after += 1
        if fills_after or top_wait_after < 10:
            continue
        similar.append(
            {
                "date": day,
                "firstClose": first_close[11:16],
                "fillsAfterFirstClose": 0,
                "topWaitRejectsAfter": top_wait_after,
                "pattern": "no_fill_after_first_close + sustained TOP wait",
            }
        )
    return similar


def main() -> None:
    similar = scan()
    store = json.loads(OUT.read_text(encoding="utf-8")) if OUT.is_file() else {"items": []}
    store["similarDayScan"] = {
        "at": datetime.now().strftime("%Y-%m-%dT%H:%M+03"),
        "rule": "0 FILL after first CLOSE (or all day) + ≥10 TOP-wait rejects",
        "days": similar,
        "note": "candidates for priceAboveTopNoChase — not auto gates",
    }
    store["updatedAt"] = store["similarDayScan"]["at"]
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(store, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"similarDays": len(similar), "dates": [d["date"] for d in similar]}, ensure_ascii=False))
    print("wrote", OUT)


if __name__ == "__main__":
    main()
