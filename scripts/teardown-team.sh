#!/usr/bin/env bash
# Tear down one team's full stack — Forgejo/DinD/runner AND the live app — and
# remove its state. Everything is prefixed team-<slug>, so this is complete.
#
#   ./scripts/teardown-team.sh <slug> [--keep-state]
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib.sh

need docker
[ $# -ge 1 ] || die "usage: $0 <slug> [--keep-state]"
SLUG="$1"
valid_slug "$SLUG" || die "bad slug"
S="$STATE_DIR/$SLUG"

echo "==> tearing down team-$SLUG"

# Live app (deploy-agent's compose project).
docker compose -p "team-$SLUG-app" down -v --remove-orphans 2>/dev/null || true

# Core team stack.
if [ -f "$S/docker-compose.yml" ]; then
    dc "$SLUG" down -v --remove-orphans
else
    # State already gone — best-effort cleanup by label/prefix.
    docker ps -aq --filter "name=^team-$SLUG-" | xargs -r docker rm -f
    docker volume ls -q --filter "name=^team-$SLUG-" | xargs -r docker volume rm
    docker network ls -q --filter "name=^team-$SLUG$" | xargs -r docker network rm
fi

if [ "${2:-}" = "--keep-state" ]; then
    echo "    state kept at $S"
else
    rm -rf "$S"
    echo "    state removed"
fi
