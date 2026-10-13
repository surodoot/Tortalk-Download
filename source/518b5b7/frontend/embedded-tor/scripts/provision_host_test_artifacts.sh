#!/usr/bin/env bash
# Explicit CI provisioning of checksum-pinned public host-test dependencies.
# No Android SDK/NDK install, terms acceptance, account credentials or signing.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
fetch() {
  local expected="$1" url="$2" dest="$3"
  mkdir -p "$(dirname "$dest")"
  if [[ ! -f "$dest" ]]; then
    curl --fail --show-error --location --proto '=https' --proto-redir '=https' --tlsv1.2 --max-time 180 "$url" -o "$dest.partial"
    printf '%s  %s\n' "$expected" "$dest.partial" | sha256sum --check --status
    mv "$dest.partial" "$dest"
  fi
  printf '%s  %s\n' "$expected" "$dest" | sha256sum --check --status
}
while read -r hash url; do
  fetch "$hash" "$url" "$ROOT/../.tooling/crypto-spike/${url##*/}"
done < "$ROOT/../crypto-spike/artifacts.sha256"
while read -r hash path; do
  case "$path" in
    */junit-4.13.2.jar|*/hamcrest-core-1.3.jar)
      fetch "$hash" "https://repo.maven.apache.org/maven2/$path" "$ROOT/../.tooling/kotlin/${path##*/}" ;;
  esac
done < "$ROOT/../scripts/kotlin-test-artifacts.sha256"
while read -r hash path; do
  fetch "$hash" "https://repo.maven.apache.org/maven2/$path" "$ROOT/.tooling/netty/${path##*/}"
done < "$ROOT/scripts/netty-artifacts.sha256"
while read -r hash url; do
  fetch "$hash" "$url" "$ROOT/.tooling/contact-qr/${url##*/}"
done < "$ROOT/scripts/contact-qr-artifacts.sha256"
while read -r hash url; do
  fetch "$hash" "$url" "$ROOT/.tooling/json/${url##*/}"
done < "$ROOT/scripts/json-test-artifacts.sha256"
fetch '3707dc100381e3bbcc57b85255fd313ae20875fc65cf3130c47e86f044ad77f0' \
 'https://repo.maven.apache.org/maven2/org/robolectric/android-all/8.0.0_r4-robolectric-r1/android-all-8.0.0_r4-robolectric-r1.jar' \
 "$ROOT/.tooling/android-all-8.0.0_r4-robolectric-r1.jar"
printf '%s\n' 'Pinned host-test dependencies verified; no Android device/runtime claim.'
