#!/usr/bin/env python3
"""hackzone control plane — the admin surface above the zones.

Two roles, one service:

* **Platform admin** (bearer = ``HZ_ADMIN_TOKEN``) sees every node and zone.
* **Zone admin** (bearer = that zone's ``state/zones/<slug>/zone-token``)
  manages *their* zone only: add/remove Forgejo users, create repos, restart
  the runner, see build/deploy status. Actions proxy the zone's own Forgejo
  API with the admin token minted at provisioning time.

Also carries the CI → live-app bridge (``POST /deploy``, bearer = the zone's
``deploy-token``), node-aware: it applies the app compose on the zone's
assigned node.

Stdlib only. Bind loopback and front it with Traefik / a tunnel.

    HZ_ADMIN_TOKEN=...  HZ_CONTROL_ADDR=127.0.0.1  HZ_CONTROL_PORT=8790  ./control/hz-control.py
"""

from __future__ import annotations

import hmac
import html
import json
import os
import ssl
import subprocess
import time
import urllib.error
import urllib.request
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

REPO = Path(__file__).resolve().parent.parent
STATE = REPO / "state"
NODES = STATE / "nodes"
ZONES = STATE / "zones"
APP_TMPL = REPO / "templates" / "app-compose.zone.yml.tmpl"

ADDR = os.environ.get("HZ_CONTROL_ADDR", "127.0.0.1")
PORT = int(os.environ.get("HZ_CONTROL_PORT", "8790"))
ADMIN_TOKEN = os.environ.get("HZ_ADMIN_TOKEN", "")
MAX_BODY = 64 * 1024

# Forgejo instances use per-zone LE certs; skip verification only if asked to
# (useful before DNS/certs settle).
_TLS = ssl._create_unverified_context() if os.environ.get("HZ_INSECURE_TLS") else None


# --------------------------------------------------------------------------- io
def _envfile(path: Path) -> dict[str, str]:
    out: dict[str, str] = {}
    if path.is_file():
        for line in path.read_text().splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                out[k] = v
    return out


def zones() -> list[str]:
    return sorted(d.name for d in ZONES.iterdir() if (d / "zone.env").is_file()) if ZONES.is_dir() else []


def nodes() -> list[str]:
    return sorted(f.stem for f in NODES.glob("*.env")) if NODES.is_dir() else []


def zone_env(slug: str) -> dict[str, str]:
    return _envfile(ZONES / slug / "zone.env")


def node_env(name: str) -> dict[str, str]:
    return _envfile(NODES / f"{name}.env")


def _read(slug: str, name: str) -> str:
    p = ZONES / slug / name
    return p.read_text().strip() if p.is_file() else ""


def node_alloc(name: str) -> tuple[float, float, int]:
    cpu = mem = 0.0
    n = 0
    for s in zones():
        if zone_env(s).get("NODE") == name:
            cpu += float(zone_env(s).get("ZONE_CPUS", 0) or 0)
            mem += float(zone_env(s).get("ZONE_MEM_GB", 0) or 0)
            n += 1
    return cpu, mem, n


# ------------------------------------------------------------------------ auth
def _bearer(headers) -> str:
    h = headers.get("Authorization", "")
    return h[7:].strip() if h.startswith("Bearer ") else ""


def _token_of(headers, cookies: SimpleCookie) -> str:
    return _bearer(headers) or (cookies["hz_token"].value if "hz_token" in cookies else "")


def identify(token: str) -> tuple[str, str | None]:
    """('platform', None) | ('zone', slug) | ('deploy', slug) | ('anon', None)"""
    if token and ADMIN_TOKEN and hmac.compare_digest(token, ADMIN_TOKEN):
        return "platform", None
    for s in zones():
        if token and hmac.compare_digest(token, _read(s, "zone-token") or "\0"):
            return "zone", s
        if token and hmac.compare_digest(token, _read(s, "deploy-token") or "\0"):
            return "deploy", s
    return "anon", None


