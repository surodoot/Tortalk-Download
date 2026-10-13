#!/usr/bin/env bash
# Local mock SOCKS peers only; no real Tor, Android execution, artifact download, or signing.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
NETTY=${NETTY_JAR_DIR:-"$ROOT/../.tooling/netty"}
JUNIT=${JUNIT_JAR:-"$ROOT/../.tooling/kotlin/junit-4.13.2.jar"}
HAMCREST=${HAMCREST_JAR:-"$ROOT/../.tooling/kotlin/hamcrest-core-1.3.jar"}
ANDROID_HOST_JAR=${ANDROID_HOST_JAR:-"$ROOT/.tooling/android-all-8.0.0_r4-robolectric-r1.jar"}
CLASSPATH="$JUNIT:$HAMCREST:$ANDROID_HOST_JAR"
printf '%s  %s\n' '3707dc100381e3bbcc57b85255fd313ae20875fc65cf3130c47e86f044ad77f0' "$ANDROID_HOST_JAR" | sha256sum --check --status
for file in "$JUNIT" "$HAMCREST"; do test -f "$file" || { echo "Missing existing test artifact: $file" >&2; exit 1; }; done
while read -r expected artifact; do
  file="$NETTY/${artifact##*/}"
  printf '%s  %s\n' "$expected" "$file" | sha256sum --check --status
  CLASSPATH="$CLASSPATH:$file"
done < "$ROOT/scripts/netty-artifacts.sha256"
mkdir -p "$ROOT/.tooling/probe-tests"
java com.sun.tools.javac.Main -source 17 -target 17 -cp "$CLASSPATH" -d "$ROOT/.tooling/probe-tests" \
  "$ROOT/src/main/java/dev/gyeopdam/embeddedtor/"{TorSocketEndpoint,TorSocketIo,TorSocketChannel,TorSocketEventLoopGroup,AndroidTorSocket,TorSocketPaths,OnionApiTransport,OnionApiRequest}.java \
  "$ROOT/src/test/java/dev/gyeopdam/embeddedtor/HostTorSockets.java" \
  "$ROOT/src/main/java/dev/gyeopdam/embeddedtor/TorReadinessGate.java" \
  "$ROOT/src/main/java/dev/gyeopdam/embeddedtor/OnionProbeSession.java" \
  "$ROOT/src/main/java/dev/gyeopdam/embeddedtor/FixedOnionProbe.java" \
  "$ROOT/src/main/java/dev/gyeopdam/embeddedtor/DiagnosticLog.java" \
  "$ROOT/src/test/java/dev/gyeopdam/embeddedtor/TorReadinessGateTest.java" \
  "$ROOT/src/test/java/dev/gyeopdam/embeddedtor/OnionProbeSessionTest.java" \
  "$ROOT/src/test/java/dev/gyeopdam/embeddedtor/FixedOnionProbeTest.java" \
  "$ROOT/src/test/java/dev/gyeopdam/embeddedtor/DiagnosticLogTest.java"
java -cp "$ROOT/.tooling/probe-tests:$CLASSPATH" org.junit.runner.JUnitCore \
  dev.gyeopdam.embeddedtor.TorReadinessGateTest \
  dev.gyeopdam.embeddedtor.OnionProbeSessionTest \
  dev.gyeopdam.embeddedtor.FixedOnionProbeTest \
  dev.gyeopdam.embeddedtor.DiagnosticLogTest
