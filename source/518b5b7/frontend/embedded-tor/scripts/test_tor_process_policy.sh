#!/usr/bin/env bash
# Pure state/deadline tests. No Android, native Tor, signing, installation or network execution.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
JUNIT=${JUNIT_JAR:-"$ROOT/../.tooling/kotlin/junit-4.13.2.jar"}
HAMCREST=${HAMCREST_JAR:-"$ROOT/../.tooling/kotlin/hamcrest-core-1.3.jar"}
for file in "$JUNIT" "$HAMCREST"; do test -f "$file" || { echo "Missing existing test artifact: $file" >&2; exit 1; }; done
printf '%s  %s\n' 8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3 "$JUNIT" | sha256sum --check --status
printf '%s  %s\n' 66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9 "$HAMCREST" | sha256sum --check --status
mkdir -p "$ROOT/.tooling/tor-process-tests"
java com.sun.tools.javac.Main -source 17 -target 17 -cp "$JUNIT" -d "$ROOT/.tooling/tor-process-tests" \
  "$ROOT/src/main/java/dev/gyeopdam/embeddedtor/TorProcessPolicy.java" \
  "$ROOT/src/test/java/dev/gyeopdam/embeddedtor/TorProcessPolicyTest.java"
java -cp "$ROOT/.tooling/tor-process-tests:$JUNIT:$HAMCREST" org.junit.runner.JUnitCore dev.gyeopdam.embeddedtor.TorProcessPolicyTest
