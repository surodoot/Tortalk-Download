#!/usr/bin/env bash
# Offline pure-Java contact/QR tests, using synthetic public identity bytes only.
# Provision the pinned Maven artifact separately; this runner never downloads or installs anything.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
ZXING="${ZXING_CORE_JAR:-$ROOT/.tooling/contact-qr/core-3.5.4.jar}"
CRYPTO="$ROOT/../.tooling/crypto-spike"
JUNIT="$ROOT/../.tooling/kotlin/junit-4.13.2.jar"
HAMCREST="$ROOT/../.tooling/kotlin/hamcrest-core-1.3.jar"
JSON="$ROOT/.tooling/json/json-20250517.jar"
read -r QR_HASH QR_URL < "$ROOT/scripts/contact-qr-artifacts.sha256"
printf '%s  %s\n' "$QR_HASH" "$ZXING" | sha256sum --check --status
while read -r hash url; do
  printf '%s  %s\n' "$hash" "$CRYPTO/${url##*/}" | sha256sum --check --status
done < "$ROOT/../crypto-spike/artifacts.sha256"
while read -r hash path; do
  case "$path" in
    */junit-4.13.2.jar|*/hamcrest-core-1.3.jar)
      printf '%s  %s\n' "$hash" "$ROOT/../.tooling/kotlin/${path##*/}" | sha256sum --check --status ;;
  esac
done < "$ROOT/../scripts/kotlin-test-artifacts.sha256"
while read -r hash url; do
  printf '%s  %s\n' "$hash" "$ROOT/.tooling/json/${url##*/}" | sha256sum --check --status
done < "$ROOT/scripts/json-test-artifacts.sha256"
CP="$ZXING:$CRYPTO/libsignal-client-0.105.0.jar:$CRYPTO/kotlin-stdlib-2.2.20.jar:$JSON:$JUNIT:$HAMCREST"
OUT=$(mktemp -d "$ROOT/.tooling/contact-qr-tests.XXXXXX")
trap 'rm -rf "$OUT"' EXIT
java com.sun.tools.javac.Main -source 17 -target 17 -cp "$CP" -d "$OUT" \
  "$ROOT/src/main/java/dev/gyeopdam/embeddedtor/UiCommandPolicy.java" \
  "$ROOT/src/main/java/dev/gyeopdam/embeddedtor/OnionApiRequest.java" \
  "$ROOT/src/main/java/dev/gyeopdam/embeddedtor/SignalClient.java" \
  "$ROOT/src/main/java/dev/gyeopdam/embeddedtor/SignalJson.java" \
  "$ROOT/src/main/java/dev/gyeopdam/embeddedtor/ContactCard.java" \
  "$ROOT/src/main/java/dev/gyeopdam/embeddedtor/ContactQrCodec.java" \
  "$ROOT/src/test/java/dev/gyeopdam/embeddedtor/ContactCardTest.java" \
  "$ROOT/src/test/java/dev/gyeopdam/embeddedtor/ContactQrCodecTest.java"
java -cp "$OUT:$CP" org.junit.runner.JUnitCore \
  dev.gyeopdam.embeddedtor.ContactCardTest dev.gyeopdam.embeddedtor.ContactQrCodecTest
