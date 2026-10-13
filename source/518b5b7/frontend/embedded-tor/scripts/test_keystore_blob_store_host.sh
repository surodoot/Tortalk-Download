#!/usr/bin/env bash
# Unchanged production storage + explicit Android doubles; no downloads, Android, signing or network.
# Synthetic keys exist only inside the test JVM. Temporary files contain ciphertext and are removed.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
FIXTURE="$ROOT/integration/storage-host"
MAIN="$ROOT/src/main/java/dev/gyeopdam/embeddedtor"
mkdir -p "$ROOT/.tooling"
OUT=$(mktemp -d "$ROOT/.tooling/storage-host.XXXXXX")
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/classes" "$OUT/data"
mapfile -t SOURCES < <(find "$FIXTURE/src" -name '*.java' -print | sort)
java com.sun.tools.javac.Main -source 17 -target 17 -d "$OUT/classes" "${SOURCES[@]}" \
  "$MAIN"/{KeystoreBlobStore,EncryptedBlobEnvelope,SessionSchema}.java
java -cp "$OUT/classes" dev.gyeopdam.embeddedtor.StorageHostReview "$OUT/data"
