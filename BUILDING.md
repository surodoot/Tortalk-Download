# Building this source snapshot

## Scope
The complete committed Android module's production source, resources, build files, dependency hash pins, notices, and synthetic host/unit/instrumentation test source are retained from revision 518b5b73bad55478850315d7c5db690bfd456baa.

Operational runtime approval records/controllers and private CI workflow, backend, unrelated apps, repository history, keys and user data are outside this publication. Some historic module documents link to private CI evidence or omitted supporting experiments; those links do not establish public verification.

## Toolchain
The module is standalone. Use an existing authorized Linux installation of JDK 25, Gradle 9.8.0, Android platform android-37.1, Build Tools 36.0.0 and NDK 27.3.13750724. The build verifies specific NDK/llvm-strip bytes before configuration and rejects different inputs. These restrictions have not been weakened. The unrelated parent Gradle 8.9 wrapper is intentionally not included.

From this repository root:

    bash source/518b5b7/frontend/embedded-tor/scripts/build_unsigned_android_test.sh source/518b5b7/frontend/embedded-tor

Set ANDROID_HOME and JAVA_HOME to the existing toolchain before running. Required Maven dependencies resolve from the repositories in settings.gradle.kts and are hash-checked by build.gradle.kts. This source export has not itself run the Android build or installed SDK components.

The debug/test builds disable signing. Release variants are disabled. An unsigned APK cannot be installed as a release. Do not invent signing keys or remove the gates to claim successful device validation. Signing and real-device/emulator execution remain separate tasks.

Host integration source uses synthetic fixtures. Several full-project integration scripts require the separate backend or other experiments, which are not part of this Android-only export. Compilation/test status from previous private CI is not equivalent to a reproduced public build.
