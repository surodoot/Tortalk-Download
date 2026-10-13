# Dependencies and corresponding-source status

Pinned app dependencies are declared in source/518b5b7/frontend/embedded-tor/build.gradle.kts. Exact resolved binary SHA-256 pins and native strip provenance remain alongside the source.

- libsignal-android/client 0.105.0, AGPLv3: https://github.com/signalapp/libsignal/releases/tag/v0.105.0 . License copy: source/518b5b7/frontend/embedded-tor/src/main/assets/libsignal-LICENSE.txt . Upstream acknowledgments are supplied by the pinned AAR; the NOTICE records their expected hash. The pinned upstream source and dependency archives have now been downloaded and recorded in [SOURCE_DOWNLOADS.md](SOURCE_DOWNLOADS.md).
- Guardian Project tor-android 0.4.9.13.2 and jtorctl 0.4.5.7: https://github.com/guardianproject/tor-android . The included guardianproject-tor-android-LICENSE.txt preserves upstream notices. The exact wrapper and six submodule source archives have been downloaded; see [SOURCE_DOWNLOADS.md](SOURCE_DOWNLOADS.md) for their immutable URLs, hashes and upstream build instructions.
- Netty 4.1.139.Final: https://github.com/netty/netty/tree/netty-4.1.139.Final . Apache-2.0 license and full notice are included.
- ZXing core 3.5.4: https://github.com/zxing/zxing . Included license preserved.
- Desugar JDK libraries 2.1.5 and Android/Gradle build dependencies retain their upstream licenses and are resolved separately.

## Remaining gates before a new APK release
1. The official libsignal v0.105.0 tag resolves to commit `4beb029d8a941f81e7d9c6d8af1ed25a677569a8`. Its [rust-toolchain](https://github.com/signalapp/libsignal/blob/4beb029d8a941f81e7d9c6d8af1ed25a677569a8/rust-toolchain) is `1.98.1`, and its [workspace Cargo.toml](https://github.com/signalapp/libsignal/blob/4beb029d8a941f81e7d9c6d8af1ed25a677569a8/Cargo.toml) declares minimum Rust `1.93.1`. The earlier Rust 1.99 warning is not supported by these verified source files and is corrected here. Exact native source/dependency availability and actual AAR provenance still require verification; this correction does not claim a reproduced native binary.
2. Review [SOURCE_DOWNLOADS.md](SOURCE_DOWNLOADS.md): exact native source archives and all 567 registry packages in the libsignal workspace Cargo.lock are available and checksum-verified. The full workspace is a superset, not the APK dependency inventory. The actual unsigned build's Gradle runtime dependency inventory is now published and its selected Java source versions are verified. Desugar release-preparation source is linked; its binary-to-source build attestation remains unverified, a provenance limit rather than an independent byte-reproduction prerequisite. These source checks do not certify legal compliance.
3. Verify the new APK against this exact source and record its hash, signing certificate, build provenance and real Android runtime results.

This source-only publication does not redistribute new dependency binaries, does not certify legal compliance, and does not change an older APK's source correspondence. Existing source notices and licenses are preserved; different versions must be labeled separately.
