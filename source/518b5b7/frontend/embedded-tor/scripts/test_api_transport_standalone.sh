#!/usr/bin/env bash
# Host-only pure policy and numeric-loopback mock SOCKS/HTTP tests. No downloads or Tor.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
NETTY=${NETTY_JAR_DIR:-"$ROOT/.tooling/netty"}
JUNIT=${JUNIT_JAR:-"$ROOT/../.tooling/kotlin/junit-4.13.2.jar"}
HAMCREST=${HAMCREST_JAR:-"$ROOT/../.tooling/kotlin/hamcrest-core-1.3.jar"}
ANDROID_HOST_JAR=${ANDROID_HOST_JAR:-"$ROOT/.tooling/android-all-8.0.0_r4-robolectric-r1.jar"}
CLASSPATH="$JUNIT:$HAMCREST:$ANDROID_HOST_JAR"
printf '%s  %s\n' '3707dc100381e3bbcc57b85255fd313ae20875fc65cf3130c47e86f044ad77f0' "$ANDROID_HOST_JAR" | sha256sum --check --status
for file in "$JUNIT" "$HAMCREST"; do test -f "$file" || { echo "Missing existing artifact: $file" >&2; exit 1; }; done
while read -r expected artifact; do
  file="$NETTY/${artifact##*/}"
  printf '%s  %s\n' "$expected" "$file" | sha256sum --check --status
  CLASSPATH="$CLASSPATH:$file"
done < "$ROOT/scripts/netty-artifacts.sha256"
BUILD=$(mktemp -d "$ROOT/.tooling/api-transport-tests.XXXXXX")
trap 'rm -rf "$BUILD"' EXIT
SOURCES=()
for name in OnionApiRequest OnionApiTransport TorLifecyclePolicy JsonBounds SignalProfile; do
  SOURCES+=("$ROOT/src/main/java/dev/gyeopdam/embeddedtor/$name.java")
  SOURCES+=("$ROOT/src/test/java/dev/gyeopdam/embeddedtor/${name}Test.java")
done
for name in TorSocketEndpoint TorSocketIo TorSocketChannel TorSocketEventLoopGroup AndroidTorSocket TorSocketPaths FixedOnionProbe OnionProbeSession TorReadinessGate; do
  SOURCES+=("$ROOT/src/main/java/dev/gyeopdam/embeddedtor/$name.java")
done
SOURCES+=("$ROOT/src/test/java/dev/gyeopdam/embeddedtor/HostTorSockets.java" "$ROOT/src/test/java/dev/gyeopdam/embeddedtor/TorSocketChannelTest.java" "$ROOT/src/test/java/dev/gyeopdam/embeddedtor/TorSocketShutdownRaceTest.java" "$ROOT/src/test/java/dev/gyeopdam/embeddedtor/TorSocketAbortSubmissionTest.java" "$ROOT/src/test/java/io/netty/channel/PausedDeregisterOioGroup.java")
java com.sun.tools.javac.Main -source 17 -target 17 -cp "$CLASSPATH" -d "$BUILD" "${SOURCES[@]}"
java -cp "$BUILD:$CLASSPATH" org.junit.runner.JUnitCore \
  dev.gyeopdam.embeddedtor.OnionApiRequestTest \
  dev.gyeopdam.embeddedtor.OnionApiTransportTest \
  dev.gyeopdam.embeddedtor.TorSocketChannelTest \
  dev.gyeopdam.embeddedtor.TorSocketShutdownRaceTest \
  dev.gyeopdam.embeddedtor.TorSocketAbortSubmissionTest \
  dev.gyeopdam.embeddedtor.TorLifecyclePolicyTest \
  dev.gyeopdam.embeddedtor.JsonBoundsTest \
  dev.gyeopdam.embeddedtor.SignalProfileTest
