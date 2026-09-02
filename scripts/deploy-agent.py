#!/usr/bin/env python3
"""CI -> live-app bridge, run on the host.

A team's CI builds an image inside its private DinD sandbox and pushes it to
its own Forgejo registry, then POSTs here. This service (which *is* on the host,
with access to the real Docker socket and the traefik-public network) actually
runs the container where Traefik can route to it. See CLAUDE.md for why this
split exists.

    POST /deploy   Authorization: Bearer <team deploy-token>
                   {"image": "<slug>.<domain>:<port>/team/app:<sha>", "port": 8080}
    GET  /healthz

No third-party dependencies — stdlib only. Bind loopback and front it with
Traefik / a unix socket / an SSH tunnel; do not expose it raw.

    DEPLOY_AGENT_ADDR=127.0.0.1  DEPLOY_AGENT_PORT=8787  ./scripts/deploy-agent.py
"""

from __future__ import annotations

import hmac
import json
import os
import re
import subprocess
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
STATE_DIR = REPO_ROOT / "state"
APP_TMPL = REPO_ROOT / "templates" / "app-compose.team.yml.tmpl"

ADDR = os.environ.get("DEPLOY_AGENT_ADDR", "127.0.0.1")
PORT = int(os.environ.get("DEPLOY_AGENT_PORT", "8787"))
MAX_BODY = 16 * 1024

_IMAGE_RE = re.compile(r"^[a-z0-9.-]+(:\d+)?/[a-z0-9._/-]+:[A-Za-z0-9._-]+$")
_SLUG_RE = re.compile(r"^[a-z0-9-]{1,40}$")


def _load_tokens() -> dict[str, str]:
    """token -> slug, read fresh each call so a new team needs no restart."""
    out: dict[str, str] = {}
    if not STATE_DIR.is_dir():
        return out
    for d in STATE_DIR.iterdir():
        tok = d / "deploy-token"
        if d.is_dir() and _SLUG_RE.match(d.name) and tok.is_file():
            out[tok.read_text().strip()] = d.name
    return out


def _team_from_bearer(header: str | None) -> str | None:
    if not header or not header.startswith("Bearer "):
        return None
    presented = header[7:].strip()
    for token, slug in _load_tokens().items():
        if hmac.compare_digest(token, presented):
            return slug
    return None


def _read_env(path: Path) -> dict[str, str]:
    env: dict[str, str] = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            env[k] = v
    return env


def _deploy(slug: str, image: str, port: int) -> dict:
    team_env = _read_env(STATE_DIR / slug / "team.env")
    base_domain = team_env["BASE_DOMAIN"]

    # The image must live in *this* team's own registry — the agent will not run
    # an arbitrary image on the shared network.
    expected_prefix = f"{slug}.{base_domain}:{team_env['TEAM_PORT']}/"
    if not image.startswith(expected_prefix):
        raise ValueError(f"image must be from {expected_prefix}")

    deploy_id = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    render_env = {
        **os.environ,
        "TEAM_SLUG": slug,
        "BASE_DOMAIN": base_domain,
        "APP_IMAGE": image,
        "APP_PORT": str(port),
        "DEPLOY_ID": deploy_id,
        "CPU_APP": os.environ.get("CPU_APP", "1.0"),
        "MEM_APP": os.environ.get("MEM_APP", "1g"),
    }
    rendered = STATE_DIR / slug / "app-compose.yml"
    vars_list = "${TEAM_SLUG} ${BASE_DOMAIN} ${APP_IMAGE} ${APP_PORT} ${DEPLOY_ID} ${CPU_APP} ${MEM_APP}"
    with rendered.open("w") as fh:
        subprocess.run(
            ["envsubst", vars_list], stdin=APP_TMPL.open(), stdout=fh,
            env=render_env, check=True,
        )

    subprocess.run(
        ["docker", "compose", "-p", f"team-{slug}-app", "-f", str(rendered),
         "up", "-d", "--pull", "always", "--remove-orphans"],
        check=True, env=render_env,
    )

    (STATE_DIR / slug / "last-activity").write_text(str(int(time.time())))
    return {"ok": True, "team": slug, "deploy_id": deploy_id,
            "url": f"https://{slug}.{base_domain}/"}


class Handler(BaseHTTPRequestHandler):
    server_version = "hackzone-deploy-agent"

    def _json(self, code: int, body: dict) -> None:
        payload = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/healthz":
            self._json(200, {"status": "ok"})
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/deploy":
            return self._json(404, {"error": "not found"})

        slug = _team_from_bearer(self.headers.get("Authorization"))
        if slug is None:
            return self._json(401, {"error": "bad or missing bearer token"})

        length = int(self.headers.get("content-length") or 0)
        if length > MAX_BODY:
            return self._json(413, {"error": "body too large"})
        try:
            req = json.loads(self.rfile.read(length) or b"{}")
        except ValueError:
            return self._json(400, {"error": "invalid JSON"})

        image = str(req.get("image", "")).strip()
        port = int(req.get("port", 8080))
        if not _IMAGE_RE.match(image):
            return self._json(400, {"error": "image must be host[:port]/path:tag"})
        if not (1 <= port <= 65535):
            return self._json(400, {"error": "port out of range"})

        try:
            result = _deploy(slug, image, port)
        except (ValueError, KeyError) as exc:
            return self._json(400, {"error": str(exc)})
        except subprocess.CalledProcessError as exc:
            return self._json(502, {"error": f"deploy failed: {exc}"})
        self._json(200, result)

    def log_message(self, fmt: str, *args) -> None:
        print(f"{self.address_string()} {fmt % args}", flush=True)


def main() -> None:
    print(f"deploy-agent on {ADDR}:{PORT}  ({len(_load_tokens())} teams registered)", flush=True)
    ThreadingHTTPServer((ADDR, PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
