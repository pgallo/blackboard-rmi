#!/usr/bin/env bash
# Distributed demo on localhost: one RMI registry, one blackboard/controller server and one
# JVM per knowledge source (plus a second Correlator that crashes mid-activation), for about
# 10 s of feed. The server prints the final blackboard state and the KS activation trace.
#   scripts/demo.sh [--seed N] [--duration S] [--port P] [--no-fault]
set -euo pipefail
cd "$(dirname "$0")/.."

SEED=7; DURATION=10; PORT=${PORT:-}; FAULT=1
while [[ $# -gt 0 ]]; do
  case "$1" in
    --seed) SEED=$2; shift 2 ;;
    --duration) DURATION=$2; shift 2 ;;
    --port) PORT=$2; shift 2 ;;
    --no-fault) FAULT=0; shift ;;
    *) echo "unknown option $1" >&2; exit 1 ;;
  esac
done
if [[ -z "$PORT" ]]; then PORT=$(( 20000 + RANDOM % 20000 )); fi

JAR=build/blackboard-rmi.jar
[[ -f "$JAR" ]] || scripts/build.sh --no-test
LOGS=build/demo-logs
rm -rf "$LOGS" && mkdir -p "$LOGS"
JAVA=(java -XX:+UseSerialGC -Xshare:auto -cp "$JAR" io.github.pgallo.blackboard.app.Main)
unset JAVA_TOOL_OPTIONS || true

PIDS=()
cleanup() { for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null || true; done; wait 2>/dev/null || true; }
trap cleanup EXIT

echo "== registry on 127.0.0.1:$PORT"
"${JAVA[@]}" registry --port "$PORT" > "$LOGS/registry.log" 2>&1 & PIDS+=($!)

WORKERS=$(( 5 + FAULT ))
echo "== blackboard server (seed $SEED, ~${DURATION}s feed, waits for $WORKERS workers)"
"${JAVA[@]}" server --port "$PORT" --seed "$SEED" --duration "$DURATION" --expect-workers "$WORKERS" \
  > "$LOGS/server.log" 2>&1 & SERVER=$!

for ks in Correlator Classifier Fuser SituationAssessor Critic; do
  echo "== worker $ks"
  "${JAVA[@]}" worker --ks "$ks" --port "$PORT" --id "$ks-1" > "$LOGS/$ks.log" 2>&1 & PIDS+=($!)
done
if [[ $FAULT == 1 ]]; then
  echo "== worker Correlator-2 (will halt its JVM on its 3rd activation, with work in flight)"
  "${JAVA[@]}" worker --ks Correlator --port "$PORT" --id Correlator-2 --crash-after 3 > "$LOGS/Correlator-2.log" 2>&1 & PIDS+=($!)
fi

set +e
wait "$SERVER"; CODE=$?
set -e
cat "$LOGS/server.log"
echo
echo "== worker logs"
for f in "$LOGS"/*.log; do
  case "$f" in *server.log|*registry.log) continue ;; esac
  sed "s/^/  /" "$f" | tail -n 4
done
exit $CODE
