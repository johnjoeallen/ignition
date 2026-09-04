#!/usr/bin/env bash
# render.sh CONFIG — fill the templates in ./templates/ from a single config
# file and write the results to ./out/. Blank secrets and WireGuard keys are
# generated and written back into CONFIG.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
tpl="$here/templates"
out="$here/out"
conf="${1:-}"

[ -n "$conf" ] && [ -f "$conf" ] || { echo "usage: $0 demo.conf" >&2; exit 2; }
[ -d "$tpl" ] || { echo "no templates/ — run ./gen-templates.sh first" >&2; exit 2; }

# ---- load CONFIG into an associative array ---------------------------------
declare -A C
while IFS= read -r line; do
    line="${line%$'\r'}"
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    [[ "$line" =~ ^[[:space:]]*$ ]] && continue
    [[ "$line" == *=* ]] || continue
    k="${line%%=*}"; v="${line#*=}"
    k="${k//[[:space:]]/}"
    v="${v%"${v##*[![:space:]]}"}"          # trim trailing whitespace only
    C["$k"]="$v"
done < "$conf"

get() { printf '%s' "${C[$1]:-}"; }
set_back() {                       # persist a generated value into CONFIG
    local k="$1" v="$2"
    C["$k"]="$v"
    if grep -qE "^[[:space:]]*$k=" "$conf"; then
        # escape & and | and \ for sed replacement
        local e; e="$(printf '%s' "$v" | sed -e 's/[\/&|]/\\&/g')"
        sed -i -E "s|^([[:space:]]*$k=).*|\1$e|" "$conf"
    else
        printf '%s=%s\n' "$k" "$v" >> "$conf"
    fi
}

# ---- generate blanks -----------------------------------------------------
gen_hex()  { openssl rand -hex "${1:-32}"; }
gen_b64()  { head -c "${1:-32}" /dev/urandom | base64; }

[ -n "$(get IGN_SECRET_KEY)" ]    || set_back IGN_SECRET_KEY    "$(gen_b64 32)"
[ -n "$(get POSTGRES_PASSWORD)" ] || set_back POSTGRES_PASSWORD "$(gen_hex 24)"

need_wg=0
for k in HETZNER_WG_PRIVKEY HETZNER_WG_PUBKEY SPITFIRE_WG_PRIVKEY SPITFIRE_WG_PUBKEY; do
    [ -n "$(get "$k")" ] || need_wg=1
done
if [ "$need_wg" = 1 ]; then
    command -v wg >/dev/null || { echo "need 'wg' to generate WireGuard keys (apt install wireguard-tools)" >&2; exit 1; }
    for host in HETZNER SPITFIRE; do
        if [ -z "$(get ${host}_WG_PRIVKEY)" ] || [ -z "$(get ${host}_WG_PUBKEY)" ]; then
            priv="$(wg genkey)"; pub="$(printf '%s' "$priv" | wg pubkey)"
            set_back "${host}_WG_PRIVKEY" "$priv"
            set_back "${host}_WG_PUBKEY"  "$pub"
        fi
    done
fi

# ---- provider credential block for acme.env ----------------------------
acme_body() {
    case "$(get ACME_DNS_PROVIDER)" in
        joker)
            printf 'JOKER_API_MODE=SVC\nJOKER_USERNAME=%s\nJOKER_PASSWORD=%s\nJOKER_PROPAGATION_TIMEOUT=1200\nJOKER_POLLING_INTERVAL=30\n' \
                "$(get JOKER_USERNAME)" "$(get JOKER_PASSWORD)" ;;
        desec)      printf 'DESEC_TOKEN=%s\n'       "$(get DESEC_TOKEN)" ;;
        cloudflare) printf 'CF_DNS_API_TOKEN=%s\n'  "$(get CF_DNS_API_TOKEN)" ;;
        *) echo "unknown ACME_DNS_PROVIDER: '$(get ACME_DNS_PROVIDER)'" >&2; exit 1 ;;
    esac
}
C[ACME_ENV_BODY]="$(acme_body)"
C[BASE_DOMAIN_RE]="$(get BASE_DOMAIN | sed 's/\./\\./g')"

