#!/usr/bin/env bash
# Synthetic host-only production checkpoint tests. No downloads/signing/device/network.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
MAIN="$ROOT/src/main/java/dev/gyeopdam/embeddedtor"
mkdir -p "$ROOT/.tooling"
OUT=$(mktemp -d "$ROOT/.tooling/device-recovery-host.XXXXXX")
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/classes" "$OUT/data"
mapfile -t SOURCES < <(find "$ROOT/integration/storage-host/src" "$ROOT/integration/device-recovery-host/src" -name '*.java' -print | sort)
java com.sun.tools.javac.Main -source 17 -target 17 -d "$OUT/classes" "${SOURCES[@]}" \
 "$MAIN"/{KeystoreBlobStore,EncryptedBlobEnvelope,SessionSchema,PendingDeviceRecoveryStore,PendingDeviceRecoverySchema}.java
java -cp "$OUT/classes" dev.gyeopdam.embeddedtor.PendingDeviceRecoveryHostReview "$OUT/data"
