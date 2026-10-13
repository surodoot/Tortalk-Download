# Source downloads for TorTalk 0.5-pilot

This index accompanies Android app source revision **518b5b73bad55478850315d7c5db690bfd456baa**. Download the [app source tree](source/518b5b7/frontend/embedded-tor/) and use [BUILDING.md](BUILDING.md). The source offer is free and separate from the APK; downloading the source is optional. It does not make an unsigned build an installable release.

## Native dependencies

[NATIVE_SOURCE_DOWNLOADS.json](NATIVE_SOURCE_DOWNLOADS.json) records immutable download URLs, commits, byte counts and SHA-256 hashes. Every listed archive was downloaded on 2026-10-13. It covers:

- libsignal 0.105.0: commit `4beb029d8a941f81e7d9c6d8af1ed25a677569a8`.
- Its Cargo.lock git dependencies: Signal's boring fork `1405df0a23a0335618f88ed328c76f67646b088f` and SPQR `06959b4708f9b7b1e94d0f8cc835f3958c077e94`.
- boring's BoringSSL submodule `e2a57cfb4d915b4ba820585aef9fdee7bca13fe5`.
- Tor Android 0.4.9.13.2: commit `170a3b4b7d13078142d34f5ac536d8f9c3cb89b6` and all six submodules: Tor, libevent, OpenSSL, zlib, zstd and elf-cleaner.

GitHub ZIP archives omit submodule contents. Place the BoringSSL source inside the boring checkout at `boring-sys/deps/boringssl`; place each Tor dependency at the `external/...` path named in the manifest. The repositories' own build scripts and patches remain in their source archives. These directories contain no additional top-level git submodule declarations in the downloaded snapshots.

The exact libsignal toolchain file specifies Rust 1.98.1; workspace Cargo.toml specifies minimum Rust 1.93.1. Its `java/build_jni.sh` and Gradle files describe Java/JNI builds for Android. Tor Android's `tor-droid-make.sh` and `external/Makefile` describe its upstream build, including NDK 30.0.16248370. That upstream build NDK is distinct from the app's pinned NDK 27.3.13750724 used to strip prebuilt Signal JNI. App strip code and provenance are preserved in the app source. This is not a claim that native binaries have been independently rebuilt.

## Rust registry sources

The five manifests [1](CARGO_SOURCES_1.json), [2](CARGO_SOURCES_2.json), [3](CARGO_SOURCES_3.json), [4](CARGO_SOURCES_4.json), [5](CARGO_SOURCES_5.json) cover all **567 registry packages** in the pinned libsignal workspace Cargo.lock. Each archive was downloaded and its SHA-256 matched Cargo.lock on 2026-10-13. They include the source, build scripts and license files supplied in the published crate packages.

This deliberately includes the entire workspace dependency superset, including test, host and non-Android targets. It is not a list of 567 libraries packaged in the APK. The exact JNI feature/target graph was not independently resolved with Cargo during this audit. Use the checked-in Cargo.lock and upstream Android build configuration; do not silently upgrade dependencies. Crates' package archives and the listed git-source archives are separate inputs.

## Java and desugaring sources

[JAVA_SOURCE_DOWNLOADS.json](JAVA_SOURCE_DOWNLOADS.json) provides verified source archives for the ten pinned Netty components, ZXing 3.5.4, jtorctl 0.4.5.7 and observed AndroidX/coroutines dependencies. Selection evidence is recorded where a version comes from APK metadata or dependency POM rather than a complete Gradle resolution report. These external URLs are source archives, not license-only links.

Desugaring source selection and its remaining provenance limit are recorded separately in that manifest. Do not substitute the current upstream default branch merely because its version file still says 2.1.5. The official 2.1.5 release-preparation source is provided, with the lack of a binary-to-source build attestation stated explicitly.

## Notices and artifact correspondence

The app retains the full AGPL license, full libsignal acknowledgments from the AAR, and existing Tor/Netty/ZXing notices. Additional exact-source Tor component notices are under [notices/](notices/); they include build-tool notices for completeness, not a claim that every tool is included in the APK. Desugar GPLv2/Classpath and assembly-exception text is included there as well. Copyright notices remain in source files.

[APK_SOURCE_CHECK.json](APK_SOURCE_CHECK.json) records the inspected **unsigned** build, SHA-256 `ecafda52c4ccf36b4a85dba7ac064adc1da49357ccb814ec59351ad57fc4ed1f`, size 72,119,353 bytes. All four Tor ABI hashes and four stripped Signal ABI hashes match the app's committed pins. No test JNI binary is present. The full production libsignal acknowledgment hash matches the committed expected value. An additional testing acknowledgment text is present; it does not mean test JNI is included.

This source audit does not certify legal compliance, exact native reproducibility, successful signing, device installation, or working Tor/chat. Before distributing a new APK, record its final source version, signature-certificate fingerprint and runtime results and provide this version's source links next to its download. If production code changes, update the source offer. Retain older APK/source labels separately.
