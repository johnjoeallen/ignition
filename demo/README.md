# demo/ — config generator for the two-part demo

Instead of hand-editing the config files in
[`../demo-1-spitfire.md`](../demo-1-spitfire.md) and
[`../demo-2-remote-access.md`](../demo-2-remote-access.md), fill in **one**
file and render the rest.

```sh
git clone https://github.com/johnjoeallen/ignition.git
cd ignition/demo
./gen-templates.sh                 # writes templates/ and demo.conf.example
cp demo.conf.example demo.conf
$EDITOR demo.conf                  # fill in the values
./render.sh demo.conf             # writes out/
cat out/INSTALL.txt               # where each rendered file goes
```

- **`gen-templates.sh`** — writes the placeholder templates (`templates/*`) and
  `demo.conf.example`. Run once; re-run if the templates change.
- **`render.sh demo.conf`** — substitutes `<KEY>` from `demo.conf` into every
  template, into `out/`. Blank `IGN_SECRET_KEY` / `IGN_USER_SECRET_PEPPER` /
  `POSTGRES_PASSWORD` are generated; blank WireGuard keys are generated with
  `wg` (`apt install wireguard-tools`). Generated values are written **back**
  into `demo.conf`, so a second run is reproducible.

`demo.conf` and `out/` hold secrets and are git-ignored. `demo.conf` is the
one file to back up — it has every generated token and key.

## Keeping it up to date

On `spitfire`, once the initial files from `out/` are in place, pulling a
later change and restarting is [`../update-and-run.sh`](../update-and-run.sh)
(run from the repo root): `git pull`, `docker compose … pull`, `up -d` for
both stacks, then prints status and the first-run setup code if `/setup`
hasn't been used yet.

## What gets rendered

| file | goes to | part |
|---|---|---|
| `ignition.env` | `ignition/.env` — run compose with `--project-directory .` | 1 |
| `acme.env` | `ignition/acme.env` | 1 |
| `hetzner-wg0.conf` | `hetzner:/etc/wireguard/wg0.conf` | 2 |
| `spitfire-wg0.conf` | `spitfire:/etc/wireguard/wg0.conf` | 2 |
| `hetzner-nginx-stream.conf` | `hetzner:/etc/nginx/stream-ignition.conf` | 2 |
| `INSTALL.txt` | — placement + next commands | — |
