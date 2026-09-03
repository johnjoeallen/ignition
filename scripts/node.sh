#!/usr/bin/env bash
# `ign node ...` — the platform admin manages the hosts that run zone stacks.
#
#   ign node add <name> <docker-host> [--cpus N] [--mem NNg] [--labels a,b]
#   ign node list
#   ign node show <name>
#   ign node drain <name>      # stop scheduling new zones here
#   ign node undrain <name>
#   ign node rm <name>         # only when no zone is assigned
#
# <docker-host>: "local" for this host's daemon, or ssh://user@host, or
# tcp://host:2376 (with client certs configured out of band).
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib.sh
mkdir -p "$NODES_DIR"

cmd_add() {
    [ $# -ge 2 ] || die "usage: ign node add <name> <docker-host> [--cpus N] [--mem NNg] [--labels a,b]"
    local name="$1" host="$2"; shift 2
    valid_slug "$name" || die "node name must be [a-z0-9-]"
    node_exists "$name" && die "node $name already exists"
    local cpus=4 mem=8 labels=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --cpus)   cpus="$2"; shift 2 ;;
            --mem)    mem="${2%g}"; shift 2 ;;
            --labels) labels="$2"; shift 2 ;;
            *) die "unknown flag: $1" ;;
        esac
    done
    [ "$host" = "local" ] && host=""
    cat > "$(node_env "$name")" <<EOF
NAME=$name
DOCKER_HOST=$host
CPUS=$cpus
MEM_GB=$mem
LABELS=$labels
STATE=active
EOF
    echo "added node $name  (${cpus} cpus, ${mem}g, host='${host:-local}')"
    DOCKER_HOST="$host" docker info -f '  daemon: {{.ServerVersion}} on {{.OperatingSystem}}' 2>/dev/null \
        || echo "  warning: could not reach the daemon at '${host:-local}' yet"
}

cmd_list() {
    printf '%-14s %-10s %-26s %-14s %s\n' NODE STATE DOCKER_HOST 'ALLOC (cpu/mem)' ZONES
    for name in $(list_nodes); do
        read -r ucpu umem zn <<<"$(node_alloc "$name")"
        printf '%-14s %-10s %-26s %-14s %s\n' \
            "$name" "$(node_get "$name" STATE)" "$(node_get "$name" DOCKER_HOST | sed 's/^$/local/')" \
            "${ucpu}/$(node_get "$name" CPUS)  ${umem}/$(node_get "$name" MEM_GB)g" "$zn"
    done
}

cmd_show() {
    [ $# -eq 1 ] || die "usage: ign node show <name>"
    node_exists "$1" || die "no such node: $1"
    cat "$(node_env "$1")"
    read -r ucpu umem zn <<<"$(node_alloc "$1")"
    echo "ALLOCATED_CPUS=$ucpu"
    echo "ALLOCATED_MEM_GB=$umem"
    echo "ZONES=$(for s in $(list_zones); do [ "$(zone_get "$s" NODE)" = "$1" ] && printf '%s ' "$s"; done)"
}

cmd_state() {   # cmd_state <name> <active|draining>
    node_exists "$1" || die "no such node: $1"
    sed -i "s/^STATE=.*/STATE=$2/" "$(node_env "$1")"
    echo "$1 is now $2"
}

cmd_rm() {
    [ $# -eq 1 ] || die "usage: ign node rm <name>"
    node_exists "$1" || die "no such node: $1"
    for s in $(list_zones); do
        [ "$(zone_get "$s" NODE)" = "$1" ] && die "zone $s is still assigned to $1 — move or destroy it first"
    done
    rm -f "$(node_env "$1")"
    echo "removed node $1"
}

sub="${1:-list}"; [ $# -gt 0 ] && shift || true
case "$sub" in
    add)     cmd_add "$@" ;;
    list|ls) cmd_list ;;
    show)    cmd_show "$@" ;;
    drain)   cmd_state "${1:?node name}" draining ;;
    undrain) cmd_state "${1:?node name}" active ;;
    rm)      cmd_rm "$@" ;;
    *)       die "unknown: ign node $sub" ;;
esac
