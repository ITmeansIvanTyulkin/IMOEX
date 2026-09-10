#!/usr/bin/env python3
"""Inventory Exclusive/positional research-corpus + journal labels for training readiness.

Writes data/trend-corpus-inventory.json (gitignored). No tuning, no Phase C.
Usage: python3 scripts/trend_corpus_inventory.py
"""
from __future__ import annotations

import json
from collections import Counter
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CORPUS = ROOT / "research-corpus"
OUT = ROOT / "data" / "trend-corpus-inventory.json"
JOURNAL = ROOT / "data" / "trend-paper-journal.json"


def _day_from_name(name: str) -> str:
    return name.replace("events-", "").replace(".jsonl", "").replace("(1)", "")


def summarize_lane(subdir: str) -> dict:
    d = CORPUS / subdir
    if not d.is_dir():
        return {"exists": False, "events": 0, "days": []}
    kinds: Counter[str] = Counter()
    decisions: Counter[str] = Counter()
    htf: Counter[str] = Counter()
    rejects: Counter[str] = Counter()
    days: list[dict] = []
    events = 0
    for p in sorted(d.glob("events-*.jsonl")):
        if "(1)" in p.name:
            continue
        day = _day_from_name(p.name)
        n = 0
        for line in p.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            o = json.loads(line)
            n += 1
            events += 1
            kinds[str(o.get("kind") or "?")] += 1
            lab = o.get("label") or {}
            if isinstance(lab, dict):
                if lab.get("decision"):
                    decisions[str(lab["decision"])] += 1
                if lab.get("htf"):
                    htf[str(lab["htf"])] += 1
                rr = lab.get("rejectReason")
                if rr:
                    rejects[str(rr)[:80]] += 1
        days.append({"date": day, "events": n})
    return {
        "exists": True,
        "events": events,
        "dayCount": len(days),
        "days": days,
        "kinds": dict(kinds),
        "decisions": dict(decisions),
        "htf": dict(htf),
        "topRejects": [{"reason": r, "n": c} for r, c in rejects.most_common(12)],
    }


def journal_fair_since(since: str = "2026-08-25") -> dict:
    if not JOURNAL.is_file():
        return {"trades": 0}
    j = json.loads(JOURNAL.read_text(encoding="utf-8"))
    rows = []
    for t in j.get("trades") or []:
        if str(t.get("tag")) != "SANDBOX_FAIR":
            continue
        ca = str(t.get("closedAt") or "")
        if ca < since:
            continue
        notes = str(t.get("notes") or "")
        kind = None
        for k in ("SWEEP", "THROUGH", "GAP"):
            if f"SL={k}" in notes:
                kind = k
                break
        if kind is None:
            er = str(t.get("exitReason") or "")
            kind = "TP" if er.startswith("TP") else er or "OTHER"
        rows.append(
            {
                "day": ca[:10],
                "side": t.get("side"),
                "mode": t.get("mode"),
                "exitReason": t.get("exitReason"),
                "kind": kind,
                "pnlRub": t.get("pnlRub"),
                "operatorLabel": t.get("operatorLabel")
                or (
                    "нож vs HTF DOWN"
                    if "operator: нож vs HTF DOWN" in notes
                    else None
                ),
                "id": t.get("id"),
            }
        )
    labeled = [r for r in rows if r.get("operatorLabel")]
    pnl = sum(float(r["pnlRub"] or 0) for r in rows)
    return {
        "since": since,
        "trades": len(rows),
        "tradingDays": len({r["day"] for r in rows}),
        "pnlRub": round(pnl, 2),
        "kinds": dict(Counter(r["kind"] for r in rows)),
        "operatorLabels": labeled,
        "operatorLabelCount": len(labeled),
    }


def main() -> None:
    exclusive = summarize_lane("exclusive-m5")
    positional = summarize_lane("positional-h1")
    fair = journal_fair_since()
    # crude readiness: enough decision diversity + labeled SL journal, not "train now"
    ex_days = exclusive.get("dayCount") or 0
    arm_fill = (exclusive.get("kinds") or {}).get("ARM", 0) + (exclusive.get("kinds") or {}).get("FILL", 0)
    obs_path = ROOT / "data" / "trend-training-observations.json"
    training_obs = None
    if obs_path.is_file():
        try:
            raw = json.loads(obs_path.read_text(encoding="utf-8"))
            training_obs = {
                "file": "data/trend-training-observations.json",
                "items": len(raw.get("items") or []),
                "similarDays": len((raw.get("similarDayScan") or {}).get("days") or []),
            }
        except Exception:
            training_obs = {"file": "data/trend-training-observations.json", "error": "unreadable"}
    brief = {
        "asOf": datetime.now().strftime("%Y-%m-%dT%H:%M+03"),
        "mode": "collect — Phase C NO_GO; ML only on explicit go",
        "tune": "none — doNotTuneOnSight",
        "exclusiveM5": exclusive,
        "positionalH1": positional,
        "sandboxFairJournal": fair,
        "trainingObservations": training_obs,
        "trainingReadiness": {
            "status": "collecting",
            "exclusiveDecisionDays": ex_days,
            "exclusiveEvents": exclusive.get("events") or 0,
            "armFillEvents": arm_fill,
            "operatorLabels": fair.get("operatorLabelCount") or 0,
            "trainingObservationItems": (training_obs or {}).get("items") or 0,
            "hint": "полноценное ML — после нормальной длины размеченных дней и явного go оператора",
            "notReadyReason": [
                "Phase C NO_GO — сначала история решений",
                "operator labels ещё мало (нужны спорные кейсы вроде нож vs HTF)",
                "не учить/оптимизировать по одному дню",
            ],
        },
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(brief, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(brief["trainingReadiness"], ensure_ascii=False, indent=2))
    print("wrote", OUT)


if __name__ == "__main__":
    main()
