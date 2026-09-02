#!/usr/bin/env bash
# Stand up one team's full stack: Gitea + private DinD + Actions runner, and
# register a deploy token so the team's CI can push a live app.
#
#   BASE_DOMAIN=hz.example.com ./scripts/provision-team.sh <slug> <index>
#
# Idempotent: re-running with the same slug/index re-renders and re-applies.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib.sh

# --- per-team resource quotas (override via env) ------------------------------
: "${CPU_GITEA:=1.0}"  "${MEM_GITEA:=1g}"
: "${CPU_DIND:=2.0}"   "${MEM_DIND:=4g}"
: "${CPU_RUNNER:=1.0}" "${MEM_RUNNER:=2g}"
: "${CPU_APP:=1.0}"    "${MEM_APP:=1g}"
: "${PORT_BASE:=30000}"
: "${APP_PORT:=8080}"        # the port the team's app is expected to listen on

need docker; need envsubst; need openssl
[ $# -eq 2 ] || die "usage: $0 <slug> <index>"
SLUG="$1"; INDEX="$2"
valid_slug "$SLUG" || die "slug must be [a-z0-9-], <=40 chars, no leading/trailing dash"
[ "$INDEX" -ge 0 ] 2>/dev/null || die "index must be a non-negative integer"
: "${BASE_DOMAIN:?set BASE_DOMAIN (e.g. hz.example.com)}"

export TEAM_SLUG="$SLUG"
export TEAM_PORT=$(( PORT_BASE + INDEX ))
export BASE_DOMAIN CPU_GITEA MEM_GITEA CPU_DIND MEM_DIND CPU_RUNNER MEM_RUNNER

S="$STATE_DIR/$SLUG"
mkdir -p "$S"
echo "==> team=$SLUG index=$INDEX  gitea=http://$SLUG.$BASE_DOMAIN:$TEAM_PORT/  app=https://$SLUG.$BASE_DOMAIN/"

# --- phase 1: gitea + dind ---------------------------------------------------
export RUNNER_TOKEN=""
render "$TEMPLATES/docker-compose.team.yml.tmpl" "$TEAM_TMPL_VARS" "$S/docker-compose.yml"
dc "$SLUG" up -d gitea dind

printf '    waiting for gitea'
for _ in $(seq 1 60); do
    state=$(dc "$SLUG" ps --format '{{.Service}} {{.Health}}' | awk '$1=="gitea"{print $2}')
    [ "$state" = "healthy" ] && { echo " ok"; break; }
    printf '.'; sleep 2
done
[ "${state:-}" = "healthy" ] || die "gitea did not become healthy"

# --- runner token (two-phase: gitea must be up first) ----------------------
RUNNER_TOKEN="$(dc "$SLUG" exec -T -u git gitea gitea actions generate-runner-token | tr -d '\r\n[:space:]')"
[ -n "$RUNNER_TOKEN" ] || die "empty runner registration token"
printf '%s\n' "$RUNNER_TOKEN" > "$S/runner-token"
export RUNNER_TOKEN

# --- phase 2: bring up the runner with the token --------------------------
render "$TEMPLATES/docker-compose.team.yml.tmpl" "$TEAM_TMPL_VARS" "$S/docker-compose.yml"
dc "$SLUG" up -d

# --- deploy token: CI -> deploy-agent bearer -----------------------------
if [ ! -s "$S/deploy-token" ]; then
    openssl rand -hex 32 > "$S/deploy-token"
fi
DEPLOY_TOKEN="$(cat "$S/deploy-token")"

# --- record everything teardown / deploy-agent need ----------------------
cat > "$S/team.env" <<EOF
TEAM_SLUG=$SLUG
TEAM_INDEX=$INDEX
TEAM_PORT=$TEAM_PORT
BASE_DOMAIN=$BASE_DOMAIN
APP_PORT=$APP_PORT
GITEA_URL=http://$SLUG.$BASE_DOMAIN:$TEAM_PORT/
APP_URL=https://$SLUG.$BASE_DOMAIN/
EOF
date +%s > "$S/last-activity"

cat <<EOF

  provisioned: $SLUG
    Gitea            http://$SLUG.$BASE_DOMAIN:$TEAM_PORT/
    Live app (once deployed)  https://$SLUG.$BASE_DOMAIN/
    Registry host    $SLUG.$BASE_DOMAIN:$TEAM_PORT
    Deploy token     $S/deploy-token   (give to CI as DEPLOY_TOKEN secret)
    Runner token     $S/runner-token

  Next: create an admin user in the Gitea UI, seed the starter repo with
  examples/.gitea-workflows-deploy.yml, and set repo vars/secrets
  (REGISTRY, DEPLOY_URL, GITEA_TOKEN, DEPLOY_TOKEN). See CLAUDE.md next tasks.
EOF
