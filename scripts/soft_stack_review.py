#!/usr/bin/env python3
"""N-day review: Exclusive soft-memory hits vs subsequent win/loss.

Scans trend-paper-journal for closes that carried contextAtEnter.softMatch
(armed after a history stack match). Reports how often soft×size / soft continuum
ended win vs loss — if soft is cutting winners, tighten overlap, do not add veto.

Usage:
  python3 scripts/soft_stack_review.py
  python3 scripts/soft_stack_review.py --days 14
"""
from __future__ import annotations

import argparse
import json
from collections import Counter
from datetime import date, datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JOURNAL = ROOT / "data" / "trend-paper-journal.json"
ARCHIVE = ROOT / "data" / "trend-paper-voided-archive.json"


def parse_day(iso: str | None) -> date | None:
    if not iso:
        return None
    try:
        return datetime.fromisoformat(iso.replace("Z", "+00:00")[:19]).date()
    except Exception:
        try:
            return date.fromisoformat(iso[:10])
        except Exception:
            return None


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=14, help="Lookback calendar days")
    args = ap.parse_args()
    since = date.today() - timedelta(days=args.days)

    trades = []
    for path in (JOURNAL, ARCHIVE):
        if not path.is_file():
            continue
        raw = json.loads(path.read_text())
        trades.extend(raw.get("trades") or [])

    soft = []
    for t in trades:
        ctx = t.get("contextAtEnter") or {}
        if not ctx.get("softMatch"):
            continue
        d = parse_day(t.get("closedAt") or t.get("openedAt"))
        if d is None or d < since:
            continue
        pnl = float(t.get("pnlRub") or 0)
        soft.append({
            "day": str(d),
            "id": t.get("id"),
            "side": t.get("side"),
            "mode": t.get("mode"),
            "pnl": pnl,
            "match": ctx.get("softMatch"),
            "overlap": ctx.get("softOverlap"),
            "sizeMult": ctx.get("softSizeMult"),
        })

    wins = [s for s in soft if s["pnl"] > 0]
    losses = [s for s in soft if s["pnl"] < 0]
    print(f"soft-armed closes last {args.days}d: {len(soft)}  W={len(wins)} L={len(losses)}")
    if soft:
        wr = 100.0 * len(wins) / len(soft)
        print(f"WR among soft-armed: {wr:.0f}%  net={sum(s['pnl'] for s in soft):+.0f}₽")
        print("If WR high and net>0 — soft size/wait may be cutting winners → tighten overlap, no veto.")
        print("If WR low / net<0 — soft is doing its job.")
        for s in sorted(soft, key=lambda x: x["day"]):
            print(f"  {s['day']} {s['side']}/{s['mode']} {s['pnl']:+.0f} match={s['match']}")
    else:
        print("No softMatch on closes yet — memory will fill as contextAtEnter is persisted on new arms.")

    # Memory corpus size (eligible stacks)
    elig = 0
    for t in trades:
        pnl = float(t.get("pnlRub") or 0)
        lesson = t.get("lessonOnly") or t.get("keptOnStatement")
        if not lesson and pnl >= 0:
            continue
        notes = (t.get("notes") or "") + (t.get("id") or "")
        if "levels-profile" not in notes and not lesson:
            continue
        ctx = t.get("contextAtEnter") or {}
        if not ctx:
            continue
        buy = t.get("side") != "SELL"
        d = float(ctx.get("delta") or 0)
        imb = float(ctx.get("domImbalance") or 0)
        ph = (ctx.get("sessionPhase") or "").lower()
        bad_d = d < -200 if buy else d > 200
        bad_dom = imb < 0 if buy else imb > 0
        phase = (("ралли" in ph or "rally" in ph) if buy else ("дамп" in ph or "dump" in ph))
        if sum([bad_d, bad_dom, phase]) >= 2:
            elig += 1
    print(f"eligible LOSS/LESSON stacks in corpus: {elig}")


if __name__ == "__main__":
    main()
