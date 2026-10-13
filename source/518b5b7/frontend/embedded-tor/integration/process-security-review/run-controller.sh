#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT="$ROOT/.tooling/process-security-review-controller"
mkdir -p "$OUT"
mapfile -t SOURCES < <(find "$ROOT/integration/controller-host/src" -name '*.java' -print | sort)
java com.sun.tools.javac.Main -source 17 -target 17 -d "$OUT" "${SOURCES[@]}" \
  "$ROOT/src/main/java/dev/gyeopdam/embeddedtor/"{EmbeddedTorController,TorReadinessGate,TorLifecyclePolicy,OnionProbeSession,TorProcessProtocol,TorSocketEndpoint}.java \
  "$ROOT/integration/process-security-review/src/dev/gyeopdam/embeddedtor/ControllerSecurityReview.java"
SCENARIOS=(wrong-binder-death denied-stop-unbind old-callbacks-new-generation death-before-main-drain
  probe-death-before-main-drain)
if (( $# )); then SCENARIOS=("$@"); fi
failed=0
for scenario in "${SCENARIOS[@]}"; do
  if ! java -cp "$OUT" dev.gyeopdam.embeddedtor.ControllerSecurityReview "$scenario"; then failed=$((failed + 1)); fi
done
printf 'Independent controller security review: %s scenarios, %s failures\n' "${#SCENARIOS[@]}" "$failed"
(( failed == 0 ))
