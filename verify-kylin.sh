#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=${BASH_SOURCE[0]%/*}
if [[ $SCRIPT_DIR == "${BASH_SOURCE[0]}" ]]; then SCRIPT_DIR=.; fi
ROOT=$(CDPATH= cd -- "$SCRIPT_DIR" && pwd)
cd -- "$ROOT"
command -v curl >/dev/null || { echo 'This optional check requires curl.' >&2; exit 1; }
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
"$JAVA_BIN" -version
uname -a
if [[ -r /etc/os-release ]]; then cat /etc/os-release; fi
RUN_DIR=$(mktemp -d "${TMPDIR:-/tmp}/offline-generator-check.XXXXXXXX")
PORT=${VERIFY_PORT:-18991}
if [[ ! $PORT =~ ^[1-9][0-9]{3,4}$ ]] || (( PORT < 1024 || PORT > 65535 )); then
    echo 'VERIFY_PORT must be an integer between 1024 and 65535.' >&2
    exit 1
fi
# --noproxy keeps checks on the local machine even when proxy variables are set.
if curl --noproxy '*' --silent --max-time 1 "http://127.0.0.1:$PORT/api/config" >/dev/null 2>&1; then
    echo "Port $PORT is occupied. Choose another VERIFY_PORT." >&2
    exit 1
fi
bash "$ROOT/start.sh" "--server.port=$PORT" "--tool.data-dir=$RUN_DIR/data" >"$RUN_DIR/server.log" 2>&1 &
TEST_PID=$!
trap 'kill "$TEST_PID" 2>/dev/null || true; wait "$TEST_PID" 2>/dev/null || true; echo "Verification files: $RUN_DIR"' EXIT
for ((attempt=0; attempt<60; attempt++)); do
    if ! kill -0 "$TEST_PID" 2>/dev/null; then cat "$RUN_DIR/server.log"; exit 1; fi
    if curl --noproxy '*' --fail --silent --max-time 2 "http://127.0.0.1:$PORT/api/config" >"$RUN_DIR/config.json"; then
        grep -Fq '"version":"2.1.1-rebuild.8-java8"' "$RUN_DIR/config.json"
        grep -Fq '"springBoot":"2.7.18"' "$RUN_DIR/config.json"
        grep -Fq '"targetJava":"8"' "$RUN_DIR/config.json"
        grep -Fq '"java":"1.8.' "$RUN_DIR/config.json"
        curl --noproxy '*' --fail --silent --max-time 3 "http://127.0.0.1:$PORT/rebuild-help.html" >"$RUN_DIR/help.html"
        echo 'PASS: generator starts on this machine with Java 8 / Spring Boot 2.7.18.'
        exit 0
    fi
    sleep 1
done
cat "$RUN_DIR/server.log"
echo 'FAIL: server did not become ready.' >&2
exit 1
