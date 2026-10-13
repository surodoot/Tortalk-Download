#!/usr/bin/env bash
# Compile only. Does not execute Android JNI, Android Keystore, AGP or D8.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CRYPTO="$ROOT/../.tooling/crypto-spike"
printf '%s  %s\n' cdc1846376a14b0370cc63454a129606b4a52cc50ada75ef0d4cf956b1ad2daa "$ROOT/.tooling/android-api26.jar" | sha256sum --check --status
printf '%s  %s\n' 48ebd3b6614a8e4367d1dbda1222aed5ffb6b874214922412af5c9fbcd5e877a "$ROOT/.tooling/core-lambda-stubs.jar" | sha256sum --check --status
while read -r hash url; do printf '%s  %s\n' "$hash" "$CRYPTO/${url##*/}" | sha256sum --check --status; done < "$ROOT/../crypto-spike/artifacts.sha256"
mkdir -p "$ROOT/.tooling/signal-api26"
java com.sun.tools.javac.Main -source 8 -target 8 \
 -bootclasspath "$ROOT/.tooling/core-lambda-stubs.jar:$ROOT/.tooling/android-api26.jar" \
 -cp "$CRYPTO/libsignal-client-0.105.0.jar:$CRYPTO/kotlin-stdlib-2.2.20.jar" \
 -d "$ROOT/.tooling/signal-api26" "$ROOT/src/main/java/dev/gyeopdam/embeddedtor/SignalClient.java" "$ROOT/src/main/java/dev/gyeopdam/embeddedtor/SignalJson.java"
echo 'PASS: Signal bridge + JSON boundary compile against official API26 Java/Android surface'
echo 'NOT TESTED: Android JNI execution, Keystore, AGP/D8, APK, Tor or physical device'
