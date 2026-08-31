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
SETTINGS="$ROOT/data/trend-ui-settings.json"
python3 - <<PY
import json
from pathlib import Path
from datetime import datetime
p = Path("$SETTINGS")
want = {
    "autoExecution": True,
    "liveExecution": False,
    "playbookId": "levels-profile-br-m5",
    "instrumentId": "BRU6",
    "updatedAt": datetime.now().strftime("%Y-%m-%dT%H:%M:%S"),
}
if p.exists():
    try:
        cur = json.loads(p.read_text(encoding="utf-8"))
        if isinstance(cur, dict) and set(cur) <= set(want) | {"updatedAt"}:
            want["instrumentId"] = cur.get("instrumentId") or want["instrumentId"]
            want["autoExecution"] = bool(cur.get("autoExecution", True))
            want["liveExecution"] = bool(cur.get("liveExecution", False))
    except Exception:
        pass
p.write_text(json.dumps(want, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("settings:", want["playbookId"], "live=", want["liveExecution"])
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
echo "НЕ крутить пад/knife. Цель: observePathToReal phase A → B → FORTS SL."
