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
import urllib.request
from pathlib import Path
from datetime import datetime

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
        with urllib.request.urlopen(
            "http://127.0.0.1:8080/api/trend/desk?playbook=levels-profile-br-m5",
            timeout=3,
        ) as r:
            d = json.loads(r.read().decode())
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
  echo "8080 down — start Exclusive-only spring-boot in another terminal:"
  echo "  cd \"$ROOT\" && mvn -Poperator -pl trinity-app -am spring-boot:run -DskipTests \\"
  echo "    -Dspring-boot.run.jvmArguments='-Xms256m -Xmx1536m'"
  echo "(application.yml already: playbook=levels-profile-br-m5, parallel-playbooks=false)"
  echo "Then re-run: bash scripts/trend_observe_resume.sh"
  exit 2
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
echo "LOG: $ROOT/data/trend-observe-path-log.json"
echo "DONE snapshot for $DAY — loop arm is agent's job if Mac was off overnight."
echo "НЕ крутить пад/knife. Режим: collect corpus (Phase C NO_GO). ML/FORTS — только по явному go."
python3 "$ROOT/scripts/trend_corpus_inventory.py" || true
