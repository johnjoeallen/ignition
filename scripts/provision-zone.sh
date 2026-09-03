#!/usr/bin/env bash
# Stand up one zone's stack on a node: Forgejo + private DinD + Actions runner,
# a zone-admin account, and the tokens the control plane and CI need.
#
#   BASE_DOMAIN=hz.example.com ./scripts/provision-zone.sh <slug> [--node <name>] [--label <l>]
#
# With no --node, the scheduler picks the least-loaded active node that fits.
# Idempotent: re-running re-renders and re-applies on the same node.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib.sh
. scripts/scheduler.sh

# --- per-zone resource quotas (override via env) ----------------------------
: "${CPU_FORGEJO:=1.0}" "${MEM_FORGEJO:=1g}"
: "${CPU_DIND:=2.0}"    "${MEM_DIND:=4g}"
: "${CPU_RUNNER:=1.0}"  "${MEM_RUNNER:=2g}"
: "${CPU_APP:=1.0}"     "${MEM_APP:=1g}"
: "${APP_PORT:=8080}"
: "${RUNNER_CAPACITY:=4}"

need docker; need envsubst; need openssl; need od
[ $# -ge 1 ] || die "usage: $0 <slug> [--node <name>] [--label <l>]"
SLUG="$1"; shift
NODE=""; LABEL=""
while [ $# -gt 0 ]; do
    case "$1" in
        --node)  NODE="$2"; shift 2 ;;
        --label) LABEL="$2"; shift 2 ;;
        *) die "unknown flag: $1" ;;
    esac
done
valid_slug "$SLUG" || die "slug must be [a-z0-9-], <=40 chars"
: "${BASE_DOMAIN:?set BASE_DOMAIN (e.g. hz.example.com)}"

# footprint = sum of the limits (used for node capacity accounting)
strip_g() { echo "${1%g}"; }
ZONE_CPUS=$(awk "BEGIN{print $CPU_FORGEJO + $CPU_DIND + $CPU_RUNNER + $CPU_APP}")
ZONE_MEM_GB=$(awk "BEGIN{print $(strip_g "$MEM_FORGEJO") + $(strip_g "$MEM_DIND") + $(strip_g "$MEM_RUNNER") + $(strip_g "$MEM_APP")}")

S="$(zone_dir "$SLUG")"
mkdir -p "$S"

# --- node assignment -------------------------------------------------------
if zone_exists "$SLUG" && [ -z "$NODE" ]; then
    NODE="$(zone_get "$SLUG" NODE)"                 # keep the existing placement
fi
if [ -z "$NODE" ]; then
    NODE="$(pick_node "$ZONE_CPUS" "$ZONE_MEM_GB" "$LABEL")"
    echo "==> scheduler placed zone $SLUG on node $NODE"
else
    node_exists "$NODE" || die "no such node: $NODE (see: hz node list)"
fi
DH="$(node_get "$NODE" DOCKER_HOST)"
GIT_HOST="$(git_host "$SLUG")"

# zone.env early so `zc` knows the node for the rest of the script.
cat > "$S/zone.env" <<EOF
ZONE_SLUG=$SLUG
NODE=$NODE
BASE_DOMAIN=$BASE_DOMAIN
ZONE_CPUS=$ZONE_CPUS
ZONE_MEM_GB=$ZONE_MEM_GB
APP_PORT=$APP_PORT
GIT_HOST=$GIT_HOST
REGISTRY=$GIT_HOST
FORGEJO_URL=https://$GIT_HOST/
APPS_BASE=apps.$BASE_DOMAIN
EOF

export ZONE_SLUG="$SLUG"
export BASE_DOMAIN CPU_FORGEJO MEM_FORGEJO CPU_DIND MEM_DIND CPU_RUNNER MEM_RUNNER
render "$TEMPLATES/zone-compose.yml.tmpl" "$ZONE_TMPL_VARS" "$S/docker-compose.yml"

