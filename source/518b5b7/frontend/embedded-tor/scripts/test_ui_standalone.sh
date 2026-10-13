#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
JUNIT="$ROOT/../.tooling/kotlin/junit-4.13.2.jar"
HAMCREST="$ROOT/../.tooling/kotlin/hamcrest-core-1.3.jar"
mkdir -p "$ROOT/.tooling/ui-tests"
java com.sun.tools.javac.Main -source 17 -target 17 -cp "$JUNIT" -d "$ROOT/.tooling/ui-tests" \
 "$ROOT/src/main/java/dev/gyeopdam/embeddedtor/AccountWorkQueue.java" \
 "$ROOT/src/main/java/dev/gyeopdam/embeddedtor/TorTalkState.java" \
 "$ROOT/src/main/java/dev/gyeopdam/embeddedtor/InvitationInput.java" \
 "$ROOT/src/main/java/dev/gyeopdam/embeddedtor/DraftLedger.java" \
 "$ROOT/src/main/java/dev/gyeopdam/embeddedtor/CoordinatorFailures.java" \
 "$ROOT/src/main/java/dev/gyeopdam/embeddedtor/UiCommandPolicy.java" \
 "$ROOT/src/test/java/dev/gyeopdam/embeddedtor/AccountWorkQueueTest.java" \
 "$ROOT/src/test/java/dev/gyeopdam/embeddedtor/TorTalkStateTest.java" \
 "$ROOT/src/test/java/dev/gyeopdam/embeddedtor/InvitationInputTest.java" \
 "$ROOT/src/test/java/dev/gyeopdam/embeddedtor/DraftLedgerTest.java" \
 "$ROOT/src/test/java/dev/gyeopdam/embeddedtor/CoordinatorFailuresTest.java" \
 "$ROOT/src/test/java/dev/gyeopdam/embeddedtor/UiCommandPolicyTest.java"
java -cp "$ROOT/.tooling/ui-tests:$JUNIT:$HAMCREST" org.junit.runner.JUnitCore dev.gyeopdam.embeddedtor.AccountWorkQueueTest dev.gyeopdam.embeddedtor.TorTalkStateTest dev.gyeopdam.embeddedtor.UiCommandPolicyTest dev.gyeopdam.embeddedtor.InvitationInputTest dev.gyeopdam.embeddedtor.DraftLedgerTest dev.gyeopdam.embeddedtor.CoordinatorFailuresTest
node "$ROOT/scripts/test_packaged_ui_logic.cjs"
