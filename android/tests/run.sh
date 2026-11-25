#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
KLIB="$(cd "$(dirname "$(command -v kotlinc)")/../lib" && pwd)"
OUT=$(mktemp -d); trap 'rm -rf "$OUT"' EXIT
kotlinc core/src/main/kotlin/appbase/*.kt tests/Smoke.kt -cp "$KLIB/kotlinx-coroutines-core-jvm.jar" -include-runtime -d "$OUT/smoke.jar"
java -cp "$OUT/smoke.jar:$KLIB/kotlinx-coroutines-core-jvm.jar" SmokeKt
