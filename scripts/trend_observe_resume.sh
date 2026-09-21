#!/usr/bin/env bash
# Resume Exclusive observation after Mac sleep/shutdown.
# Usage (from anywhere): bash IMOEX/scripts/trend_observe_resume.sh [YYYY-MM-DD]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

DAY="${1:-$(date +%F)}"
echo "== trend observe resume $DAY =="

# MEGA sync sometimes renames files to *(1).* — restore canonical names before trading.
python3 - <<'PY'
import shutil
from pathlib import Path
data = Path("data")
if data.is_dir():
    for dup in sorted(data.glob("*(1).*")):
        canon = data / dup.name.replace("(1)", "")
        if not canon.exists():
            shutil.copy2(dup, canon)
            print("restored", canon.name, "from", dup.name)
PY

# Keep Exclusive-only while positional H1 OOMs the JVM (restore both after phase A).
# Front month: prefer desk if UP; never restore expired BRU6 after 2026-08-31.
SETTINGS="$ROOT/data/trend-ui-settings.json"
python3 - <<'PY'
import json
import sys
from pathlib import Path
from datetime import datetime

sys.path.insert(0, str(Path("scripts").resolve()))
from trend_observe_auth import desk_request

p = Path("data/trend-ui-settings.json")
want = {
    "autoExecution": True,
    "liveExecution": False,
    "playbookId": "levels-profile-br-m5",
    "instrumentId": "BRV6",
    "updatedAt": datetime.now().strftime("%Y-%m-%dT%H:%M:%S"),
}
EXPIRED = {"BRU6"}  # last trade 2026-08-31

def desk_front():
    try:
        d = desk_request(
            "http://127.0.0.1:8080/api/trend/desk?playbook=levels-profile-br-m5",
            timeout=3,
        )
        s = d.get("situation") or {}
        ce = s.get("contractExpiry") if isinstance(s.get("contractExpiry"), dict) else {}
        for cand in (ce.get("secid"), d.get("instrument"), s.get("instrument")):
            if cand and str(cand).strip():
                return str(cand).strip().upper()
    except Exception:
        return None
    return None

if p.exists():
    try:
        cur = json.loads(p.read_text(encoding="utf-8"))
        if isinstance(cur, dict):
            prev = str(cur.get("instrumentId") or "").upper()
            if prev and prev not in EXPIRED:
                want["instrumentId"] = prev
            want["autoExecution"] = bool(cur.get("autoExecution", True))
            # hard: collect / NO_GO — never flip live on from stale UI
            want["liveExecution"] = False
            if cur.get("playbookId"):
                want["playbookId"] = cur.get("playbookId")
    except Exception:
        pass

front = desk_front()
if front and front not in EXPIRED:
    want["instrumentId"] = front
elif want["instrumentId"] in EXPIRED:
    want["instrumentId"] = "BRV6"