# ---- required-field check --------------------------------------------
missing=()
req=(PUBLIC_IP BASE_DOMAIN ACME_EMAIL ACME_DNS_PROVIDER
     IGN_SMTP_HOST IGN_SMTP_USERNAME IGN_SMTP_PASSWORD IGN_SMTP_FROM)
for k in "${req[@]}"; do [ -n "$(get "$k")" ] || missing+=("$k"); done
case "$(get ACME_DNS_PROVIDER)" in
    joker)      for k in JOKER_USERNAME JOKER_PASSWORD; do [ -n "$(get "$k")" ] || missing+=("$k"); done ;;
    desec)      [ -n "$(get DESEC_TOKEN)" ]      || missing+=(DESEC_TOKEN) ;;
    cloudflare) [ -n "$(get CF_DNS_API_TOKEN)" ] || missing+=(CF_DNS_API_TOKEN) ;;
esac
if [ "${#missing[@]}" -gt 0 ]; then
    echo "CONFIG is missing: ${missing[*]}" >&2; exit 1
fi

# ---- substitute -----------------------------------------------------
rm -rf "$out"; mkdir -p "$out"
shopt -s nullglob
for f in "$tpl"/*; do
    name="$(basename "$f")"
    content="$(cat "$f"; printf x)"; content="${content%x}"   # keep trailing newlines
    # replace every <KEY> that we have a value for
    while [[ "$content" =~ \<([A-Z0-9_]+)\> ]]; do
        key="${BASH_REMATCH[1]}"
        if [ -z "${C[$key]+set}" ]; then
            echo "template $name references <$key> but CONFIG has no such key" >&2
            exit 1
        fi
        content="${content//"<$key>"/${C[$key]}}"
    done
    printf '%s' "$content" > "$out/$name"
done
chmod 600 "$out"/acme.env "$out"/*wg0.conf 2>/dev/null || true

# ---- placement guide ----------------------------------------------
cat > "$out/INSTALL.txt" <<EOF
Generated $(date -u +%FT%TZ). Put each file where it belongs:

PART 1 — spitfire  (run from the repo root of your ignition clone)
  ignition.env  ->  .env            (chmod 600)
  acme.env      ->  acme.env        (chmod 600)
  docker network create traefik-public
  docker volume  create ignition-dynamic
  mkdir -p ssh-empty
  docker compose --project-directory . -f templates/traefik-core-compose.yml up -d
  docker compose --project-directory . -f templates/ignition-control-compose.yml up -d
  ( --project-directory . makes compose read .env / acme.env / ssh-empty from
    the repo root, not templates/ )

  first run: grab the setup code and create the platform admin —
    docker compose --project-directory . -f templates/ignition-control-compose.yml \
      logs ignition-control | grep "IGNITION SETUP"
    then open  https://<BASE_DOMAIN>/setup

PART 2 — hetzner
  hetzner-wg0.conf           -> /etc/wireguard/wg0.conf   (chmod 600)
  hetzner-nginx-stream.conf  -> /etc/nginx/stream-ignition.conf
       + add  include /etc/nginx/stream-ignition.conf;  to nginx.conf (top level)
       + ports.conf:  Listen 443  ->  Listen 127.0.0.1:443
  firewall: allow inbound udp/$(get WG_PORT) if input policy is drop
  systemctl enable --now wg-quick@wg0 nginx

PART 2 — spitfire
  spitfire-wg0.conf  -> /etc/wireguard/wg0.conf   (chmod 600)
  systemctl enable --now wg-quick@wg0

DNS (at whoever serves $(get BASE_DOMAIN)):
  *.$(get BASE_DOMAIN).  A  $(get PUBLIC_IP)

Keep demo.conf safe — it now holds the generated secrets and WireGuard keys.
EOF

echo "rendered -> $out/"
ls -1 "$out"
