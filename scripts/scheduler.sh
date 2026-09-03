# Node placement. Sourced by provision-zone.sh / zone.sh.
#
#   pick_node <need_cpus> <need_mem_gb> [require_label]
#
# Picks the active node with the most free CPU headroom that can fit the zone
# (and carries require_label, if given). Nodes are oversubscribed on CPU the
# same way a single host was — the quotas are limits, not reservations — but we
# still don't place a zone whose limits alone exceed a node's stated capacity.
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

_node_has_label() {
    [ -z "$2" ] && return 0
    case ",$(node_get "$1" LABELS)," in *",$2,"*) return 0 ;; *) return 1 ;; esac
}

pick_node() {
    local need_cpu="$1" need_mem="$2" label="${3:-}"
    local best="" best_free="-1"
    for name in $(list_nodes); do
        [ "$(node_get "$name" STATE)" = "active" ] || continue
        _node_has_label "$name" "$label" || continue
        local cap_cpu cap_mem
        cap_cpu="$(node_get "$name" CPUS)"; cap_mem="$(node_get "$name" MEM_GB)"
        # limits alone must not exceed the node
        awk "BEGIN{exit !($need_cpu <= $cap_cpu && $need_mem <= $cap_mem)}" || continue
        read -r ucpu _ _ <<<"$(node_alloc "$name")"
        local free; free=$(awk "BEGIN{print $cap_cpu - $ucpu}")
        if awk "BEGIN{exit !($free > $best_free)}"; then best="$name"; best_free="$free"; fi
    done
    [ -n "$best" ] || die "no active node can fit a zone needing ${need_cpu} cpu / ${need_mem}g${label:+ with label '$label'}"
    echo "$best"
}
