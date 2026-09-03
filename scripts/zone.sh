#!/usr/bin/env bash
# `ign zone ...` — the platform admin manages zones and their node placement.
#
#   ign zone create <slug> [--node <name>] [--label <l>]
#   ign zone list
#   ign zone show <slug>
#   ign zone status <slug>            # live container health on the zone's node
#   ign zone move <slug> --node <n>   # recreate the stack on a different node
#   ign zone destroy <slug>
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib.sh

cmd_create() {
    [ $# -ge 1 ] || die "usage: ign zone create <slug> [--node <name>] [--label <l>]"
    exec ./scripts/provision-zone.sh "$@"
}

cmd_list() {
    printf '%-16s %-12s %-10s %-5s %s\n' ZONE NODE 'CPU/MEMg' APPS FORGEJO
    for slug in $(list_zones); do
        printf '%-16s %-12s %-10s %-5s %s\n' \
            "$slug" "$(zone_get "$slug" NODE)" \
            "$(zone_get "$slug" ZONE_CPUS)/$(zone_get "$slug" ZONE_MEM_GB)" \
            "$(zone_apps "$slug" | wc -l | tr -d ' ')" \
            "$(zone_get "$slug" FORGEJO_URL)"
    done
}

cmd_show() {
    [ $# -eq 1 ] || die "usage: ign zone show <slug>"
    zone_exists "$1" || die "no such zone: $1"
    cat "$(zone_dir "$1")/zone.env"
}

cmd_status() {
    [ $# -eq 1 ] || die "usage: ign zone status <slug>"
    zone_exists "$1" || die "no such zone: $1"
    local dh; dh="$(zone_docker_host "$1")"
    echo "zone $1  on node $(zone_get "$1" NODE)   forgejo $(zone_get "$1" FORGEJO_URL)"
    zc "$1" ps --format 'table {{.Service}}\t{{.Status}}\t{{.Health}}' || true
    echo "apps:"
    for a in $(zone_apps "$1"); do
        printf '  %-32s %s\n' "https://$(app_host "$a" "$1")/" \
            "$(DOCKER_HOST="${dh:-}" docker compose -p "app-$1-$a" ps --format '{{.State}}' 2>/dev/null | paste -sd, -)"
    done
}

cmd_move() {
    [ $# -ge 3 ] || die "usage: ign zone move <slug> --node <name>"
    local slug="$1"; shift
    [ "$1" = "--node" ] || die "expected --node"
    local target="$2"
    zone_exists "$slug" || die "no such zone: $slug"
    node_exists "$target" || die "no such node: $target"
    local from; from="$(zone_get "$slug" NODE)"
    [ "$from" = "$target" ] && { echo "already on $target"; exit 0; }
    echo "==> moving zone $slug: $from -> $target"
    echo "    data volumes do not follow — the zone is rebuilt empty."
    [ -n "$(zone_apps "$slug")" ] && echo "    its apps ($(zone_apps "$slug" | paste -sd, -)) stay on $from until CI redeploys them."
    ./scripts/teardown-zone.sh "$slug" --keep-state
    rm -f "$(zone_dir "$slug")/docker-compose.yml" "$(zone_dir "$slug")/runner-secret" \
          "$(zone_dir "$slug")/zone-admin.txt"
    sed -i "s/^NODE=.*/NODE=$target/" "$(zone_dir "$slug")/zone.env"
    BASE_DOMAIN="$(zone_get "$slug" BASE_DOMAIN)" ./scripts/provision-zone.sh "$slug" --node "$target"
}

cmd_destroy() {
    [ $# -eq 1 ] || die "usage: ign zone destroy <slug>"
    exec ./scripts/teardown-zone.sh "$1"
}

sub="${1:-list}"; [ $# -gt 0 ] && shift || true
case "$sub" in
    create)   cmd_create "$@" ;;
    list|ls)  cmd_list ;;
    show)     cmd_show "$@" ;;
    status)   cmd_status "$@" ;;
    move)     cmd_move "$@" ;;
    destroy|rm) cmd_destroy "$@" ;;
    *) die "unknown: ign zone $sub" ;;
esac
