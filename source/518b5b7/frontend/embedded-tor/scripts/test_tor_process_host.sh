#!/usr/bin/env bash
# Actual TorProcessService with explicit Android/vendor doubles, virtual elapsed time,
# real JVM worker/watchdog threads and recorded fake kill requests. No native/Android execution.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
MAIN="$ROOT/src/main/java/dev/gyeopdam/embeddedtor"
FIXTURE="$ROOT/integration/tor-process-host"
OUT="$ROOT/.tooling/tor-process-host"
mkdir -p "$OUT"
mapfile -t FIXTURE_SOURCES < <(find "$FIXTURE/src" -name '*.java' -print | sort)
java com.sun.tools.javac.Main -source 17 -target 17 -d "$OUT" "${FIXTURE_SOURCES[@]}" \
  "$MAIN"/{TorProcessService,TorProcessProtocol,TorProcessPolicy,TorReadinessGate,TorSocketEndpoint}.java
SCENARIOS=(reject-main-process reject-package-uid reject-process-uid reject-unavailable-identity
  reject-socket-path-prepare reject-control-path-prepare
  hung-socket-getconf wrong-socket-getconf foreign-socket-getconf extra-socket-getconf empty-socket-getconf wrong-key-getconf
  reject-directory-before-enable reject-socket-before-ready
  hung-socket-configure failed-socket-configure socket-configure-order tcp-listener-rejected foreign-unix-listener-rejected
  hung-getinfo hung-enable hung-disable hung-super-destroy normal-super-destroy owner-death
  foreground-lease blocked-outbound wrong-uid wrong-protocol wrong-primitive-type foreign-owner
  unclaimed-startup cold-control-start authentication-pending nonzero-auth-marker hung-auth-monitor stop-ack failed-disable-no-ack stop-before-start protocol-reply-schema)
if (( $# )); then SCENARIOS=("$@"); fi
failed=0
for scenario in "${SCENARIOS[@]}"; do
  if ! java -cp "$OUT" dev.gyeopdam.embeddedtor.TorProcessReview "$scenario"; then
    printf 'FAIL %s\n' "$scenario" >&2
    failed=$((failed + 1))
  fi
done
printf '\nTor process host integration: %s scenarios, %s failures\n' "${#SCENARIOS[@]}" "$failed"
(( failed == 0 ))
