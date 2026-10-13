#!/usr/bin/env bash
# Real EmbeddedTorController/protocol/gate + explicit Android IPC/service/probe doubles.
# No external artifacts, network, Android SDK, Tor/JNI load, key generation, APK creation or signing.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
MAIN="$ROOT/src/main/java/dev/gyeopdam/embeddedtor"
FIXTURE="$ROOT/integration/controller-host"
OUT="$ROOT/.tooling/controller-host"
mkdir -p "$OUT"
mapfile -t FIXTURE_SOURCES < <(find "$FIXTURE/src" -name '*.java' -print | sort)
java com.sun.tools.javac.Main -source 17 -target 17 -d "$OUT" "${FIXTURE_SOURCES[@]}" \
  "$MAIN"/{EmbeddedTorController,TorReadinessGate,TorLifecyclePolicy,OnionProbeSession,TorProcessProtocol,TorSocketEndpoint}.java
SCENARIOS=(offline-opt-in explicit-start repeated-start foreground-owners normal-stop stop-ack-not-death
  exact-binder-death explicit-restart unexpected-death late-old-reply duplicate-binding
  stop-before-binding null-binding bind-refused start-refused bind-timeout command-timeout ready-expiry
  endpoint-identity main-pid-rejected changed-pid-rejected pid-pinned-before-ready invalid-pid-schema remote-path-schema
  wrong-uid invalid-schema stale-sequence stale-timestamp future-timestamp wrong-epoch stop-ack-clock
  control-error repeated-stop dead-before-binding foreign-component binding-died
  callback-isolation probe-cancel main-thread-only)
if (( $# )); then SCENARIOS=("$@"); fi
failed=0
for scenario in "${SCENARIOS[@]}"; do
  # Fresh process preserves the real singleton's semantics without test-only production injection.
  if ! java -cp "$OUT" dev.gyeopdam.embeddedtor.ControllerReview "$scenario"; then
    printf 'FAIL %s\n' "$scenario" >&2
    failed=$((failed + 1))
  fi
done
printf '\nController host integration: %s scenarios, %s failures\n' "${#SCENARIOS[@]}" "$failed"
(( failed == 0 ))
