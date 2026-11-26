#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
KLIB="$(cd "$(dirname "$(command -v kotlinc)")/../lib" && pwd)"
OUT=$(mktemp -d); trap 'rm -rf "$OUT"' EXIT
SOURCES=()
for source in core/src/main/kotlin/appbase/*.kt; do
    case "${source##*/}" in UIHost.kt|AndroidSavedState.kt) continue ;; esac
    SOURCES+=("$source")
done
kotlinc "${SOURCES[@]}" tests/Smoke.kt -cp "$KLIB/kotlinx-coroutines-core-jvm.jar" -include-runtime -d "$OUT/smoke.jar"
java -cp "$OUT/smoke.jar:$KLIB/kotlinx-coroutines-core-jvm.jar" SmokeKt
