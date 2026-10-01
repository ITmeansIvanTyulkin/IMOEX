#!/usr/bin/env bash
# Durable HHMM wake → /tmp/trend-observe-agent-wake.log (survives agent shell).
# Usage: trend_observe_wake_arm.sh LABEL HHMM [DAY]
# Example: trend_observe_wake_arm.sh midday 1330 2026-09-21
set -euo pipefail
LABEL="${1:?label}"
TARGET="${2:?HHMM}"
DAY="${3:-$(date +%F)}"
LOG="${TREND_OBSERVE_WAKE_LOG:-/tmp/trend-observe-agent-wake.log}"

# already past → fire once and exit
now="$(date +%H%M)"
if [ "$now" -ge "$TARGET" ]; then
  printf '%s\n' "AGENT_LOOP_WAKE_${LABEL} {\"at\": \"$(date +%Y-%m-%dT%H:%M:%S%z)\", \"kind\": \"${LABEL}\", \"day\": \"${DAY}\", \"prompt\": \"Goal observePathToReal: ${LABEL} ${DAY}. health/desk/poll/CPU; evening→seal. НЕ крути пад. Phase C NO_GO.\"}" >>"$LOG"
  exit 0
fi

while true; do
  now="$(date +%H%M)"
  if [ "$now" -ge "$TARGET" ]; then
    printf '%s\n' "AGENT_LOOP_WAKE_${LABEL} {\"at\": \"$(date +%Y-%m-%dT%H:%M:%S%z)\", \"kind\": \"${LABEL}\", \"day\": \"${DAY}\", \"prompt\": \"Goal observePathToReal: ${LABEL} ${DAY}. health/desk/poll/CPU; evening→seal. НЕ крути пад. Phase C NO_GO.\"}" >>"$LOG"
    exit 0
  fi
  sleep 20
done
