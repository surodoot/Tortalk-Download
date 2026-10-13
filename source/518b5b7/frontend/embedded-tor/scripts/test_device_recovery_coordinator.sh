#!/usr/bin/env bash
# Host-only native JSON validation; real pinned AOSP JSON, no Activity or Keystore runtime.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
JSON="$ROOT/.tooling/android-all-8.0.0_r4-robolectric-r1.jar"
JUNIT="$ROOT/../.tooling/kotlin/junit-4.13.2.jar"
HAMCREST="$ROOT/../.tooling/kotlin/hamcrest-core-1.3.jar"
printf '%s  %s\n' '3707dc100381e3bbcc57b85255fd313ae20875fc65cf3130c47e86f044ad77f0' "$JSON" | sha256sum --check --status
OUT="$ROOT/.tooling/device-recovery-tests"
mkdir -p "$OUT"
java com.sun.tools.javac.Main -source 17 -target 17 -cp "$JSON:$JUNIT" -sourcepath "$ROOT/src/main/java:$ROOT/src/test/java" -d "$OUT" "$ROOT/src/test/java/dev/gyeopdam/embeddedtor/DeviceRecoveryCoordinatorTest.java"
java -cp "$OUT:$JSON:$JUNIT:$HAMCREST" org.junit.runner.JUnitCore dev.gyeopdam.embeddedtor.DeviceRecoveryCoordinatorTest
