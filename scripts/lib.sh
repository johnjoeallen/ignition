# Shared helpers for the hackzone scripts. Sourced, not executed.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMPLATES="$REPO_ROOT/templates"
STATE_DIR="$REPO_ROOT/state"

# The full set of vars the templates reference. envsubst is told exactly these
# so a literal `$foo` in a compose file is left untouched.
TEAM_TMPL_VARS='${TEAM_SLUG} ${BASE_DOMAIN} ${TEAM_PORT} ${RUNNER_TOKEN} ${CPU_GITEA} ${MEM_GITEA} ${CPU_DIND} ${MEM_DIND} ${CPU_RUNNER} ${MEM_RUNNER}'
APP_TMPL_VARS='${TEAM_SLUG} ${BASE_DOMAIN} ${APP_IMAGE} ${APP_PORT} ${DEPLOY_ID} ${CPU_APP} ${MEM_APP}'

die() { echo "error: $*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"; }

valid_slug() {
    case "$1" in
        *[!a-z0-9-]* | -* | *- | "") return 1 ;;
        *) [ "${#1}" -le 40 ] ;;
    esac
}

render() {   # render <template> <vars> <out>
    envsubst "$2" < "$1" > "$3"
}

dc() {   # docker compose for a team's own stack
    local slug="$1"; shift
    docker compose -p "team-$slug" -f "$STATE_DIR/$slug/docker-compose.yml" "$@"
}
