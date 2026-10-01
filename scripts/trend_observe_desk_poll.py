#!/usr/bin/env python3
"""Poll trend desk for gate skip rationales (smash/buffer/knife/shelf).
Robot journal only stores actionable plans — skips never appear there.
Append-only JSONL. No strategy tuning.
Usage: python3 scripts/trend_observe_desk_poll.py [--once] [--seconds N]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from trend_observe_auth import DESK_URL, desk_request  # noqa: E402

OUT = ROOT / "data" / "trend-gate-observe.jsonl"

NEEDLES = (
    ("smash", ("вынос через дневную полку", "smash")),
    ("sweepBufferSkip", ("+sweep", "exceeds speculative", "sweep ")),
    ("deepPokeKnife", ("deep-poke", "нож по day")),
    ("dayShelfSkip", ("SL beyond day shelf", "beyond range exceeds speculative")),
)


def fetch_desk():
    return desk_request(DESK_URL, timeout=20)


def classify(text: str) -> list[str]:
    low = (text or "").lower()
    hits = []
    for key, needles in NEEDLES:
        if any(n.lower() in low for n in needles):
            hits.append(key)
    return hits


def snapshot():
    d = fetch_desk()
    plan = d.get("plan") or {}
    sit = d.get("situation") or {}
    fp = d.get("fairPaper") or {}
    rationale = str(plan.get("rationale") or d.get("blockReason") or "")
    blob = " | ".join(
        [
            rationale,
            str(d.get("blockReason") or ""),
            str(sit.get("why") or ""),
        ]
    )
    hits = classify(blob)
    bars = d.get("bars") or []
    last = bars[-1] if bars else {}
    return {
        "at": datetime.now().strftime("%Y-%m-%dT%H:%M:%S+03"),
        "fillMode": fp.get("fillMode") or sit.get("fillMode"),
        "planState": plan.get("state"),
        "mode": plan.get("mode"),
        "actionable": plan.get("actionable"),
        "inTrade": sit.get("inTrade"),
        "hits": hits,
        "rationale": rationale[:300],
        "lastBar": last.get("time"),
        "close": last.get("close"),
        "low": last.get("low"),
        "high": last.get("high"),
    }


def dedupe_key(row: dict) -> str:
    raw = f"{row.get('hits')}|{row.get('rationale')}|{str(row.get('at',''))[:16]}"
    return hashlib.sha1(raw.encode()).hexdigest()[:12]


def append_if_new(row: dict, seen: set[str]) -> bool:
    if not row.get("hits"):
        return False
    k = dedupe_key(row)
    if k in seen:
        return False
    seen.add(k)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("a", encoding="utf-8") as f:
        f.write(json.dumps(row, ensure_ascii=False) + "\n")
    return True


def daemonize(log_path: Path) -> None:
    """Double-fork so Cursor/agent shell teardown cannot kill the poll."""
    log_path = log_path.expanduser().resolve()
    log_path.parent.mkdir(parents=True, exist_ok=True)
    if os.fork() > 0:
        raise SystemExit(0)
    os.setsid()
    if os.fork() > 0:
        raise SystemExit(0)
    os.chdir("/")
    fd = os.open(str(log_path), os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o644)
    os.dup2(fd, 1)
    os.dup2(fd, 2)
    os.close(fd)
    devnull = os.open(os.devnull, os.O_RDONLY)
    os.dup2(devnull, 0)
    os.close(devnull)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--once", action="store_true")
    ap.add_argument("--seconds", type=int, default=5400, help="poll duration (default ~90m)")
    ap.add_argument("--interval", type=int, default=60)
    ap.add_argument(
        "--daemon",
        action="store_true",
        help="double-fork detach; log to --log (default data/trend-desk-poll.log)",
    )
    ap.add_argument(
        "--log",
        type=Path,
        default=None,
        help="log file when --daemon (also used to redirect stdout)",
    )
    args = ap.parse_args()

    if args.daemon:
        log = args.log or (ROOT / "data" / "trend-desk-poll.log")
        daemonize(log)

    seen: set[str] = set()
    if OUT.exists():
        for line in OUT.read_text(encoding="utf-8").splitlines():
            try:
                seen.add(dedupe_key(json.loads(line)))
            except Exception:
                pass

    deadline = time.time() + (0 if args.once else args.seconds)
    while True:
        try:
            row = snapshot()
            if append_if_new(row, seen):
                print(
                    "AGENT_LOOP_WAKE_trend_gate "
                    + json.dumps(
                        {
                            "prompt": (
                                "Goal observePathToReal: новый gate-hit на desk "
                                f"({row['hits']}). Допиши в trend-observe-path-log.json. "
                                "НЕ крути пад/knife."
                            ),
                            "hits": row["hits"],
                            "rationale": row["rationale"][:160],
                        },
                        ensure_ascii=False,
                    ),
                    flush=True,
                )
            else:
                print(
                    f"poll ok fill={row.get('fillMode')} state={row.get('planState')} "
                    f"C={row.get('close')} hits={row.get('hits')}",
                    flush=True,
                )
        except Exception as e:
            print(f"poll err {e}", flush=True)
        if args.once or time.time() >= deadline:
            break
        time.sleep(max(15, args.interval))
    print("desk_poll_done", flush=True)


if __name__ == "__main__":
    main()
