# Dependencies and corresponding-source status

Pinned app dependencies are declared in source/518b5b7/frontend/embedded-tor/build.gradle.kts. Exact resolved binary SHA-256 pins and native strip provenance remain alongside the source.

- libsignal-android/client 0.105.0, AGPLv3: https://github.com/signalapp/libsignal/releases/tag/v0.105.0 . License copy: source/518b5b7/frontend/embedded-tor/src/main/assets/libsignal-LICENSE.txt . Upstream acknowledgments are supplied by the pinned AAR; the NOTICE records their expected hash. Upstream reference is not a verified local corresponding-source archive.
- Guardian Project tor-android 0.4.9.13.2 and jtorctl 0.4.5.7: https://github.com/guardianproject/tor-android . The included guardianproject-tor-android-LICENSE.txt preserves upstream notices. Complete exact native source/build-input closure must still be verified for the binary to be distributed.
- Netty 4.1.139.Final: https://github.com/netty/netty/tree/netty-4.1.139.Final . Apache-2.0 license and full notice are included.
- ZXing core 3.5.4: https://github.com/zxing/zxing . Included license preserved.
- Desugar JDK libraries 2.1.5 and Android/Gradle build dependencies retain their upstream licenses and are resolved separately.

## Remaining gates before a new APK release
1. Resolve the previously observed Rust 1.99 metadata versus 1.98.1 native-build provenance mismatch for libsignal, and archive the exact source/submodules/build inputs needed for the distributed native bytes.
2. Verify corresponding-source availability and license/notice closure for all included native/dependency components; do not treat a project homepage or a license link as proof that this is complete.
3. Verify the new APK against this exact source and record its hash, signing certificate, build provenance and real Android runtime results.

This source-only publication does not redistribute new dependency binaries, does not certify legal compliance, and does not change an older APK's source correspondence. Existing source notices and licenses are preserved; different versions must be labeled separately.
