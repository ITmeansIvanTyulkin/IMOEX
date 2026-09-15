#!/usr/bin/env python3
"""Watch Exclusive observe JVM: wake agent on DOWN or sustained CPU thrash.
No strategy tuning. Usage:
  python3 scripts/trend_observe_jvm_watch.py --daemon --seconds N [--log PATH]
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import time
import urllib.request
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_WAKE = Path("/tmp/trend-observe-agent-wake.log")
HEALTH = "http://127.0.0.1:8080/actuator/health"
CPU_THRASH = 300.0  # percent; multi-core OK until sustained
CPU_SAMPLES = 3
CPU_INTERVAL = 5
WAKE_COOLDOWN_SEC = 900  # don't spam agent on same kind


def daemonize(log_path: Path) -> None:
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
    dn = os.open(os.devnull, os.O_RDONLY)
    os.dup2(dn, 0)
    os.close(dn)


def jpid() -> str | None:
    try:
        out = subprocess.check_output(
            ["pgrep", "-f", "com.moex.trinity.TrinityApplication"],
            text=True,
        ).strip()
    except subprocess.CalledProcessError:
        return None
    return out.splitlines()[0] if out else None


def cpu_pct(pid: str) -> float | None:
    try:
        out = subprocess.check_output(["ps", "-p", pid, "-o", "%cpu="], text=True).strip()
        return float(out.replace(",", "."))
    except Exception:
        return None


def health_up() -> bool:
    try:
        with urllib.request.urlopen(HEALTH, timeout=5) as r:
            return r.status == 200
    except Exception:
        return False


_last_wake_at: dict[str, float] = {}


def wake(wake_path: Path, kind: str, detail: dict) -> None:
    now = time.time()
    prev = _last_wake_at.get(kind, 0.0)
    if now - prev < WAKE_COOLDOWN_SEC:
        print(f"wake_suppressed kind={kind} cool={int(WAKE_COOLDOWN_SEC - (now - prev))}s", flush=True)
        return
    _last_wake_at[kind] = now
    prompt = (
        "Goal observePathToReal: JVM watch "
        f"{kind}. Проверь health/desk/poll; при flat — resume/daemon restart. "
        "НЕ крути пад/knife. Phase C NO_GO."
    )
    row = {
        "at": datetime.now().strftime("%Y-%m-%dT%H:%M:%S+03"),
        "kind": kind,
        "detail": detail,
        "prompt": prompt,
    }
    line = "AGENT_LOOP_WAKE_trend_jvm " + json.dumps(row, ensure_ascii=False)
    wake_path.parent.mkdir(parents=True, exist_ok=True)
    with wake_path.open("a", encoding="utf-8") as f:
        f.write(line + "\n")
    print(line, flush=True)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=int, default=28800)
    ap.add_argument("--interval", type=int, default=60)
    ap.add_argument("--daemon", action="store_true")
    ap.add_argument("--log", type=Path, default=None)
    ap.add_argument("--wake", type=Path, default=DEFAULT_WAKE)
    args = ap.parse_args()
    if args.daemon:
        daemonize(args.log or (ROOT / "data" / "trend-jvm-watch.log"))

    deadline = time.time() + args.seconds
    down_streak = 0
    thrash_streak = 0
    while time.time() < deadline:
        up = health_up()
        pid = jpid()
        if not up or not pid:
            down_streak += 1
            thrash_streak = 0
            print(f"watch down streak={down_streak} up={up} pid={pid}", flush=True)
            if down_streak >= 2:
                wake(args.wake, "DOWN", {"health": up, "pid": pid})
                down_streak = 0
                time.sleep(max(30, args.interval))
                continue
        else:
            down_streak = 0
            samples = []
            for _ in range(CPU_SAMPLES):
                c = cpu_pct(pid)
                if c is not None:
                    samples.append(c)
                time.sleep(CPU_INTERVAL)
            avg = sum(samples) / len(samples) if samples else 0.0
            print(f"watch ok pid={pid} cpu_avg={avg:.1f} samples={samples}", flush=True)
            if avg >= CPU_THRASH:
                thrash_streak += 1
                if thrash_streak >= 2:
                    wake(
                        args.wake,
                        "CPU_THRASH",
                        {"pid": pid, "cpu_avg": round(avg, 1), "samples": samples},
                    )
                    thrash_streak = 0
            else:
                thrash_streak = 0
        time.sleep(max(15, args.interval))
    print("jvm_watch_done", flush=True)


if __name__ == "__main__":
    main()
