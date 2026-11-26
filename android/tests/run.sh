#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
KLIB="$(cd "$(dirname "$(command -v kotlinc)")/../lib" && pwd)"
OUT=$(mktemp -d); trap 'rm -rf "$OUT"' EXIT
MAVEN=https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx
fetch() { curl --fail --silent --show-error --location "$MAVEN/$1/$2/$1-$2.jar" -o "$OUT/$1.jar"; echo "$OUT/$1.jar"; }
# kotlinc distributions do not always bundle coroutines (the GitHub runner's does not).
if [ -f "$KLIB/kotlinx-coroutines-core-jvm.jar" ]; then CP="$KLIB/kotlinx-coroutines-core-jvm.jar"
else CP="$(fetch kotlinx-coroutines-core-jvm 1.10.2)"; fi
SER_VERSION=1.8.1
for artifact in kotlinx-serialization-core-jvm kotlinx-serialization-json-jvm; do
    CP="$CP:$(fetch "$artifact" "$SER_VERSION")"
done
SOURCES=()
for source in core/src/main/kotlin/appbase/*.kt; do
    case "${source##*/}" in UIHost.kt|AndroidSavedState.kt) continue ;; esac
    SOURCES+=("$source")
done
kotlinc "${SOURCES[@]}" tests/Smoke.kt -cp "$CP" -include-runtime -d "$OUT/smoke.jar"
java -cp "$OUT/smoke.jar:$CP" SmokeKt