p.parent.mkdir(parents=True, exist_ok=True)
p.write_text(json.dumps(want, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("settings:", want["playbookId"], "instr=", want["instrumentId"], "live=", want["liveExecution"])
PY

if ! curl -sf -m 5 http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then
  echo "8080 down — daemon-starting Exclusive-only spring-boot (double-fork, survives agent shell)…"
  APP_LOG="${TMPDIR:-/tmp}/trinity-app-${DAY}.log"
  : >"$APP_LOG"
  python3 - <<PY
import os, sys
from pathlib import Path
root = Path("$ROOT")
log = Path("$APP_LOG")
if os.fork() > 0:
    raise SystemExit(0)
os.setsid()
if os.fork() > 0:
    os._exit(0)
os.chdir(root)
fd = os.open(str(log), os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o644)
os.dup2(fd, 1)
os.dup2(fd, 2)
os.close(fd)
dn = os.open(os.devnull, os.O_RDONLY)
os.dup2(dn, 0)
os.close(dn)
os.execvp("mvn", [
    "mvn", "-Poperator", "-pl", "trinity-app", "-am", "spring-boot:run", "-DskipTests",
    "-Dspring-boot.run.optimizedLaunch=false",
    "-Dspring-boot.run.jvmArguments=-Xms512m -Xmx1536m -XX:+UseG1GC",
])
PY
  ok=0
  for i in $(seq 1 90); do
    if curl -sf -m 3 http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then
      echo "JVM UP after ${i} tries (log $APP_LOG)"
      ok=1
      break
    fi
    if grep -q 'BUILD FAILURE' "$APP_LOG" 2>/dev/null && ! pgrep -f 'com.moex.trinity.TrinityApplication' >/dev/null; then
      echo "BUILD FAILURE — see $APP_LOG"
      break
    fi
    sleep 2
  done
  if [ "$ok" != 1 ]; then
    echo "8080 still down. Manual:"
    echo "  cd \"$ROOT\" && mvn -Poperator -pl trinity-app -am spring-boot:run -DskipTests \\"
    echo "    -Dspring-boot.run.jvmArguments='-Xms256m -Xmx1536m'"
    echo "(application.yml: playbook=levels-profile-br-m5, parallel-playbooks=false)"
    exit 2
  fi
fi

python3 "$ROOT/scripts/trend_observe_evening.py" "$DAY"
python3 "$ROOT/scripts/trend_observe_expectancy.py"

# Surface overnight / open fair-paper so agent doesn't miss carry
python3 - <<'PY'
import json
from pathlib import Path
st = Path("data/trend-fair-paper-state.json")
if not st.is_file():
    raise SystemExit
lanes = (json.loads(st.read_text()).get("lanes") or {})
op = (lanes.get("levels-profile-br-m5") or {}).get("open")
if op:
    side = "BUY" if op.get("buy") else "SELL"
    print(
        f"OVERNIGHT OPEN: {side} {op.get('mode')} {op.get('instrument')} "
        f"@ {op.get('avg')} qty={op.get('qty')}/{op.get('plannedQty')} "
        f"SL={op.get('sl')} TP1={op.get('tp1')} entry={op.get('entryTime')} "
        f"— не рестартить JVM без нужды; FORMING_BAR ведёт SL/TP"
    )
else:
    print("fair-paper: flat (no overnight open)")
PY

# seconds until next 18:02 local
SEC="$(python3 - <<'PY'
from datetime import datetime, timedelta
now = datetime.now()
t = now.replace(hour=18, minute=2, second=0, microsecond=0)
if t <= now:
    t += timedelta(days=1)
print(int((t - now).total_seconds()))
PY
)"
echo "Arm evening loop in ${SEC}s (~18:02). Keep this terminal open or run from Cursor agent."

# Durable desk poll → gate-observe JSONL (skips). --daemon double-forks so Cursor
# agent shell teardown cannot kill it. Journal/corpus still from JVM.
POLL_LOG="$ROOT/data/trend-desk-poll-$DAY.log"
pkill -f 'trend_observe_desk_poll.py' >/dev/null 2>&1 || true
sleep 0.3
python3 "$ROOT/scripts/trend_observe_desk_poll.py" \
  --daemon --log "$POLL_LOG" --seconds "$SEC" --interval 120
sleep 0.4
POLL_PID="$(pgrep -f 'trend_observe_desk_poll.py' | head -1 || true)"
echo "desk_poll_pid=${POLL_PID:-?} log=$POLL_LOG seconds=$SEC (until ~18:02)"

# JVM thrash/down watchdog → /tmp/trend-observe-agent-wake.log
WATCH_LOG="$ROOT/data/trend-jvm-watch-$DAY.log"
pkill -f 'trend_observe_jvm_watch.py' >/dev/null 2>&1 || true
sleep 0.2
python3 "$ROOT/scripts/trend_observe_jvm_watch.py" \
  --daemon --log "$WATCH_LOG" --seconds "$SEC" --interval 60
sleep 0.3
WATCH_PID="$(pgrep -f 'trend_observe_jvm_watch.py' | head -1 || true)"
echo "jvm_watch_pid=${WATCH_PID:-?} log=$WATCH_LOG"

# Durable HHMM wakes → /tmp/trend-observe-agent-wake.log (open / midday / evening)
WAKE_ARM="$ROOT/scripts/trend_observe_wake_arm.sh"
chmod +x "$WAKE_ARM" 2>/dev/null || true
pkill -f 'trend_observe_wake_arm.sh' >/dev/null 2>&1 || true
pkill -f '/tmp/trend_wake_arm.sh' >/dev/null 2>&1 || true
sleep 0.2
arm_wake() {
  local label="$1" hhmm="$2"
  # skip if already past (except evening — still useful same-minute)
  local now
  now="$(date +%H%M)"
  if [ "$now" -ge "$hhmm" ] && [ "$label" != "evening" ]; then
    echo "wake_arm skip ${label} (past ${hhmm})"
    return 0
  fi
  nohup "$WAKE_ARM" "$label" "$hhmm" "$DAY" >/dev/null 2>&1 &
  disown || true
  echo "wake_arm ${label}@${hhmm} pid=$!"
}
arm_wake open 1025
arm_wake midday 1330
arm_wake evening 1802

echo "LOG: $ROOT/data/trend-observe-path-log.json"
echo "DONE snapshot for $DAY — evening seal ~18:02: python3 scripts/trend_observe_evening.py"
echo "НЕ крутить пад/knife. Режим: collect corpus (Phase C NO_GO). ML/FORTS — только по явному go."
python3 "$ROOT/scripts/trend_corpus_inventory.py" || true
