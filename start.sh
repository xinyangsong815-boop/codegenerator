#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=${BASH_SOURCE[0]%/*}
if [[ $SCRIPT_DIR == "${BASH_SOURCE[0]}" ]]; then SCRIPT_DIR=.; fi
ROOT=$(CDPATH= cd -- "$SCRIPT_DIR" && pwd)
cd -- "$ROOT"
if [[ -n ${JAVA_HOME:-} ]]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
else
    JAVA_BIN=$(command -v java || true)
fi
if [[ -z $JAVA_BIN || ! -x $JAVA_BIN ]]; then
    echo 'Java 17 is required. Set JAVA_HOME to an installed Java 17 JDK/JRE.' >&2
    exit 1
fi
JAR_PATH="$ROOT/offline-dev-assistant-2.1.1-rebuild.8-java17.jar"
if [[ ! -f $JAR_PATH ]]; then JAR_PATH="$ROOT/dist/offline-dev-assistant-2.1.1-rebuild.8-java17.jar"; fi
if [[ ! -f $JAR_PATH ]]; then echo 'Application JAR is missing. Run bash build.sh in the source package.' >&2; exit 1; fi
exec "$JAVA_BIN" -Xms64m -Xmx512m -Dfile.encoding=UTF-8 -jar "$JAR_PATH" "$@"
