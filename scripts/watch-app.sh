#!/usr/bin/env bash
# Watch one Ignition app so you can see why it vanishes. Run on the node.
#
#   scripts/watch-app.sh [project]        default: app-temporal-dragons-cardart
#   nohup scripts/watch-app.sh &          or run it inside tmux
#
# Writes to ~/ignition-watch/<project>/ :
#   events.log    docker events for this project (die/kill/stop/destroy/oom/start/create)
#   node-events.log   destroy/kill/die for ANY container on the node (prune, rm -f ...)
#   app.log       the app's own output, re-attached after stop/start/recreate
#   control.log   ignition-control log, re-attached after Watchtower recreates it
#   watchtower.log  Watchtower log
#   snapshot.log  one line a minute: which of the project's containers exist and their state
#
# Stop it with Ctrl-C, or: pkill -f watch-app.sh
set -u

PROJECT="${1:-app-temporal-dragons-cardart}"
CONTROL="${IGN_CONTROL_CONTAINER:-ignition-control}"
WATCHTOWER="${IGN_WATCHTOWER_CONTAINER:-ignition-core-watchtower-1}"
OUT="${HOME}/ignition-watch/${PROJECT}"
mkdir -p "$OUT"

ts() { date -u +%Y-%m-%dT%H:%M:%SZ; }
note() { echo "$(ts) [watch] $*" | tee -a "$OUT/snapshot.log" >&2; }

pids=()
cleanup() { kill "${pids[@]}" 2>/dev/null; note "stopped"; exit 0; }
trap cleanup INT TERM

# follow one container's log forever, re-attaching whenever it stops/is recreated.
# $1 = log file, $2.. = a command that prints the container name (or nothing)
follow() {
  local file="$1"; shift
  local first=1 name since
  while true; do
    name="$("$@" 2>/dev/null | head -1)"
    if [ -n "$name" ]; then
      since="$(ts)"
      if [ "$first" = 1 ]; then
        echo "$(ts) [watch] attached to $name (last 100 lines)" >> "$file"
        docker logs -f --tail 100 --timestamps "$name" >> "$file" 2>&1
        first=0
      else
        echo "$(ts) [watch] re-attached to $name" >> "$file"
        docker logs -f --since "$since" --timestamps "$name" >> "$file" 2>&1
      fi
      echo "$(ts) [watch] log stream for $name ended" >> "$file"
    fi
    sleep 2
  done
}

app_name()  { docker ps -a --filter "label=com.docker.compose.project=${PROJECT}" --format '{{.Names}}'; }
by_name()   { docker ps -a --filter "name=^/$1\$" --format '{{.Names}}'; }

# 1. events for this project
(
  while true; do
    docker events \
      --filter "label=com.docker.compose.project=${PROJECT}" \
      --filter event=die --filter event=kill --filter event=stop --filter event=destroy \
      --filter event=create --filter event=start --filter event=oom --filter event=restart \
      --format '{{.Time}} {{.Action}} {{.Actor.Attributes.name}} signal={{.Actor.Attributes.signal}} exit={{.Actor.Attributes.exitCode}}' \
      >> "$OUT/events.log" 2>&1
    sleep 2
  done
) & pids+=($!)

# 2. destroy/kill/die on any container (catches prune, docker rm -f, a node job)
(
  while true; do
    docker events --filter type=container \
      --filter event=destroy --filter event=kill --filter event=die \
      --format '{{.Time}} {{.Action}} {{.Actor.Attributes.name}} signal={{.Actor.Attributes.signal}} exit={{.Actor.Attributes.exitCode}}' \
      >> "$OUT/node-events.log" 2>&1
    sleep 2
  done
) & pids+=($!)

# 3-5. the logs, re-attaching
follow "$OUT/app.log"        app_name                 & pids+=($!)
follow "$OUT/control.log"    by_name "$CONTROL"       & pids+=($!)
follow "$OUT/watchtower.log" by_name "$WATCHTOWER"    & pids+=($!)

# 6. a minute-by-minute snapshot, so the time of disappearance is bracketed
(
  while true; do
    s="$(docker ps -a --filter "label=com.docker.compose.project=${PROJECT}" \
          --format '{{.Names}}={{.Status}}')"
    echo "$(ts) ${s:-NO CONTAINERS FOR ${PROJECT}}" | tr '\n' ' ' >> "$OUT/snapshot.log"
    echo >> "$OUT/snapshot.log"
    sleep 60
  done
) & pids+=($!)

note "watching ${PROJECT}, writing to ${OUT}"
wait
