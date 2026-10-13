#!/usr/bin/env bash
# Production journal/storage with explicit Android doubles. No downloads, Android, signing or network.
# Synthetic keys exist only in the host JVM; temporary files contain ciphertext and are removed.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
FIXTURE="$ROOT/integration/block-journal-host"
STORAGE_FIXTURE="$ROOT/integration/storage-host"
MAIN="$ROOT/src/main/java/dev/gyeopdam/embeddedtor"
mkdir -p "$ROOT/.tooling"
OUT=$(mktemp -d "$ROOT/.tooling/block-journal-host.XXXXXX")
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/classes" "$OUT/data"
mapfile -t SOURCES < <(find "$STORAGE_FIXTURE/src" "$FIXTURE/src" -name '*.java' -print | sort)
java com.sun.tools.javac.Main -source 17 -target 17 -d "$OUT/classes" "${SOURCES[@]}" \
  "$MAIN"/{KeystoreBlobStore,EncryptedBlobEnvelope,SessionSchema,PeerBlockJournal,PeerBlockJournalSchema}.java
java -cp "$OUT/classes" dev.gyeopdam.embeddedtor.PeerBlockJournalSchemaReview
java -cp "$OUT/classes" dev.gyeopdam.embeddedtor.PeerBlockJournalHostReview "$OUT/data"
