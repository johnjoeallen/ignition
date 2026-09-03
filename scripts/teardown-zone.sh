#!/usr/bin/env bash
# Tear down one zone's full stack — Forgejo/DinD/runner AND the live app — on
# its assigned node, and remove its state. Everything is prefixed zone-<slug>.
#
#   ./scripts/teardown-zone.sh <slug> [--keep-state]
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib.sh

need docker
[ $# -ge 1 ] || die "usage: $0 <slug> [--keep-state]"
SLUG="$1"
valid_slug "$SLUG" || die "bad slug"
S="$(zone_dir "$SLUG")"
DH="$(zone_docker_host "$SLUG")"

echo "==> tearing down zone-$SLUG  (node $(zone_get "$SLUG" NODE || echo '?'))"

DOCKER_HOST="${DH:-}" docker compose -p "zone-$SLUG-app" down -v --remove-orphans 2>/dev/null || true

if [ -f "$S/docker-compose.yml" ]; then
    zc "$SLUG" down -v --remove-orphans
else
    DOCKER_HOST="${DH:-}" docker ps -aq --filter "name=^zone-$SLUG-" | xargs -r -I{} sh -c "DOCKER_HOST='${DH:-}' docker rm -f {}"
fi

if [ "${2:-}" = "--keep-state" ]; then
    echo "    state kept at $S"
else
    rm -rf "$S"
    echo "    state removed"
fi
