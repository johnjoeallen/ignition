#!/usr/bin/env bash
# Reclaim team stacks idle longer than IDLE_TTL. Meant for cron:
#
#   */15 * * * *  IDLE_TTL=86400 /opt/hackzone/scripts/sweep-idle.sh >> /var/log/hackzone-sweep.log 2>&1
#
# "Idle" = state/<slug>/last-activity older than IDLE_TTL seconds. That file is
# bumped by provision-team.sh and by deploy-agent.py on every deploy. Add other
# signals (Gitea last-commit via API, etc.) here if a heartbeat isn't enough.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib.sh

: "${IDLE_TTL:=86400}"       # 24h
: "${DRY_RUN:=0}"
now=$(date +%s)

[ -d "$STATE_DIR" ] || exit 0
for dir in "$STATE_DIR"/*/; do
    [ -d "$dir" ] || continue
    slug=$(basename "$dir")
    valid_slug "$slug" || continue
    last=$(cat "$dir/last-activity" 2>/dev/null || echo 0)
    age=$(( now - last ))
    if [ "$age" -lt "$IDLE_TTL" ]; then
        echo "keep   $slug  (idle ${age}s)"
        continue
    fi
    echo "reclaim $slug  (idle ${age}s > ${IDLE_TTL}s)"
    [ "$DRY_RUN" = "1" ] || ./scripts/teardown-team.sh "$slug"
done
