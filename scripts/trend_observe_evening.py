#!/usr/bin/env python3
"""Evening Exclusive BR M5 observation snapshot for observePathToReal.
No strategy tuning — read-only. Writes/merges into data/trend-observe-path-log.json.
Usage: python3 scripts/trend_observe_evening.py [YYYY-MM-DD]
"""
from __future__ import annotations

import json
import sys
import urllib.request
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data"
JOURNAL = DATA / "trend-paper-journal.json"
ROBOT_JOURNAL = DATA / "trend-robot-journal.json"
LOG = DATA / "trend-observe-path-log.json"
DESK_URL = "http://127.0.0.1:8080/api/trend/desk"

GATE_NEEDLES = (
    ("smash", ("вынос через дневную полку", "smash")),
    ("sweepBufferSkip", ("+sweep", "exceeds speculative")),
    ("deepPokeKnife", ("deep-poke", "нож по day")),
    ("dayShelfSkip", ("SL beyond day shelf", "beyond range exceeds speculative")),
)


def parse_day(s: str | None) -> str:
    if s:
        return s[:10]
    return datetime.now().strftime("%Y-%m-%d")


def load_trades():
    raw = json.loads(JOURNAL.read_text(encoding="utf-8"))
    if isinstance(raw, list):
        return raw
    return raw.get("trades") or []


def exclusive_on(day: str):
    out = []
    for t in load_trades():
        if str(t.get("tag")) != "SANDBOX_FAIR":
            continue
        if "levels-profile-br-m5" not in str(t.get("id", "")):
            continue
        blob = str(t.get("openedAt", "")) + str(t.get("closedAt", ""))
        if day in blob:
            out.append(t)
    return out


def classify_notes(notes: str) -> str | None:
    n = notes or ""
    for k in ("SWEEP", "THROUGH", "GAP"):
        if f"SL={k}" in n or f"slKind={k}" in n or f"SL={k}" in n:
            return k
    if "SWEEP" in n:
        return "SWEEP"
    if "THROUGH" in n:
        return "THROUGH"
    return None


def desk_snapshot():
    try:
        with urllib.request.urlopen(DESK_URL, timeout=20) as r:
            d = json.load(r)
    except Exception as e:
        return {"error": str(e)}
    fp = d.get("fairPaper") or {}
    paper = d.get("paper") or {}
    st = paper.get("statement") if isinstance(paper, dict) else {}
    if not isinstance(st, dict):
        st = {}
    sit = d.get("situation") or {}
    lc = sit.get("lastClose") or fp.get("latestClose") or paper.get("latestClose") or {}
    if not isinstance(lc, dict):
        lc = {}
    fp_lane = sit.get("fairPaper") if isinstance(sit.get("fairPaper"), dict) else {}
    open_ = fp_lane.get("open") or fp.get("open")
    return {
        "fillMode": fp.get("fillMode") or sit.get("fillMode"),
        "fillModeRu": fp.get("fillModeRu") or sit.get("fillModeRu"),
        "engineState": d.get("engineState") or sit.get("engineState"),
        "blockReason": (d.get("blockReason") or sit.get("blockReason") or "")[:240],
        "inTrade": bool(sit.get("inTrade")),
        "liveExecution": sit.get("liveExecution"),
        "todayPnlRub": st.get("todayPnlRub"),
        "todayTradeCount": st.get("todayTradeCount"),
        "todayWins": st.get("todayWins"),
        "todayLosses": st.get("todayLosses"),
        "lastCloseSlKind": lc.get("slKind"),
        "lastCloseId": lc.get("id"),
        "open": open_ if isinstance(open_, dict) else None,
        "contractExpiry": sit.get("contractExpiry"),
    }


def gate_hits_for_day(day: str) -> dict:
    """Scan robot journal + desk gate-observe JSONL (skips are rarely journaled)."""
    hits = {k: 0 for k, _ in GATE_NEEDLES}
    samples = {k: [] for k, _ in GATE_NEEDLES}
    gate_log = DATA / "trend-gate-observe.jsonl"
    if gate_log.exists():
        for line in gate_log.read_text(encoding="utf-8").splitlines():
            if not line.strip() or day not in line:
                continue
            try:
                row = json.loads(line)
            except Exception:
                continue
            for key in row.get("hits") or []:
                if key in hits:
                    hits[key] += 1
                    if len(samples[key]) < 3:
                        samples[key].append((str(row.get("at"))[:19], str(row.get("rationale") or "")[:120]))
    if not ROBOT_JOURNAL.exists():
        return {"counts": hits, "samples": samples}
    try:
        raw = json.loads(ROBOT_JOURNAL.read_text(encoding="utf-8"))
    except Exception:
        return {"counts": hits, "samples": samples}
    entries = raw.get("entries") if isinstance(raw, dict) else []
    for e in entries or []:
        at = str(e.get("at") or "")
        if day not in at:
            continue
        plan = e.get("plan") or {}
        blob = " ".join(
            [
                str(plan.get("rationale") or ""),
                str(plan.get("state") or ""),
                " ".join(str(m) for m in (e.get("messages") or [])),
            ]
        ).lower()
        for key, needles in GATE_NEEDLES:
            for n in needles:
                if n.lower() in blob:
                    hits[key] += 1
                    if len(samples[key]) < 3:
                        samples[key].append((at[:19], (plan.get("rationale") or "")[:120]))
                    break
    return {"counts": hits, "samples": samples}