# -------------------------------------------------------------------- forgejo
def forgejo(slug: str, method: str, path: str, body: dict | None = None) -> tuple[int, object]:
    admin = _envfile(ZONES / slug / "zone-admin.txt")
    base = admin.get("forgejo_url", zone_env(slug).get("FORGEJO_URL", "")).rstrip("/")
    tok = admin.get("forgejo_token", "")
    if not base or not tok:
        return 503, {"error": "zone has no Forgejo admin token yet"}
    req = urllib.request.Request(
        f"{base}/api/v1{path}", method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Authorization": f"token {tok}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=15, context=_TLS) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"error": raw.decode("utf-8", "replace")[:400]}
    except urllib.error.URLError as e:
        return 502, {"error": str(e)}


# --------------------------------------------------------------------- docker
def _dc_env(slug: str) -> dict[str, str]:
    dh = node_env(zone_env(slug).get("NODE", "")).get("DOCKER_HOST", "")
    env = {**os.environ}
    if dh:
        env["DOCKER_HOST"] = dh
    else:
        env.pop("DOCKER_HOST", None)
    return env


def zc(slug: str, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["docker", "compose", "-p", f"zone-{slug}",
         "-f", str(ZONES / slug / "docker-compose.yml"), *args],
        env=_dc_env(slug), capture_output=True, text=True, check=False,
    )


def zone_status(slug: str) -> dict:
    r = zc(slug, "ps", "--format", "{{.Service}}={{.State}}")
    stack = dict(x.split("=", 1) for x in r.stdout.split() if "=" in x)
    app = subprocess.run(
        ["docker", "compose", "-p", f"zone-{slug}-app", "ps", "--format", "{{.State}}"],
        env=_dc_env(slug), capture_output=True, text=True, check=False,
    ).stdout.strip()
    z = zone_env(slug)
    return {
        "slug": slug, "node": z.get("NODE"), "forgejo_url": z.get("FORGEJO_URL"),
        "app_url": z.get("APP_URL"), "stack": stack,
        "app": app or "not deployed",
        "last_activity": _read(slug, "last-activity"),
    }


def deploy(slug: str, image: str, port: int) -> dict:
    z = zone_env(slug)
    registry = z.get("REGISTRY", f"git.{slug}.{z.get('BASE_DOMAIN','')}")
    if not image.startswith(registry + "/"):
        raise ValueError(f"image must be from {registry}/")
    deploy_id = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    env = {
        **_dc_env(slug), "ZONE_SLUG": slug, "BASE_DOMAIN": z["BASE_DOMAIN"],
        "APP_IMAGE": image, "APP_PORT": str(port), "DEPLOY_ID": deploy_id,
        "CPU_APP": os.environ.get("CPU_APP", "1.0"), "MEM_APP": os.environ.get("MEM_APP", "1g"),
    }
    rendered = ZONES / slug / "app-compose.yml"
    vlist = "${ZONE_SLUG} ${BASE_DOMAIN} ${APP_IMAGE} ${APP_PORT} ${DEPLOY_ID} ${CPU_APP} ${MEM_APP}"
    with rendered.open("w") as fh:
        subprocess.run(["envsubst", vlist], stdin=APP_TMPL.open(), stdout=fh, env=env, check=True)
    subprocess.run(
        ["docker", "compose", "-p", f"zone-{slug}-app", "-f", str(rendered),
         "up", "-d", "--pull", "always", "--remove-orphans"],
        env=env, check=True, capture_output=True, text=True,
    )
    (ZONES / slug / "last-activity").write_text(str(int(time.time())))
    return {"ok": True, "zone": slug, "deploy_id": deploy_id, "url": z.get("APP_URL")}


