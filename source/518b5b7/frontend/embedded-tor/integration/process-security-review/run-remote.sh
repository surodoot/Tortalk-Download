#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT="$ROOT/.tooling/process-security-review-remote"
mkdir -p "$OUT"
mapfile -t SOURCES < <(find "$ROOT/integration/tor-process-host/src" -name '*.java' -print | sort)
java com.sun.tools.javac.Main -source 17 -target 17 -d "$OUT" "${SOURCES[@]}" \
  "$ROOT/src/main/java/dev/gyeopdam/embeddedtor/"{TorProcessService,TorProcessProtocol,TorProcessPolicy,TorReadinessGate,TorSocketEndpoint}.java \
  "$ROOT/integration/process-security-review/src/dev/gyeopdam/embeddedtor/RemoteSecurityReview.java"
SCENARIOS=(foreign-start foreign-malformed missing-owner owner-malformed changed-self-pid
  foreign-renew-no-lease wrong-uid-active-owner)
if (( $# )); then SCENARIOS=("$@"); fi
failed=0
for scenario in "${SCENARIOS[@]}"; do
  if ! java -cp "$OUT" dev.gyeopdam.embeddedtor.RemoteSecurityReview "$scenario"; then failed=$((failed + 1)); fi
done
printf 'Independent remote security review: %s scenarios, %s failures\n' "${#SCENARIOS[@]}" "$failed"
(( failed == 0 ))
