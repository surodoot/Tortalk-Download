#!/usr/bin/env bash
# Real facade + request/transport/gate/JSON guard; explicit Android/controller doubles and loopback only.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
NETTY=${NETTY_JAR_DIR:-"$ROOT/.tooling/netty"}
JUNIT=${JUNIT_JAR:-"$ROOT/../.tooling/kotlin/junit-4.13.2.jar"}
HAMCREST=${HAMCREST_JAR:-"$ROOT/../.tooling/kotlin/hamcrest-core-1.3.jar"}
JSON=${ANDROID_JSON_JAR:-"$ROOT/.tooling/android-all-8.0.0_r4-robolectric-r1.jar"}
CLASSPATH="$JUNIT:$HAMCREST:$JSON"
for file in "$JUNIT" "$HAMCREST" "$JSON"; do test -f "$file" || { echo "Missing existing artifact: $file" >&2; exit 1; }; done
printf '%s  %s\n' '3707dc100381e3bbcc57b85255fd313ae20875fc65cf3130c47e86f044ad77f0' "$JSON" | sha256sum --check --status
printf '%s  %s\n' '8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3' "$JUNIT" | sha256sum --check --status
printf '%s  %s\n' '66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9' "$HAMCREST" | sha256sum --check --status
while read -r expected artifact; do
  file="$NETTY/${artifact##*/}"
  printf '%s  %s\n' "$expected" "$file" | sha256sum --check --status
  CLASSPATH="$CLASSPATH:$file"
done < "$ROOT/scripts/netty-artifacts.sha256"
BUILD=$(mktemp -d "$ROOT/.tooling/api-facade-host.XXXXXX")
trap 'rm -rf "$BUILD"' EXIT
mapfile -t SOURCES < <(find "$HERE/src" -name '*.java' | sort)
for name in TorApiClient TorReadinessGate OnionApiRequest OnionApiTransport JsonBounds TorSocketEndpoint TorSocketIo TorSocketChannel TorSocketEventLoopGroup AndroidTorSocket TorSocketPaths FixedOnionProbe OnionProbeSession; do
  SOURCES+=("$ROOT/src/main/java/dev/gyeopdam/embeddedtor/$name.java")
done
SOURCES+=("$ROOT/src/test/java/dev/gyeopdam/embeddedtor/HostTorSockets.java")
java com.sun.tools.javac.Main -source 17 -target 17 -cp "$CLASSPATH" -d "$BUILD" "${SOURCES[@]}"
if [[ "${1:-}" == "--stress" ]]; then
  java -cp "$BUILD:$CLASSPATH" dev.gyeopdam.embeddedtor.ApiFacadeHostStress "${2:-100}"
elif [[ $# -eq 0 ]]; then
  java -cp "$BUILD:$CLASSPATH" org.junit.runner.JUnitCore dev.gyeopdam.embeddedtor.ApiFacadeHostTest
else
  echo "Usage: $0 [--stress ITERATIONS]" >&2
  exit 2
fi
