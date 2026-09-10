#!/usr/bin/env python3
"""Stamp contextAtEnter on SANDBOX_FAIR journal + propose operator label candidates.

Machine labels (SL=SWEEP|THROUGH|GAP) stay in notes.
Operator labels are NOT auto-applied when HTF claim would be wrong
(e.g. never auto «нож vs HTF DOWN» if corpus HTF≠DOWN).

Writes:
  - updates data/trend-paper-journal.json (contextAtEnter)
  - data/trend-label-candidates.json

Usage: python3 scripts/trend_label_candidates.py
"""
from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JOURNAL = ROOT / "data" / "trend-paper-journal.json"
CORPUS = ROOT / "research-corpus" / "exclusive-m5"
OUT = ROOT / "data" / "trend-label-candidates.json"
MSK = timezone(timedelta(hours=3))


def parse_ts(s: str) -> datetime:
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


def context_at_enter(day: str, opened_at: str, side: str) -> dict | None:
    """Nearest actionable ENTER plan around open (handles fill/plan skew)."""
    fp = CORPUS / f"events-{day}.jsonl"
    if not fp.is_file() or not opened_at:
        return None
    side_l = "LONG" if side == "BUY" else "SHORT"
    best_before = None  # (ts, ctx)
    best_near = None  # (abs_delta_sec, ctx)
    try:
        oa = parse_ts(opened_at)
    except Exception:
        return None
    for line in fp.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        o = json.loads(line)
        if o.get("kind") != "PLAN_PROPOSED":
            continue
        lab = o.get("label") or {}
        if not lab.get("actionable") or lab.get("decision") != "ENTER":
            continue
        if lab.get("side") != side_l:
            continue
        ts = o.get("ts")
        if not ts:
            continue
        try:
            t = parse_ts(ts)
        except Exception:
            continue
        pl = o.get("payload") or {}
        st = pl.get("structure") if isinstance(pl.get("structure"), dict) else {}
        ctx = {
            "planTs": ts,
            "htf": lab.get("htf") or st.get("htf"),
            "regime": lab.get("regime") or st.get("regime"),
            "mode": pl.get("mode"),
            "side": side_l,
        }
        if t <= oa + timedelta(minutes=2):
            if best_before is None or t > best_before[0]:
                best_before = (t, ctx)
        delta = abs((t - oa).total_seconds())
        if delta <= 600 and (best_near is None or delta < best_near[0]):
            best_near = (delta, ctx)
    if best_before:
        return best_before[1]
    if best_near:
        return best_near[1]
    return None


def main() -> None:
    now = datetime.now(MSK).strftime("%Y-%m-%dT%H:%M+03")
    j = json.loads(JOURNAL.read_text(encoding="utf-8"))
    changed = 0
    candidates: list[dict] = []
    for t in j.get("trades") or []:
        if str(t.get("tag")) != "SANDBOX_FAIR":
            continue
        oa = str(t.get("openedAt") or "")
        if oa < "2026-08-25":
            continue
        day = oa[:10]
        ctx = context_at_enter(day, oa, str(t.get("side") or ""))
        if ctx and t.get("contextAtEnter") != ctx:
            t["contextAtEnter"] = ctx
            changed += 1
        notes = str(t.get("notes") or "")
        sl = None
        for k in ("SWEEP", "THROUGH", "GAP"):
            if f"SL={k}" in notes:
                sl = k
                break
        op = t.get("operatorLabel")
        if not op and "operator: " in notes:
            op = notes.split("operator: ", 1)[-1].strip() or None
        pnl = float(t.get("pnlRub") or 0)
        if pnl >= 0 or not sl or op:
            continue
        htf = (ctx or {}).get("htf")
        suggest = None
        reason = None
        if t.get("side") == "BUY" and t.get("mode") == "BOUNCE" and htf == "DOWN":
            suggest = "нож vs HTF DOWN"
            reason = "BUY BOT bounce loss + corpus HTF DOWN"
        elif t.get("side") == "BUY" and t.get("mode") == "BOUNCE" and sl in ("THROUGH", "SWEEP"):
            suggest = "нож"
            reason = f"BUY BOT bounce {sl} loss; corpus HTF={htf} — не «vs HTF DOWN»"
        elif t.get("side") == "SELL" and t.get("mode") == "BOUNCE" and htf == "UP":
            suggest = "нож vs HTF UP"
            reason = "SELL TOP bounce loss + corpus HTF UP"
        candidates.append(
            {
                "id": t.get("id"),
                "openedAt": oa,
                "side": t.get("side"),
                "mode": t.get("mode"),
                "exitReason": t.get("exitReason"),
                "slKind": sl,
                "pnlRub": t.get("pnlRub"),
                "contextAtEnter": ctx,
                "suggestOperatorLabel": suggest,
                "suggestReason": reason,
                "autoApplySafe": bool(suggest and "vs HTF" in suggest and htf in ("DOWN", "UP")),
            }
        )

    j["updatedAt"] = now
    JOURNAL.write_text(json.dumps(j, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    brief = {
        "at": now,
        "policy": {
            "machineLabels": "SL=SWEEP|THROUGH|GAP in notes — always auto",
            "operatorLabels": "human or rule-gated; never auto «нож vs HTF DOWN» when corpus HTF≠DOWN",
            "contextAtEnter": "htf/regime from nearest ENTER plan — stamped for ML",
            "doNotTuneOnSight": True,
        },
        "journalContextStamped": changed,
        "pendingOperatorReview": candidates,
        "counts": {
            "pending": len(candidates),
            "safeAutoIfEnabled": sum(1 for c in candidates if c.get("autoApplySafe")),
        },
    }
    OUT.write_text(json.dumps(brief, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(brief["counts"], ensure_ascii=False))
    print("stamped", changed, "→", OUT)


if __name__ == "__main__":
    main()
