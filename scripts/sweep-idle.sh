#!/usr/bin/env bash
# Reclaim zones idle longer than IDLE_TTL. Meant for cron:
#
#   */15 * * * *  IDLE_TTL=86400 /opt/hackzone/scripts/sweep-idle.sh >> /var/log/hackzone-sweep.log 2>&1
#
# "Idle" = state/zones/<slug>/last-activity older than IDLE_TTL seconds. That
# file is bumped by provision-zone.sh and by the control plane on every deploy.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib.sh

: "${IDLE_TTL:=86400}"       # 24h
: "${DRY_RUN:=0}"
now=$(date +%s)

for slug in $(list_zones); do
    last=$(cat "$(zone_dir "$slug")/last-activity" 2>/dev/null || echo 0)
    age=$(( now - last ))
    if [ "$age" -lt "$IDLE_TTL" ]; then
        echo "keep    $slug  (idle ${age}s)"
        continue
    fi
    echo "reclaim $slug  (idle ${age}s > ${IDLE_TTL}s)"
    [ "$DRY_RUN" = "1" ] || ./scripts/teardown-zone.sh "$slug"
done
