# Shared helpers for the hackzone scripts. Sourced, not executed.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMPLATES="$REPO_ROOT/templates"
STATE_DIR="$REPO_ROOT/state"
NODES_DIR="$STATE_DIR/nodes"
ZONES_DIR="$STATE_DIR/zones"

# The full set of vars the templates reference. envsubst is told exactly these
# so a literal `$foo` in a compose file is left untouched.
ZONE_TMPL_VARS='${ZONE_SLUG} ${BASE_DOMAIN} ${CPU_FORGEJO} ${MEM_FORGEJO} ${CPU_DIND} ${MEM_DIND} ${CPU_RUNNER} ${MEM_RUNNER}'
APP_TMPL_VARS='${APP_NAME} ${ZONE_SLUG} ${BASE_DOMAIN} ${APP_IMAGE} ${APP_PORT} ${DEPLOY_ID} ${CPU_APP} ${MEM_APP}'

# --- domain scheme -------------------------------------------------------
# BASE_DOMAIN is the apex (e.g. example.com). Each zone owns the subdomain
# <slug>.<BASE_DOMAIN>, and every service for that zone hangs off it:
#   git.<slug>.<BASE_DOMAIN>          the zone's Forgejo (git + PRs + Actions + registry)
#   admin.<slug>.<BASE_DOMAIN>        the zone admin's control-plane view
#   <app>.apps.<slug>.<BASE_DOMAIN>   a deployed app (name unique within the zone)
# The platform admin's view is at admin.<BASE_DOMAIN> (the apex).
zone_domain()  { echo "$1.${BASE_DOMAIN:?set BASE_DOMAIN}"; }
git_host()     { echo "git.$(zone_domain "$1")"; }
zadmin_host()  { echo "admin.$(zone_domain "$1")"; }
app_host()     { echo "$1.apps.$(zone_domain "$2")"; }   # app_host <app> <slug>

die() { echo "error: $*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"; }

valid_slug() {
    case "$1" in
        *[!a-z0-9-]* | -* | *- | "") return 1 ;;
        *) [ "${#1}" -le 40 ] ;;
    esac
}

render() { envsubst "$2" < "$1" > "$3"; }   # render <template> <vars> <out>

# --- nodes -----------------------------------------------------------------
# A node is state/nodes/<name>.env with: DOCKER_HOST, CPUS, MEM_GB, LABELS, STATE
node_env() { echo "$NODES_DIR/$1.env"; }
node_exists() { [ -f "$(node_env "$1")" ]; }

node_get() {   # node_get <name> <KEY>
    grep -E "^$2=" "$(node_env "$1")" 2>/dev/null | head -1 | cut -d= -f2-
}

list_nodes() {
    [ -d "$NODES_DIR" ] || return 0
    for f in "$NODES_DIR"/*.env; do [ -e "$f" ] || continue; basename "$f" .env; done
}

# --- zones ---------------------------------------------------------------
zone_dir() { echo "$ZONES_DIR/$1"; }
zone_exists() { [ -f "$(zone_dir "$1")/zone.env" ]; }

zone_get() {   # zone_get <slug> <KEY>
    grep -E "^$2=" "$(zone_dir "$1")/zone.env" 2>/dev/null | head -1 | cut -d= -f2-
}

list_zones() {
    [ -d "$ZONES_DIR" ] || return 0
    for d in "$ZONES_DIR"/*/; do [ -f "$d/zone.env" ] || continue; basename "$d"; done
}

# CPU / memory / zone-count currently allocated to a node.
node_alloc() {   # echoes: "<cpus_used> <mem_gb_used> <zone_count>"
    local node="$1" cpus=0 mem=0 n=0 slug
    for slug in $(list_zones); do
        [ "$(zone_get "$slug" NODE)" = "$node" ] || continue
        cpus=$(awk "BEGIN{print $cpus + $(zone_get "$slug" ZONE_CPUS 2>/dev/null || echo 0)}")
        mem=$(awk "BEGIN{print $mem + $(zone_get "$slug" ZONE_MEM_GB 2>/dev/null || echo 0)}")
        n=$((n + 1))
    done
    echo "$cpus $mem $n"
}

# The DOCKER_HOST for a zone's assigned node ("" = the local daemon).
zone_docker_host() {
    local node; node="$(zone_get "$1" NODE)"
    [ -n "$node" ] && node_get "$node" DOCKER_HOST || true
}

# docker compose for a zone's stack, on its assigned node.
zc() {
    local slug="$1"; shift
    local dh; dh="$(zone_docker_host "$slug")"
    DOCKER_HOST="${dh:-}" docker compose \
        -p "zone-$slug" -f "$(zone_dir "$slug")/docker-compose.yml" "$@"
}

# --- apps ---------------------------------------------------------------
# An app belongs to a zone: state/zones/<slug>/apps/<name>.env with NODE,
# IMAGE, PORT, DEPLOY_ID. Names are unique within a zone. Compose project /
# container: app-<slug>-<name>.
app_dir()      { echo "$(zone_dir "$1")/apps"; }
app_file()     { echo "$(app_dir "$1")/$2.env"; }              # app_file <slug> <name>
app_exists()   { [ -f "$(app_file "$1" "$2")" ]; }
app_get()      { grep -E "^$3=" "$(app_file "$1" "$2")" 2>/dev/null | head -1 | cut -d= -f2-; }
zone_apps()    { local d; d="$(app_dir "$1")"; [ -d "$d" ] || return 0
                 for f in "$d"/*.env; do [ -e "$f" ] || continue; basename "$f" .env; done; }
list_apps()    { local s a; for s in $(list_zones); do for a in $(zone_apps "$s"); do echo "$s $a"; done; done; }

# Forgejo derives a runner's UUID from the first 16 chars of the 40-hex shared
# secret: those chars, as raw bytes, hex-encoded, formatted 8-4-4-4-12.
forgejo_uuid() {
    local h; h=$(printf '%s' "${1:0:16}" | od -An -tx1 | tr -d ' \n')
    printf '%s-%s-%s-%s-%s' "${h:0:8}" "${h:8:4}" "${h:12:4}" "${h:16:4}" "${h:20:12}"
}
