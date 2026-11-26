#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
KLIB="$(cd "$(dirname "$(command -v kotlinc)")/../lib" && pwd)"
# kotlinc distributions do not always bundle coroutines; fall back to a pinned Maven Central jar.
COROUTINES="$KLIB/kotlinx-coroutines-core-jvm.jar"
if [ ! -f "$COROUTINES" ]; then
    VERSION=1.10.2
    CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/appbase"
    COROUTINES="$CACHE/kotlinx-coroutines-core-jvm-$VERSION.jar"
    if [ ! -f "$COROUTINES" ]; then
        mkdir -p "$CACHE"
        curl -fsSL -o "$COROUTINES.tmp" "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/$VERSION/kotlinx-coroutines-core-jvm-$VERSION.jar"
        mv "$COROUTINES.tmp" "$COROUTINES"
    fi
fi
OUT=$(mktemp -d); trap 'rm -rf "$OUT"' EXIT
SOURCES=()
for source in core/src/main/kotlin/appbase/*.kt; do
    case "${source##*/}" in UIHost.kt|AndroidSavedState.kt) continue ;; esac
    SOURCES+=("$source")
done
kotlinc "${SOURCES[@]}" tests/Smoke.kt -cp "$COROUTINES" -include-runtime -d "$OUT/smoke.jar"
java -cp "$OUT/smoke.jar:$COROUTINES" SmokeKt
