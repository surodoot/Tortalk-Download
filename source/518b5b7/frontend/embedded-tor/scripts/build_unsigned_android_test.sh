#!/usr/bin/env bash
# Guard the existing compile/lint/unit-test build and both unsigned APK assemblies.
# No signing, SDK installation, emulator, app installation or Android runtime.
set -euo pipefail
module=$(cd -- "${1:?usage: build_unsigned_android_test.sh STANDALONE_MODULE}" && pwd)
scripts="$module/scripts"
report="$module/build/reports/unsigned-android-test"
: "${ANDROID_HOME:?Existing reviewed Android SDK is required}"
test -d "$ANDROID_HOME/platforms/android-37.1"
test -d "$ANDROID_HOME/build-tools/36.0.0"
test -x "$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer"
test -f "$scripts/unsigned_android_test.init.gradle"
mkdir -p "$report"
# Keep Android user-state output in this disposable build, without changing HOME.
export ANDROID_USER_HOME="$report/android-user-home"
mkdir -p "$ANDROID_USER_HOME"
python3 "$scripts/debug_keystore_inventory.py" --output "$report/debug-keystore-before.json"
check_keystores() {
  python3 "$scripts/debug_keystore_inventory.py" \
    --compare "$report/debug-keystore-before.json" --output "$report/debug-keystore-after.json"
}
trap check_keystores EXIT
# Preserve normal Maven dependency resolution from the existing CI build. Installed
# SDK/NDK components are required; automatic SDK downloads remain explicitly disabled.
# This is the FIRST task-bearing Gradle invocation in the standalone CI build.
gradle --no-daemon --no-build-cache --no-configuration-cache \
  --init-script "$scripts/unsigned_android_test.init.gradle" -p "$module" \
  -Pandroid.builder.sdkDownload=false \
  -Pandroid.experimental.useDefaultDebugSigningConfigForProfileableBuildtypes=false \
  assembleDebug assembleDebugAndroidTest lintDebug testDebugUnitTest compileDebugAndroidTestJavaWithJavac
mapfile -t apps < <(find "$module/build/outputs/apk/debug" -maxdepth 1 -type f -name '*.apk')
mapfile -t tests < <(find "$module/build/outputs/apk/androidTest/debug" -maxdepth 1 -type f -name '*.apk')
test "${#apps[@]}" -eq 1
test "${#tests[@]}" -eq 1
"$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer" manifest print "${tests[0]}" > "$report/test-merged-manifest.xml"
python3 "$scripts/verify_unsigned_android_test.py" --app "${apps[0]}" --test "${tests[0]}" \
  --test-manifest "$report/test-merged-manifest.xml" --output "$report/unsigned-apk-report.json"
