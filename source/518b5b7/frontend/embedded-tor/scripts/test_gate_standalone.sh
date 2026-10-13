#!/usr/bin/env bash
# Pure Java gate/session state tests only; no SDK, native library load, signing, or Tor network connection.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
JUNIT=${JUNIT_JAR:-"$ROOT/../.tooling/kotlin/junit-4.13.2.jar"}
HAMCREST=${HAMCREST_JAR:-"$ROOT/../.tooling/kotlin/hamcrest-core-1.3.jar"}
for file in "$JUNIT" "$HAMCREST"; do test -f "$file" || { echo "Missing existing test artifact: $file" >&2; exit 1; }; done
mkdir -p "$ROOT/.tooling/gate-tests"
java com.sun.tools.javac.Main -source 17 -target 17 -cp "$JUNIT" -d "$ROOT/.tooling/gate-tests" \
  "$ROOT/src/main/java/dev/gyeopdam/embeddedtor/TorSocketEndpoint.java" \
  "$ROOT/src/main/java/dev/gyeopdam/embeddedtor/TorReadinessGate.java" \
  "$ROOT/src/main/java/dev/gyeopdam/embeddedtor/OnionProbeSession.java" \
  "$ROOT/src/test/java/dev/gyeopdam/embeddedtor/TorReadinessGateTest.java" \
  "$ROOT/src/test/java/dev/gyeopdam/embeddedtor/OnionProbeSessionTest.java"
java -cp "$ROOT/.tooling/gate-tests:$JUNIT:$HAMCREST" org.junit.runner.JUnitCore dev.gyeopdam.embeddedtor.TorReadinessGateTest dev.gyeopdam.embeddedtor.OnionProbeSessionTest