def main():
    day = parse_day(sys.argv[1] if len(sys.argv) > 1 else None)
    desk = desk_snapshot()
    gates = gate_hits_for_day(day)
    trades = exclusive_on(day)
    kinds = {"SWEEP": 0, "THROUGH": 0, "GAP": 0, "UNLABELED_SL": 0, "TP": 0, "OTHER": 0}
    rows = []
    pnl = 0.0
    for t in trades:
        er = str(t.get("exitReason") or "")
        notes = str(t.get("notes") or "")
        kind = classify_notes(notes)
        if er in ("SL", "BE_STOP"):
            if kind:
                kinds[kind] = kinds.get(kind, 0) + 1
            else:
                kinds["UNLABELED_SL"] += 1
        elif er.startswith("TP"):
            kinds["TP"] += 1
        else:
            kinds["OTHER"] += 1
        p = float(t.get("pnlRub") or 0)
        pnl += p
        rows.append(
            {
                "openedAt": t.get("openedAt"),
                "closedAt": t.get("closedAt"),
                "side": t.get("side"),
                "mode": t.get("mode"),
                "exitReason": er,
                "slKind": kind,
                "pnlRub": t.get("pnlRub"),
                "entry": t.get("entryPrice"),
                "sl": t.get("stopLoss"),
            }
        )

    entry = {
        "date": day,
        "phase": "A",
        "checkedAt": datetime.now().strftime("%Y-%m-%dT%H:%M+03"),
        "desk": desk,
        "exclusive": {
            "trades": len(trades),
            "pnlRub": round(pnl, 2),
            "kinds": kinds,
            "rows": rows,
        },
        "gateHits": gates,
        "gatesOk": desk.get("fillMode") == "FORMING_BAR",
        "wireOk": "slObserveNote → journal notes + lastClose.slKind (verified in TrendFairPaperLiveService)",
        "tune": "none — doNotTuneOnSight",
        "verdict": None,
    }
    if desk.get("fillMode") != "FORMING_BAR":
        entry["verdict"] = "WARN: desk not FORMING_BAR — restart needed before counting observation day"
    elif len(trades) == 0 and sum(gates["counts"].values()) == 0:
        entry["verdict"] = "нет Exclusive сделок и skip-хитов за день — день наблюдения слабый"
    elif kinds["UNLABELED_SL"] and not (kinds["SWEEP"] or kinds["THROUGH"]):
        entry["verdict"] = "есть SL без SL=SWEEP|THROUGH в notes — сделки до/без новых меток"
    else:
        entry["verdict"] = (
            f"OK snapshot: {len(trades)} trades, pnl={pnl:.1f}, "
            f"SWEEP={kinds['SWEEP']} THROUGH={kinds['THROUGH']} TP={kinds['TP']} "
            f"gateHits={gates['counts']}"
        )

    log = {"goal": "observePathToReal after gates 2026-08-24", "doNotTuneOnSight": True, "days": []}
    if LOG.exists():
        try:
            log = json.loads(LOG.read_text(encoding="utf-8"))
        except Exception:
            pass
    days = [d for d in (log.get("days") or []) if d.get("date") != day]
    # keep prior midSession/baseline/eod fields if merging same day
    prev = next((d for d in (log.get("days") or []) if d.get("date") == day), None)
    if prev:
        for k in ("gatesLiveSince", "afterGates", "midSession", "eod", "ops", "missedSetups"):
            if k in prev:
                entry.setdefault(k, prev[k])
        if prev.get("exclusive") and not rows:
            entry["exclusive"] = prev["exclusive"]
            entry["verdict"] = prev.get("verdict") or entry["verdict"]
    days.append(entry)
    days.sort(key=lambda x: x.get("date") or "")
    log["days"] = days
    log["doNotTuneOnSight"] = True
    log["lastEveningRun"] = entry["checkedAt"]

    # Auto-seal only at/after evening (≥18:00). Morning resume must not count the day.
    target = (log.get("split") or {}).get("observeDaysTarget") or [
        "2026-08-25",
        "2026-08-26",
        "2026-08-27",
    ]
    now_h = datetime.now().hour
    evening = now_h >= 18
    forming = desk.get("fillMode") == "FORMING_BAR"
    unlabeled_only = bool(kinds["UNLABELED_SL"]) and not (
        kinds["SWEEP"] or kinds["THROUGH"] or kinds["GAP"]
    )
    full = bool(
        evening
        and forming
        and day in target
        and not unlabeled_only
        and not str(entry.get("verdict") or "").startswith("WARN")
    )
    eod = entry.get("eod") if isinstance(entry.get("eod"), dict) else {}
    # Preserve prior true seal; never upgrade to full before evening.
    already = bool(eod.get("countAsFullObserveDay"))
    if full or already:
        entry["eod"] = {
            **eod,
            "at": entry["checkedAt"] if full else eod.get("at", entry["checkedAt"]),
            "countAsFullObserveDay": True,
            "phaseA": eod.get("phaseA")
            or (
                f"auto-seal FORMING_BAR · trades={len(trades)} pnl={pnl:.1f}"
                if full
                else eod.get("phaseA")
            ),
            "pnlRub": round(pnl, 2),
            "kinds": kinds,
            "tune": "none — doNotTuneOnSight",
        }
        for i, d in enumerate(log["days"]):
            if d.get("date") == day:
                log["days"][i] = entry
                break
    elif not evening:
        entry["eod"] = {
            **eod,
            "at": entry["checkedAt"],
            "countAsFullObserveDay": False,
            "phaseA": "intraday/morning — seal only at ~18:02 EOD",
            "tune": "none — doNotTuneOnSight",
        }
        for i, d in enumerate(log["days"]):
            if d.get("date") == day:
                log["days"][i] = entry
                break
    else:
        # Evening seal for optional/extra days (expiry, day 5+) — not in observeDaysTarget.
        # Always refresh phaseA at EOD (do not keep morning "intraday/morning" stub).
        gh = sum(gates["counts"].values())
        phase = f"EOD optional · trades={len(trades)} pnl={pnl:.1f} gateHits={gh}"
        eod_block = {
            **eod,
            "at": entry["checkedAt"],
            "countAsFullObserveDay": False,
            "phaseA": phase,
            "pnlRub": round(pnl, 2),
            "kinds": kinds,
            "gateHits": gh,
            "tune": "none — doNotTuneOnSight",
        }
        if desk.get("open"):
            eod_block["overnight"] = desk["open"]
        if desk.get("contractExpiry"):
            ce = desk["contractExpiry"]
            if isinstance(ce, dict) and ce.get("headline"):
                eod_block["contractExpiry"] = ce.get("headline")
        entry["eod"] = eod_block
        for i, d in enumerate(log["days"]):
            if d.get("date") == day:
                log["days"][i] = entry
                break

    full_days = sorted(
        {
            d.get("date")
            for d in log["days"]
            if isinstance(d.get("eod"), dict) and d["eod"].get("countAsFullObserveDay") and d.get("date")
        }
    )
    log["phaseA_progress"] = {
        "fullObserveDays": full_days,
        "target": target,
        "baselineNotCounted": "2026-08-24",
        "count": len(full_days),
        "need": 3,
        "next": next((t for t in target if t not in full_days), "phase A days done — human OOS / phase B"),
        "doNotTuneOnSight": True,
        "updatedAt": entry["checkedAt"],
    }
    # Prefer explicit operator decision if present
    phase_c = log.get("phaseCDecision") if isinstance(log.get("phaseCDecision"), dict) else {}
    if str(phase_c.get("decision") or "").upper() == "NO_GO":
        log["phaseC_blocker"] = "NO_GO — копим историю/corpus; FORTS SL и код Phase C не начинать"
        log["phaseB_gate"] = "NO_GO 04.09: observe + research-corpus до явного go на ML или Phase C"
        log["nextCheck"] = "collect corpus — resume next session; ML/Phase C only on explicit go"
    else:
        log["phaseC_blocker"] = (
            "TrendExecutionBridge: live-execution journals only; "
            "BrokerClient pairs-oriented — FORTS single-leg SL not placed yet"
        )
        log["phaseB_gate"] = (
            "2–3+ полных FORMING_BAR дней + положительная fair-paper expectancy + labeled SL "
            "до малого FORTS SL (human OOS review обязателен)"
        )
        nxt = log["phaseA_progress"]["next"]
        log["nextCheck"] = (
            f"{nxt} ~18:02 — python3 scripts/trend_observe_evening.py"
            if nxt.startswith("20")
            else nxt
        )
    LOG.write_text(json.dumps(log, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(entry, ensure_ascii=False, indent=2))
    print("wrote", LOG)
    print("phaseA_full_days", full_days)
    # phase B rollup + corpus inventory (read-only; no Exclusive knobs)
    try:
        import subprocess

        subprocess.run(
            [sys.executable, str(ROOT / "scripts" / "trend_observe_expectancy.py")],
            check=False,
            cwd=str(ROOT),
        )
        subprocess.run(
            [sys.executable, str(ROOT / "scripts" / "trend_corpus_inventory.py")],
            check=False,
            cwd=str(ROOT),
        )
        subprocess.run(
            [sys.executable, str(ROOT / "scripts" / "trend_label_candidates.py")],
            check=False,
            cwd=str(ROOT),
        )
        subprocess.run(
            [sys.executable, str(ROOT / "scripts" / "trend_training_obs_scan.py")],
            check=False,
            cwd=str(ROOT),
        )
    except Exception as ex:
        print("expectancy rollup skipped:", ex)


if __name__ == "__main__":
    main()