# ----------------------------------------------------------------------- html
CSS = """
body{font:15px/1.5 system-ui,sans-serif;max-width:60rem;margin:2rem auto;padding:0 1rem;color:#111}
h1{font-size:1.5rem}h2{font-size:1.1rem;margin-top:1.8rem}
table{border-collapse:collapse;width:100%;margin:.6rem 0}th,td{border:1px solid #ddd;padding:.35rem .6rem;text-align:left}
th{background:#f4f4f4}code{background:#f2f4f7;padding:.1rem .35rem;border-radius:3px}
form{margin:.6rem 0;display:flex;gap:.5rem;flex-wrap:wrap;align-items:end}
label{display:flex;flex-direction:column;font-size:.8rem;color:#555}
input,select{font:inherit;padding:.35rem}button{font:inherit;padding:.4rem .9rem;cursor:pointer}
.msg{background:#e6f4ea;border:1px solid #b7dfc4;padding:.5rem .8rem;border-radius:4px;margin:1rem 0}
.err{background:#fdeaea;border-color:#f0b7b7}
.card{border:1px solid #ddd;border-radius:6px;padding:1rem 1.2rem;margin:1rem 0}
a{color:#0a7d6b}
"""


def page(title: str, body: str) -> bytes:
    return (
        f"<!doctype html><meta charset=utf-8><title>{html.escape(title)}</title>"
        f"<style>{CSS}</style><h1>hackzone · {html.escape(title)}</h1>{body}"
    ).encode()


def login_page(msg: str = "") -> bytes:
    m = f"<div class='msg err'>{html.escape(msg)}</div>" if msg else ""
    return page("sign in", m + """
      <form method=post action=/ui/login>
        <label>Token<input name=token type=password autofocus size=44></label>
        <button>Sign in</button>
      </form>
      <p>Platform admins use <code>HZ_ADMIN_TOKEN</code>; zone admins use their
      zone token (<code>state/zones/&lt;slug&gt;/zone-token</code>).</p>""")


def platform_page(msg: str) -> bytes:
    rows = ""
    for n in nodes():
        e = node_env(n)
        uc, um, zn = node_alloc(n)
        rows += (f"<tr><td>{n}</td><td>{e.get('STATE')}</td>"
                 f"<td><code>{html.escape(e.get('DOCKER_HOST') or 'local')}</code></td>"
                 f"<td>{uc:g}/{e.get('CPUS')} cpu · {um:g}/{e.get('MEM_GB')}g</td><td>{zn}</td></tr>")
    zrows = ""
    for s in zones():
        z = zone_env(s)
        zrows += (f"<tr><td>{s}</td><td>{z.get('NODE')}</td>"
                  f"<td>{z.get('ZONE_CPUS')}cpu/{z.get('ZONE_MEM_GB')}g</td>"
                  f"<td><a href='{html.escape(z.get('FORGEJO_URL',''))}'>forge</a> · "
                  f"<a href='{html.escape(z.get('APP_URL',''))}'>app</a></td></tr>")
    return page("platform", (f"<div class='msg'>{html.escape(msg)}</div>" if msg else "") + f"""
      <h2>Nodes</h2>
      <table><tr><th>node<th>state<th>docker host<th>allocated<th>zones</tr>{rows}</table>
      <p><code>hz node add &lt;name&gt; &lt;docker-host&gt;</code> to register a node,
         <code>hz zone create &lt;slug&gt;</code> to place a zone.</p>
      <h2>Zones</h2>
      <table><tr><th>zone<th>node<th>footprint<th>links</tr>{zrows}</table>
      <form method=post action=/ui/logout><button>Sign out</button></form>""")


