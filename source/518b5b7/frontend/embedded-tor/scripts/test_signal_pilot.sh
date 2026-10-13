#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
[[ $# == 1 ]] || { echo "Usage: $0 /absolute/path/to/tortalk-pilot" >&2; exit 2; }
CRYPTO="$ROOT/../.tooling/crypto-spike"
while read -r hash url; do printf '%s  %s\n' "$hash" "$CRYPTO/${url##*/}" | sha256sum --check --status; done < "$ROOT/../crypto-spike/artifacts.sha256"
CP="$CRYPTO/libsignal-client-0.105.0.jar:$CRYPTO/kotlin-stdlib-2.2.20.jar:$ROOT/.tooling/android-all-8.0.0_r4-robolectric-r1.jar"
mkdir -p "$ROOT/.tooling/signal-pilot"
java com.sun.tools.javac.Main -source 17 -target 17 -cp "$CP" -d "$ROOT/.tooling/signal-pilot" \
 "$ROOT/src/main/java/dev/gyeopdam/embeddedtor/SignalClient.java" "$ROOT/src/main/java/dev/gyeopdam/embeddedtor/SignalJson.java" "$ROOT/src/test/java/dev/gyeopdam/embeddedtor/SignalPilotPeer.java"
python3 "$ROOT/scripts/test_signal_pilot.py" --binary "$1" --classpath "$ROOT/.tooling/signal-pilot:$CP"
