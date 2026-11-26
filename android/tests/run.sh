#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
KLIB="$(cd "$(dirname "$(command -v kotlinc)")/../lib" && pwd)"
OUT=$(mktemp -d); trap 'rm -rf "$OUT"' EXIT
SER_VERSION=1.8.1
CP="$KLIB/kotlinx-coroutines-core-jvm.jar"
for artifact in kotlinx-serialization-core-jvm kotlinx-serialization-json-jvm; do
    jar="$OUT/$artifact.jar"
    curl --fail --silent --show-error --location "https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/$artifact/$SER_VERSION/$artifact-$SER_VERSION.jar" -o "$jar"
    CP="$CP:$jar"
done
SOURCES=()
for source in core/src/main/kotlin/appbase/*.kt; do
    case "${source##*/}" in UIHost.kt|AndroidSavedState.kt) continue ;; esac
    SOURCES+=("$source")
done
kotlinc "${SOURCES[@]}" tests/Smoke.kt -cp "$CP" -include-runtime -d "$OUT/smoke.jar"
java -cp "$OUT/smoke.jar:$CP" SmokeKt