def zone_page(slug: str, msg: str, err: str) -> bytes:
    st = zone_status(slug)
    stack = " ".join(f"{k}:{v}" for k, v in st["stack"].items()) or "—"
    code, users = forgejo(slug, "GET", "/admin/users?limit=50")
    ulist = "".join(
        f"<tr><td>{html.escape(u['login'])}</td><td>{html.escape(u.get('email',''))}</td>"
        f"<td>{'admin' if u.get('is_admin') else ''}</td>"
        f"<td><form method=post action=/ui/user/delete style=display:inline>"
        f"<input type=hidden name=login value='{html.escape(u['login'])}'>"
        f"<button {'disabled' if u['login']=='zoneadmin' else ''}>remove</button></form></td></tr>"
        for u in (users or []) if isinstance(u, dict)
    ) if code == 200 else f"<tr><td colspan=4>could not list users ({code})</td></tr>"
    code, repos = forgejo(slug, "GET", "/repos/search?limit=50")
    rl = repos.get("data", []) if isinstance(repos, dict) else []
    rlist = "".join(f"<li><a href='{html.escape(r['html_url'])}'>{html.escape(r['full_name'])}</a></li>"
                    for r in rl) or "<li>none yet</li>"
    banner = ""
    if msg:
        banner += f"<div class='msg'>{html.escape(msg)}</div>"
    if err:
        banner += f"<div class='msg err'>{html.escape(err)}</div>"
    return page(f"zone {slug}", banner + f"""
      <div class=card>
        <b>Forgejo</b> <a href='{html.escape(st['forgejo_url'])}'>{html.escape(st['forgejo_url'])}</a><br>
        <b>Live app</b> <a href='{html.escape(st['app_url'])}'>{html.escape(st['app_url'])}</a> — {html.escape(st['app'])}<br>
        <b>Node</b> {html.escape(st['node'] or '?')} &nbsp; <b>Stack</b> <code>{html.escape(stack)}</code>
        <form method=post action=/ui/runner/restart><button>Restart runner</button></form>
      </div>

      <h2>Users</h2>
      <form method=post action=/ui/user/create>
        <label>username<input name=username required></label>
        <label>email<input name=email type=email required></label>
        <label>password<input name=password type=password required></label>
        <button>Add user</button>
      </form>
      <table><tr><th>login<th>email<th>role<th></tr>{ulist}</table>

      <h2>Repositories</h2>
      <form method=post action=/ui/repo/create>
        <label>name<input name=name required></label>
        <label>&nbsp;<span><input type=checkbox name=private> private</span></label>
        <button>Create repo</button>
      </form>
      <ul>{rlist}</ul>

      <form method=post action=/ui/logout><button>Sign out</button></form>""")


