#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=${BASH_SOURCE[0]%/*}
if [[ $SCRIPT_DIR == "${BASH_SOURCE[0]}" ]]; then SCRIPT_DIR=.; fi
ROOT=$(CDPATH= cd -- "$SCRIPT_DIR" && pwd)
cd -- "$ROOT"
JAVAC_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
mkdir -p build/bootstrap
"$JAVAC_BIN" -encoding UTF-8 -source 8 -target 8 -d build/bootstrap tools/OfflineBuild.java
"$JAVA_BIN" -Xmx512m -cp build/bootstrap OfflineBuild "$ROOT"
