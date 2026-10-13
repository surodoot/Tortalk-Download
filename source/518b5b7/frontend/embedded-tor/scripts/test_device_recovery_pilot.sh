#!/usr/bin/env bash
# Production coordinator + encrypted store against a real Go pilot on loopback.
# Android storage/Keystore doubles only. No Tor, downloads, signing, or device runtime.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
[[ $# == 1 && "$1" == /* && -x "$1" ]] || {
  echo "Usage: $0 /absolute/path/to/tortalk-pilot" >&2; exit 2;
}
JSON="$ROOT/.tooling/android-all-8.0.0_r4-robolectric-r1.jar"
printf '%s  %s\n' '3707dc100381e3bbcc57b85255fd313ae20875fc65cf3130c47e86f044ad77f0' "$JSON" | sha256sum --check --status
OUT=$(mktemp -d "${TMPDIR:-/tmp}/tortalk-recovery-pilot-host.XXXXXX")
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/classes" "$OUT/data"
MAIN="$ROOT/src/main/java/dev/gyeopdam/embeddedtor"
mapfile -t SOURCES < <(find "$ROOT/integration/storage-host/src/android" "$ROOT/integration/storage-host/src/dev/gyeopdam/embeddedtor/host" -name '*.java' -print | sort)
java com.sun.tools.javac.Main -source 17 -target 17 -cp "$JSON" -d "$OUT/classes" "${SOURCES[@]}" \
  "$MAIN"/{DeviceRecoveryCoordinator,PendingDeviceRecoveryStore,PendingDeviceRecoverySchema,AppSessionStore,SessionSchema,KeystoreBlobStore,EncryptedBlobEnvelope,NativeResponseRules,UiCommandPolicy}.java \
  "$ROOT/integration/device-recovery-pilot/src/dev/gyeopdam/embeddedtor/DeviceRecoveryPilotHost.java"
java -Dsun.net.http.allowRestrictedHeaders=true -cp "$OUT/classes:$JSON" \
  dev.gyeopdam.embeddedtor.DeviceRecoveryPilotHost "$1" "$OUT/data"
