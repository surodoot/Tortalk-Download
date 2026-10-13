#!/usr/bin/env bash
# Production MessageCoordinator + real pinned libsignal/JVM JNI; synthetic in-memory state only.
# Android service/Keystore/HTTP doubles are outside Android main/test source sets.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CRYPTO="$ROOT/../.tooling/crypto-spike"
MAIN="$ROOT/src/main/java/dev/gyeopdam/embeddedtor"
FIXTURE="$ROOT/integration/coordinator-host"
OUT="$ROOT/.tooling/coordinator-host"
JSON="$ROOT/.tooling/android-all-8.0.0_r4-robolectric-r1.jar"
while read -r hash url; do
  printf '%s  %s\n' "$hash" "$CRYPTO/${url##*/}" | sha256sum --check --status
done < "$ROOT/../crypto-spike/artifacts.sha256"
[[ -f "$JSON" ]] || { echo 'Missing local Android JSON fixture jar; provision pinned test dependencies first.' >&2; exit 2; }
printf '%s  %s\n' '3707dc100381e3bbcc57b85255fd313ae20875fc65cf3130c47e86f044ad77f0' "$JSON" | sha256sum --check --status
CP="$CRYPTO/libsignal-client-0.105.0.jar:$CRYPTO/kotlin-stdlib-2.2.20.jar:$JSON"
mkdir -p "$OUT"
mapfile -t FIXTURE_SOURCES < <(find "$FIXTURE/src" -name '*.java' -print | sort)
java com.sun.tools.javac.Main -source 17 -target 17 -cp "$CP" -d "$OUT" "${FIXTURE_SOURCES[@]}" \
 "$MAIN"/{MessageCoordinator,SignalClient,SignalJson,SignalProfile,AppSessionStore,SessionSchema,OnionApiRequest,UiCommandPolicy}.java
java -cp "$OUT:$CP" dev.gyeopdam.embeddedtor.CoordinatorReview "$@"