echo "==> zone=$SLUG  node=$NODE ('${DH:-local}')  forgejo=https://$GIT_HOST/  apps=*.apps.$BASE_DOMAIN"

# --- phase 1: forgejo + dind ---------------------------------------------
zc "$SLUG" up -d forgejo dind
printf '    waiting for forgejo'
for _ in $(seq 1 60); do
    st=$(zc "$SLUG" ps --format '{{.Service}} {{.Health}}' | awk '$1=="forgejo"{print $2}')
    [ "$st" = "healthy" ] && { echo " ok"; break; }
    printf '.'; sleep 2
done
[ "${st:-}" = "healthy" ] || die "forgejo did not become healthy"

# --- runner registration (two-phase: forgejo must exist) ----------------
[ -s "$S/runner-secret" ] || openssl rand -hex 20 > "$S/runner-secret"
SECRET="$(cat "$S/runner-secret")"; UUID="$(forgejo_uuid "$SECRET")"
zc "$SLUG" exec -T -u git forgejo \
    forgejo forgejo-cli actions register --keep-labels --name "zone-$SLUG" --secret "$SECRET" >/dev/null

cat > "$S/runner-config.yml" <<EOF
log: { level: info }
runner:
  file: /data/.runner
  capacity: ${RUNNER_CAPACITY}
  timeout: 3h
  labels:
    - "ubuntu-latest:docker://code.forgejo.org/oci/node:22-bookworm"
    - "docker-cli:docker://code.forgejo.org/oci/docker:cli"
container: { network: host, valid_volumes: [] }
server:
  connections:
    default: { url: "http://forgejo:3000", uuid: "${UUID}", token: "${SECRET}" }
EOF

# --- phase 2: runner up, then push its config into the volume -----------
zc "$SLUG" up -d
zc "$SLUG" cp "$S/runner-config.yml" runner:/data/config.yml
zc "$SLUG" restart runner

# --- zone-admin account + Forgejo API token -----------------------------
if [ ! -s "$S/zone-admin.txt" ]; then
    ADMIN_PW="$(openssl rand -base64 18)"
    zc "$SLUG" exec -T -u git forgejo \
        forgejo admin user create --admin --username zoneadmin \
        --password "$ADMIN_PW" --email "zoneadmin@$GIT_HOST" --must-change-password=false >/dev/null
    FJ_TOKEN="$(zc "$SLUG" exec -T -u git forgejo \
        forgejo admin user generate-access-token --username zoneadmin \
        --scopes all --raw | tr -d '\r\n[:space:]')"
    cat > "$S/zone-admin.txt" <<EOF
username=zoneadmin
password=$ADMIN_PW
forgejo_url=https://$GIT_HOST/
forgejo_token=$FJ_TOKEN
EOF
    chmod 600 "$S/zone-admin.txt"
fi

# --- tokens: zone-admin control token + CI deploy token -----------------
[ -s "$S/zone-token" ]   || openssl rand -hex 32 > "$S/zone-token"
[ -s "$S/deploy-token" ] || openssl rand -hex 32 > "$S/deploy-token"
date +%s > "$S/last-activity"

cat <<EOF

  provisioned zone: $SLUG   (node $NODE)
    Forgejo             https://$GIT_HOST/
    Apps               https://<name>.apps.$BASE_DOMAIN/   (per app the zone deploys)
    Zone-admin login    state/zones/$SLUG/zone-admin.txt    (Forgejo admin: users, repos, ...)
    Zone control token  state/zones/$SLUG/zone-token        (admin.$BASE_DOMAIN zone view)
    CI deploy token     state/zones/$SLUG/deploy-token      (DEPLOY_TOKEN secret)

  DNS: $GIT_HOST must resolve to node $NODE's host (covered by *.git.$BASE_DOMAIN
  where zones share a node; per-record where they don't).
EOF
