#!/usr/bin/env bash
# `hz app ...` — the platform admin's view of deployed apps.
#
#   hz app list
#   hz app show <name>
#   hz app rm <name>        # stop and remove an app (any zone's)
#
# Apps are normally created and removed by a zone's CI (POST /deploy on the
# control plane). This is the override.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib.sh

cmd_list() {
    printf '%-24s %-14s %-12s %s\n' APP ZONE NODE IMAGE
    for a in $(list_apps); do
        printf '%-24s %-14s %-12s %s\n' \
            "$a" "$(app_get "$a" ZONE)" "$(app_get "$a" NODE)" "$(app_get "$a" IMAGE)"
    done
}

cmd_show() {
    [ $# -eq 1 ] || die "usage: hz app show <name>"
    app_exists "$1" || die "no such app: $1"
    cat "$(app_file "$1")"
    echo "URL=https://$1.apps.$(zone_get "$(app_get "$1" ZONE)" BASE_DOMAIN)/"
}

cmd_rm() {
    [ $# -eq 1 ] || die "usage: hz app rm <name>"
    app_exists "$1" || die "no such app: $1"
    local zone dh
    zone="$(app_get "$1" ZONE)"
    dh="$(zone_docker_host "$zone")"
    DOCKER_HOST="${dh:-}" docker compose -p "app-$1" -f "$APPS_DIR/$1-compose.yml" down -v --remove-orphans 2>/dev/null \
        || DOCKER_HOST="${dh:-}" docker rm -f "app-$1" 2>/dev/null || true
    rm -f "$(app_file "$1")" "$APPS_DIR/$1-compose.yml"
    echo "removed app $1"
}

sub="${1:-list}"; [ $# -gt 0 ] && shift || true
case "$sub" in
    list|ls) cmd_list ;;
    show)    cmd_show "$@" ;;
    rm)      cmd_rm "$@" ;;
    *) die "unknown: hz app $sub" ;;
esac
