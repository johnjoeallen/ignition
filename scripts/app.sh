#!/usr/bin/env bash
# `ign app ...` — the platform admin's view of deployed apps.
#
#   ign app list
#   ign app show <zone> <name>
#   ign app rm   <zone> <name>     # stop and remove an app
#
# Apps are normally created and removed by a zone's CI (POST /deploy on the
# control plane). This is the override. App names are unique within a zone.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib.sh

cmd_list() {
    printf '%-14s %-20s %-12s %s\n' ZONE APP NODE IMAGE
    list_apps | while read -r s a; do
        printf '%-14s %-20s %-12s %s\n' \
            "$s" "$a" "$(app_get "$s" "$a" NODE)" "$(app_get "$s" "$a" IMAGE)"
    done
}

cmd_show() {
    [ $# -eq 2 ] || die "usage: ign app show <zone> <name>"
    app_exists "$1" "$2" || die "no such app: $1/$2"
    cat "$(app_file "$1" "$2")"
    echo "URL=https://$(app_host "$2" "$1")/"
}

cmd_rm() {
    [ $# -eq 2 ] || die "usage: ign app rm <zone> <name>"
    app_exists "$1" "$2" || die "no such app: $1/$2"
    local dh; dh="$(zone_docker_host "$1")"
    DOCKER_HOST="${dh:-}" docker compose -p "app-$1-$2" \
        -f "$(app_dir "$1")/$2-compose.yml" down -v --remove-orphans 2>/dev/null \
        || DOCKER_HOST="${dh:-}" docker rm -f "app-$1-$2" 2>/dev/null || true
    rm -f "$(app_file "$1" "$2")" "$(app_dir "$1")/$2-compose.yml"
    echo "removed app $1/$2"
}

sub="${1:-list}"; [ $# -gt 0 ] && shift || true
case "$sub" in
    list|ls) cmd_list ;;
    show)    cmd_show "$@" ;;
    rm)      cmd_rm "$@" ;;
    *) die "unknown: ign app $sub" ;;
esac
