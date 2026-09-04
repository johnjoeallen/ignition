#!/usr/bin/env bash
# update-and-run.sh — pull the latest ignition + images and (re)start the
# stack on this box. Run from the repo root (where .env / acme.env live),
# e.g. on spitfire after a code change lands on main.
#
#   ./update-and-run.sh              # pull + restart both stacks
#   ./update-and-run.sh --no-pull    # just restart (recreate) with what's here
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
cd "$here"

TRAEFIK=(docker compose --project-directory . -f templates/traefik-core-compose.yml)
CONTROL=(docker compose --project-directory . -f templates/ignition-control-compose.yml)

do_pull=1
[ "${1:-}" = "--no-pull" ] && do_pull=0

log() { printf '\n\033[1m== %s ==\033[0m\n' "$*"; }

if [ ! -d .git ]; then
    echo "run this from the repo root (no .git here)" >&2
    exit 1
fi

if [ "$do_pull" = 1 ]; then
    log "git pull"
    git pull --ff-only
fi
echo "at $(git rev-parse --short HEAD) — $(git log -1 --format='%s')"

for f in .env acme.env; do
    [ -f "$f" ] || echo "warning: $f not found in $here — see demo/README.md" >&2
done

log "prerequisites (idempotent)"
docker network create traefik-public 2>/dev/null || true
docker volume create ignition-dynamic 2>/dev/null || true
mkdir -p ssh-empty

if [ "$do_pull" = 1 ]; then
    log "pull images"
    "${TRAEFIK[@]}" pull
    "${CONTROL[@]}" pull
fi

log "traefik-core up"
"${TRAEFIK[@]}" up -d

log "ignition-control up"
"${CONTROL[@]}" up -d

log "waiting for ignition-control to answer"
base_url="$(grep -E '^IGN_PUBLIC_URL=' .env 2>/dev/null | head -1 | cut -d= -f2-)"
for i in $(seq 1 30); do
    if docker exec ignition-control curl -fsS http://localhost:8790/actuator/health >/dev/null 2>&1; then
        echo "up"
        break
    fi
    sleep 2
done

log "status"
"${TRAEFIK[@]}" ps
"${CONTROL[@]}" ps

if setup_line="$("${CONTROL[@]}" logs ignition-control 2>&1 | grep 'IGNITION SETUP' | tail -1)"; then
    echo
    echo "$setup_line"
    echo "(first run only — open ${base_url:-https://admin.<BASE_DOMAIN>}/setup)"
fi