# --------------------------------------------------------------------- server
class H(BaseHTTPRequestHandler):
    server_version = "hz-control"

    # -- helpers
    def _cookies(self) -> SimpleCookie:
        c = SimpleCookie()
        c.load(self.headers.get("Cookie", ""))
        return c

    def _json(self, code: int, body: object) -> None:
        p = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(p)))
        self.end_headers()
        self.wfile.write(p)

    def _html(self, body: bytes, code: int = 200, cookie: str | None = None) -> None:
        self.send_response(code)
        self.send_header("content-type", "text/html; charset=utf-8")
        self.send_header("content-length", str(len(body)))
        if cookie:
            self.send_header("set-cookie", cookie)
        self.end_headers()
        self.wfile.write(body)

    def _redirect(self, to: str, cookie: str | None = None) -> None:
        self.send_response(303)
        self.send_header("location", to)
        if cookie:
            self.send_header("set-cookie", cookie)
        self.end_headers()

    def _form(self) -> dict[str, str]:
        n = int(self.headers.get("content-length") or 0)
        if n > MAX_BODY:
            return {}
        q = parse_qs(self.rfile.read(n).decode("utf-8", "replace"))
        return {k: v[0] for k, v in q.items()}

    def _role(self) -> tuple[str, str | None]:
        return identify(_token_of(self.headers, self._cookies()))

    def log_message(self, fmt, *a):
        print(f"{self.address_string()} {fmt % a}", flush=True)

    # -- GET
    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/healthz":
            return self._json(200, {"status": "ok"})
        role, slug = self._role()
        if path == "/":
            msg = parse_qs(urlparse(self.path).query).get("m", [""])[0]
            if role == "platform":
                return self._html(platform_page(msg))
            if role == "zone":
                return self._html(zone_page(slug, msg, ""))
            return self._html(login_page(), 401)
        if path == "/api/nodes" and role == "platform":
            return self._json(200, {n: {**node_env(n), "alloc": node_alloc(n)} for n in nodes()})
        if path == "/api/zones" and role == "platform":
            return self._json(200, {s: zone_status(s) for s in zones()})
        if path == "/api/zone" and role == "zone":
            return self._json(200, zone_status(slug))
        if path == "/api/zone/users" and role == "zone":
            code, body = forgejo(slug, "GET", "/admin/users?limit=50")
            return self._json(code, body)
        return self._json(404, {"error": "not found"})

    # -- POST
    def do_POST(self):
        path = urlparse(self.path).path
        role, slug = self._role()

        if path == "/deploy":
            if role != "deploy":
                return self._json(401, {"error": "bad or missing deploy token"})
            try:
                req = json.loads(self.rfile.read(int(self.headers.get("content-length") or 0)) or b"{}")
                return self._json(200, deploy(slug, str(req.get("image", "")), int(req.get("port", 8080))))
            except (ValueError, KeyError) as e:
                return self._json(400, {"error": str(e)})
            except subprocess.CalledProcessError as e:
                return self._json(502, {"error": f"deploy failed: {e.stderr or e}"})

        # --- UI form handlers (cookie or bearer)
        if path == "/ui/login":
            tok = self._form().get("token", "")
            r, _ = identify(tok)
            if r in ("platform", "zone"):
                return self._redirect("/", f"hz_token={tok}; Path=/; HttpOnly; SameSite=Lax")
            return self._html(login_page("that token isn't recognised"), 401)
        if path == "/ui/logout":
            return self._redirect("/", "hz_token=; Path=/; Max-Age=0")

        if role == "zone":
            f = self._form()
            try:
                if path == "/ui/user/create":
                    code, b = forgejo(slug, "POST", "/admin/users", {
                        "username": f["username"], "email": f["email"], "password": f["password"],
                        "must_change_password": False,
                    })
                    return self._redirect_zone(slug, code, b, f"user {f['username']} created")
                if path == "/ui/user/delete":
                    code, b = forgejo(slug, "DELETE", f"/admin/users/{f['login']}")
                    return self._redirect_zone(slug, code, b, f"user {f['login']} removed")
                if path == "/ui/repo/create":
                    code, b = forgejo(slug, "POST", "/admin/users/zoneadmin/repos", {
                        "name": f["name"], "private": f.get("private") == "on", "auto_init": True,
                    })
                    return self._redirect_zone(slug, code, b, f"repo {f['name']} created")
                if path == "/ui/runner/restart":
                    r = zc(slug, "restart", "runner")
                    ok = r.returncode == 0
                    return self._redirect("/?m=" + ("runner+restarted" if ok else "restart+failed"))
            except KeyError as e:
                return self._html(zone_page(slug, "", f"missing field {e}"), 400)

        return self._json(403, {"error": "forbidden"})

    def _redirect_zone(self, slug, code, body, ok_msg):
        if 200 <= code < 300:
            self._redirect("/?m=" + ok_msg.replace(" ", "+"))
        else:
            err = body.get("message") or body.get("error") or str(body) if isinstance(body, dict) else str(body)
            self._html(zone_page(slug, "", f"Forgejo said ({code}): {err}"), 400)


def main() -> None:
    if not ADMIN_TOKEN:
        print("warning: HZ_ADMIN_TOKEN not set — the platform view is disabled", flush=True)
    print(f"hz-control on {ADDR}:{PORT}  ({len(nodes())} nodes, {len(zones())} zones)", flush=True)
    ThreadingHTTPServer((ADDR, PORT), H).serve_forever()


if __name__ == "__main__":
    main()
