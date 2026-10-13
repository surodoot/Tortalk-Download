#!/usr/bin/env bash
# Real pinned libsignal JVM JNI, synthetic keys, production SignalClient implementation.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CRYPTO="$ROOT/../.tooling/crypto-spike"
JUNIT="$ROOT/../.tooling/kotlin/junit-4.13.2.jar"
HAMCREST="$ROOT/../.tooling/kotlin/hamcrest-core-1.3.jar"
while read -r hash url; do printf '%s  %s\n' "$hash" "$CRYPTO/${url##*/}" | sha256sum --check --status; done < "$ROOT/../crypto-spike/artifacts.sha256"
CP="$CRYPTO/libsignal-client-0.105.0.jar:$CRYPTO/kotlin-stdlib-2.2.20.jar:$JUNIT:$HAMCREST"
mkdir -p "$ROOT/.tooling/signal-tests"
java com.sun.tools.javac.Main -source 17 -target 17 -cp "$CP" -d "$ROOT/.tooling/signal-tests" \
 "$ROOT/src/main/java/dev/gyeopdam/embeddedtor/SignalClient.java" "$ROOT/src/test/java/dev/gyeopdam/embeddedtor/SignalClientTest.java"
java -cp "$ROOT/.tooling/signal-tests:$CP" org.junit.runner.JUnitCore dev.gyeopdam.embeddedtor.SignalClientTest
