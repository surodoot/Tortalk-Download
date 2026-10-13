# TorTalk pilot app and embedded Tor diagnostics

This is a **separate Android application and standalone Gradle build**. The 0.5
pilot adds the approved packaged conversation design, native account/admission
flows, official libsignal and a ciphertext-only API client; the fixed Tor probe
remains under Developer options. It embeds Guardian Project's Tor AAR and real
`org.torproject.jni.TorService` JNI lifecycle, without Orbot, VPN, a downloaded
executable or onion-service creation. See [the pilot API](../../docs/tortalk-pilot-api.md)
and [Signal verification](SIGNAL_CLIENT.md). Source/host tests do not establish
Android runtime behavior, anonymity or real two-phone messaging. Earlier
version-specific diagnostic results below remain historical evidence. The isolated
Unix SOCKS candidate and its pending Android proof are described in
[the socket boundary review](UNIX_SOCKET_BOUNDARY.md).

## What is implemented

- Explicit Korean “Tor connection test” screen. Start binds an app-local, non-exported TorService, which loads the APK's `libtor.so` using `System.loadLibrary` and starts the upstream C Tor implementation.
- `torrc` lives in the upstream app-private directory. A fixed filesystem Unix SOCKS listener under an app-owned 0700 directory, onion traffic only, client-only mode, no TCP SOCKS/control/HTTP-tunnel/DNS/transparent proxy listener. The upstream control socket stays in its private app data directory. This is app-local configuration, not a system network/security setting.
- Polls `status/bootstrap-phase`, `status/circuit-established`, and `net/listeners/socks` on the bound service's control connection, off the UI thread. No broadcast (including a spoofed “ON”) can unlock readiness. Only bootstrap 100%, an established circuit, the sole exact locally derived Unix listener, verified socket ownership/mode, and the current service PID produce readiness.
- The pure `TorReadinessGate` returns no endpoint before readiness, after stop/failure/disconnect, on stale control replies or when readiness exceeds its 2-second lease. Startup has a 120-second deadline. No direct-connect fallback exists.
- The controller is shared across Activities and owns at most one native Tor
  service instance per application process. Losing the last foreground owner
  immediately revokes app readiness and cancels API sockets, then requests
  `DisableNetwork 1` on the control worker. There is no foreground-service,
  boot receiver, automatic reconnection or background messaging feature.
- A manual restart requires death of the exact retained Binder and creates a fresh
  private `:tor` incarnation. The main process never uses a cached PID as kill
  authority. Readiness is never restored from disk, and stale replies cannot revive
  or poison a newer attempt.
- The private process retains its independent command/owner/stop watchdog and only
  self-terminates after verifying its own UID/process identity. Host tests exercise
  deadline decisions and immediate API revocation; they do not prove bounded native
  Tor silence on Android, scheduler guarantees during suspension, or actual process
  teardown on every device. An unready UI alone is not zero-traffic proof.
- The diagnostic screen retains its fixed official onion GET probe. It renders no remote page and never sends account or message data. The separate pilot client uses only an explicitly configured canonical onion origin.
- `FLAG_SECURE` and backup exclusions remain. Authentication and secret entry stay native, outside the packaged WebView; app-authored diagnostics omit exception/endpoint/credential contents. Upstream Tor may still emit its own diagnostics and retain Tor guard/cache state; `AvoidDiskWrites` does not mean zero disk writes or encrypted Tor state.

## Fixed official onion HTTP probe

A separate Korean button is enabled only while the control-backed Tor readiness lease is fresh, and disabled while a request is running. A button tap makes exactly one `GET /` to port 80 of
`xao2lxsmia2edq2n5zxg6uahx6xox2t7bfjw6b5vdzsxi7ezmqob6qid.onion`.
The endpoint is the **Tor Project onion directory**, verified against its official HTTPS listing on 2026-10-08: https://onion.torproject.org/ . The fixed public request sends only `Host`, `Content-Length: 0`, and `Connection: close`; no username, password, bearer token, cookie, user agent, device token, or message data. The operator still sees the request and its timing.

- A separate probe worker runs Netty 4.1.139.Final's maintained SOCKS5 and HTTP/1 codecs. It never blocks the 500 ms Tor control polling worker or Android UI thread.
- The sole proxy is the locally derived private Unix socket; its connected kernel UID and PID must match the current Binder lease before any SOCKS bytes. `NoopAddressResolverGroup` and `createUnresolved` pass the fixed onion hostname to SOCKS5 as DOMAIN, with only NO_AUTH offered. There is no local DNS, direct route, retry, global authenticator, redirect following, arbitrary URL/path field, or general HTTP client.
- Each request has a 30-second total deadline, 1-second local Unix connect limit; SOCKS onion negotiation shares the 30-second total deadline, 4 KiB HTTP initial-line limit, 8 KiB headers and a 96 KiB response-body limit. The body is not decompressed, interpreted, displayed or persisted. HTTP parser failure, oversized or truncated data fails closed. A complete response only reports a numeric HTTP status and byte count; 3xx/4xx/5xx are HTTP responses, not successful chat or delivery.
- Failure/timeout diagnostics expose only fixed stage/reason codes and elapsed milliseconds. No exception messages, peer addresses, headers, payloads or stack traces are retained. Stages distinguish setup, local TCP connect, SOCKS negotiation, HTTP write and HTTP response read. A SOCKS wait now consumes the existing overall budget instead of failing generically after 10 seconds. This is a diagnostic improvement, not a confirmed remedy for any particular Android device.
- Readiness is bound to both the Tor attempt and an uninterrupted readiness generation. Circuit loss/recovery and a SOCKS listener change invalidate old requests even if the new sample is READY. Repeated taps cannot open parallel requests. Stop/background first revoke readiness, then cancel and request closure of the live channel before Tor teardown. The worker checks its lease at most 100 ms apart even if the UI/control thread stalls. Main-thread callback delivery rechecks the lease and request generation, ignoring cancelled/superseded results.
- Netty's default channel ID gathers hardware/process data. This narrow client overrides it with an ephemeral random local ID; it is not transmitted or saved. No stream-isolation claim is made.
- The manifest retains `usesCleartextTraffic=false`. This low-level socket HTTP path is deliberately restricted to the fixed onion destination through local Tor, whose onion protocol supplies transport protection. It is not a general cleartext HTTP exception, HTTPS certificate check, or chat E2EE implementation.

## Deliberate boundaries

**No message sending, backend API, account or chat transport is wired.** This source proves an integration path, not device execution until the device test below passes. The separate `../tor-transport` Kotlin/JVM adapter remains unlinked. Its general onion validator depends on a SHA3-256 provider, which is not reliably present throughout Android API26+. This experiment takes no endpoint input and uses the exact fixed published onion above; it does not implement a replacement checksum/crypto algorithm or add a crypto provider. Tor validates the fixed onion address as part of its protocol. Local SOCKS port discovery does not cryptographically authenticate a proxy against other same-device processes.

Bootstrap readiness is neither a successful onion request nor a delivery/read receipt, peer identity proof, network leak audit, or E2EE. Tor network directory/relay contact begins when Start is pressed. The app does not host an onion service or lift the backend's development/public-access gate.

## Official provenance and version choice (2026-10-08)

- Official project: https://github.com/guardianproject/tor-android
- Guardian introduction: https://guardianproject.github.io/info/code/tor-android/
- Maven Central metadata: https://repo.maven.apache.org/maven2/info/guardianproject/tor-android/maven-metadata.xml
- Pinned latest published Android release: `info.guardianproject:tor-android:0.4.9.13.2`, plus `info.guardianproject:jtorctl:0.4.5.7`. Maven metadata was last updated `20261006030258`. The README's 0.4.9.12 example was stale, so it was not used.
- Exact AAR: https://repo.maven.apache.org/maven2/info/guardianproject/tor-android/0.4.9.13.2/tor-android-0.4.9.13.2.aar
- AAR SHA-256: `40be745450a8ecfaac8558950bf046f596d7ef6e88107f69334a8c4b6e7d14be`. `preBuild` verifies the actual resolved AAR bytes and the actual resolved jtorctl JAR (SHA-256 `51bbed22de9fe0028095223ceeb4694d8731eda2fc01edee07b8c0c8a46681c3`) before packaging. The packaging script separately verifies each ABI's bytes. These are hashes of the retrieved official artifact, **not** independent signature authentication or a reproducible-build audit.
- Actual AAR examined: four ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`); manifest minimum API24; Java classfile major 68 (Java24); AAR metadata minimum compile SDK37.1. ARM64 ELF PT_LOAD alignment inspected at 16KiB. APK byte checks do not prove 16KiB-device execution.
- 0.4.9.13 upstream security release: https://forum.torproject.org/t/security-release-0-4-9-13/22178/1 . It fixes high-severity issues affecting clients, onion services and relays. Do not downgrade to 0.4.9.12 merely to use an older Android toolchain. This pinned Android artifact embeds the .13 line; recheck newer Tor/Android security releases before any real-world use.
- POM declares BSD-3-Clause and names Guardian Project as publisher. The upstream LICENSE is preserved in `src/main/assets/guardianproject-tor-android-LICENSE.txt` (upstream legacy text mentions other Orbot components; that does not mean they are packaged here). Native Tor/libevent/OpenSSL/zlib/zstd notices, jtorctl and transitive dependency notice closure still require a release audit. Release variants are disabled.

### Probe dependency provenance

`netty-handler-proxy` and `netty-codec-http` are pinned to `4.1.139.Final`, with their ten resolved Netty JARs pinned by SHA-256 in `scripts/netty-artifacts.sha256`. `verifyProbeArtifacts` verifies the exact resolved runtime set and bytes before packaging; the probe retains the SOCKS/HTTP codecs over a reviewed public-Android Unix adapter; no Netty native transport binary or TLS provider is added. Official sources: https://netty.io/ and https://repo.maven.apache.org/maven2/io/netty/ .

The runtime closure includes `netty-transport-native-unix-common` as a **required**, non-optional dependency of `netty-handler`; its unclassified JAR contains shared Java classes and no `.so`/`.dll`/`.dylib` binary. The exact release POMs establish this edge and its existing common/buffer/transport dependencies:
https://repo.maven.apache.org/maven2/io/netty/netty-handler/4.1.139.Final/netty-handler-4.1.139.Final.pom and
https://repo.maven.apache.org/maven2/io/netty/netty-transport-native-unix-common/4.1.139.Final/netty-transport-native-unix-common-4.1.139.Final.pom .
The first probe CI correctly rejected the incomplete nine-JAR pin set before compilation. The pin list now includes the required tenth JAR; the exact-set guard remains and reports missing/unexpected filenames. Optional JNI source/native/TLS artifacts and parent-managed test dependencies are not runtime dependencies.

`src/main/assets/netty-LICENSE.txt` and `netty-NOTICE.txt` preserve the upstream release's Apache-2.0 license and full notice, retrieved from https://github.com/netty/netty/tree/netty-4.1.139.Final . The full notice also mentions optional modules not used by this probe. Complete third-party notice/license closure for all bundled Tor/Netty components still needs review before redistribution; release remains disabled.

## Independent toolchain, no changes to offline app

Use **JDK25, Gradle9.8.0, AGP9.4.1, Android SDK platform `android-37.1`, Build Tools36.0.0**. App bytecode targets Java17; the compiler/runtime must read the upstream Java24 classes. Compile SDK37.1 satisfies the AAR metadata; `minSdk=26` and `targetSdk=35` are distinct and are not a device-compatibility claim. The standalone build has no Kotlin sources/plugin requirement.

Official upstream pins: https://github.com/guardianproject/tor-android/blob/master/gradle/libs.versions.toml and https://github.com/guardianproject/tor-android/blob/master/gradle/wrapper/gradle-wrapper.properties . Android toolchain reference: https://developer.android.com/build/releases/agp-9-4-0-release-notes . The parent project's Gradle8.9/AGP8.7/SDK35 must remain separate. Do not suppress AAR metadata, substitute an older vulnerable binary, or auto-accept new SDK terms.

Android10+ forbids exec from writable app-home paths. JNI loading avoids that approach: https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission . Screen-bound service use avoids claiming compliance with the more involved ongoing foreground-service/background restrictions. Android17 local-network permission behavior is another target-SDK upgrade test; this app does not request LAN access.

With the listed **already installed** tools:

```sh
cd frontend/embedded-tor
gradle --no-daemon assembleDebug lintDebug testDebugUnitTest
python3 scripts/verify_embedded_tor.py
apkanalyzer manifest print build/outputs/apk/debug/embedded-tor-debug-unsigned.apk > .tooling/merged-manifest.xml
python3 scripts/verify_embedded_tor.py --apk build/outputs/apk/debug/embedded-tor-debug-unsigned.apk --merged-manifest .tooling/merged-manifest.xml
```

Gradle may name the single-project APK `GyeopdamEmbeddedTorExperiment-debug-unsigned.apk`; locate the generated APK under `build/outputs/apk/debug` rather than assuming its basename. `apkanalyzer` is the official preinstalled SDK tool. The verifier accepts its plain XML output, checks exact permission set/non-exported service/backup/cleartext flags, rejects APK signing blocks or v1 signatures, and compares all four native libraries to the official pinned bytes. Native bytes are preserved with `keepDebugSymbols` for that check.

No signing key is generated/configured. The APK is unsigned and cannot be installed as-is. Real/emulated-device validation needs a **separately authorized** test signing setup and an already authorized device/network test. No install, signing, SDK-license acceptance or real Tor connection was performed during source preparation.

## Verification and remaining device test

Local checks completed: 69 pure Java gate/session/mock SOCKS+HTTP JUnit tests (JDK21 compiler with source/target17); source/manifest isolation; official AAR digest and four ABI byte hashes. These do not constitute Android compilation, Android lint, D8 or native execution. Exact Android compile/lint/merged manifest/APK checks belong to the independent CI toolchain; record their actual outcome separately.

After signing/device authorization, test on arm64 and x86_64 (including API26, current target behavior and a 16KiB page-size device): fresh launch blocks transport; Start loads JNI and reaches actual bootstrap100/circuit established; airplane mode/control loss revokes readiness; background/Back/rotation/Stop revoke before shutdown; rapid repeated Start cannot double-start; return does not auto-start; process death does not restore readiness; listener is loopback only and unwanted listeners remain disabled. Check no raw control diagnostics are exposed in UI. Still do not send real user messages. After the same signing/device/network authorization, tap the official onion button only while READY. Confirm the SOCKS request uses DOMAIN and only the fixed host/80, observe HTTP metadata without HTML, then test cancellation during SOCKS and response body; delayed callback after background/Stop must not update the cancelled result. Test freshness loss, listener change, rapid repeated taps and 3xx responses. Device traffic/DNS observations, live onion exchange, Netty Android runtime behavior and leak testing remain subsequent validation; the host mock tests do not establish them.

### Host-only probe tests

```sh
bash scripts/test_probe_standalone.sh
```

This uses the already cached pinned Netty/JUnit JARs from the adjacent `.tooling` directory (or `NETTY_JAR_DIR`, `JUNIT_JAR`, `HAMCREST_JAR`). It neither downloads dependencies nor connects to Tor. The tests use only synthetic numeric-loopback proxy peers and cover exact SOCKS/HTTP bytes, failed authentication/handshake, no redirect connection, body/header/parser limits, truncation, absolute timeout, channel EOF on cancellation/readiness loss, request epochs, stale results and duplicate taps.

### Android API26 compatibility check

```sh
python3 scripts/check_probe_api26.py --fetch
```

Passed locally on 2026-10-08: `TorReadinessGate`, `OnionProbeSession`, and `FixedOnionProbe` compile against the **official AOSP API26 public API JAR as the bootclasspath**, with AOSP's compiler-only lambda stub and the ten pinned Netty JARs. This prevents desktop Java APIs from silently satisfying calls in our probe code. `CompletableFuture` and the `java.util.function` interfaces used here are available from Android API24; no SHA3 provider, `HexFormat`, or Java HTTP client is used.

The optional `--fetch` flag downloads two hash-pinned public compile fixtures from https://android.googlesource.com/platform/prebuilts/sdk/ : `26/public/android.jar` and `tools/core-lambda-stubs.jar`. It does not install an Android SDK, accept SDK terms, create a key, sign an APK, or run a device. Neither fixture becomes an APK dependency. `--netty-dir` can point to an existing pinned JAR cache. The usual AGP `assembleDebug lintDebug testDebugUnitTest` remains the independent Android/D8/minSdk26 validation gate. This source API check does **not** validate every code path inside Netty, lambda desugaring output, native execution or Android26 runtime behavior; those still need the authorized device matrix above.

### Additional host-side configuration check

On 2026-10-08, the exact app torrc text was passed to Tor 0.4.9.13
`--verify-config` and returned `Configuration was valid`. The host executable
came from the official Debian package, SHA-256
`bc81f046d312ddb07ca3efe91826e8858961296aec294a8a29e5dfc9ff8ed812`, previously
checked against the signed Debian archive index. This checks configuration syntax
only. It does not execute the Android JNI runtime or prove a live Tor circuit.

### CI standalone-project isolation

The first exact-commit CI compiled the unsigned APK and passed the readiness unit
tests, then lint incorrectly discovered the *separate offline demo's* Gradle 8.9
wrapper in a parent directory. The embedded build itself used Gradle9.8/AGP9.4.
CI now exports the committed `frontend/embedded-tor` tree with `git archive` into
`RUNNER_TEMP`, runs the complete independent build/lint/tests there, and verifies
that APK. No lint rule is disabled or lowered, and the offline wrapper is unchanged.
The resulting rerun, not the first compile alone, determines the final CI result.

## Diagnostic revision 0.3 (2026-10-08)

A device reported a generic failure after approximately ten seconds. The previous SOCKS negotiation timer matches that timing, but this does not prove the device-specific cause. This revision exposes sanitized stage/reason codes and elapsed time, lets SOCKS negotiation use the existing 30-second total budget, and guards late negotiation/response completion against that deadline. Local regression coverage includes 10.5-second negotiation success, stalled and late handshake closure with no GET after expiry, response bounds, setup failures, and existing DNS/fallback/readiness/cancellation boundaries. Device runtime and actual onion availability still require validation.

## Diagnostic log revision 0.4 (2026-10-08)

The v0.3 device result `SOCKS_HANDSHAKE:DEADLINE` at 30,000 ms established a local TCP connection but could not distinguish an unanswered SOCKS greeting from an unanswered onion CONNECT. Both cases were reproduced against synthetic local peers; this did not establish a device-specific transport defect or an onion-service outage.

The fixed transport now observes Netty's decoded SOCKS messages without replacing its codecs. `SOCKS_GREETING` covers initial NO_AUTH negotiation; `ONION_CONNECT` begins when Netty writes its typed DOMAIN CONNECT request. A rejected method or CONNECT response exposes only a fixed reason and an unsigned 0–255 reply code. No extra endpoint, Tor option, credentials, retry, DNS path, or timeout extension is added. The 30-second total deadline, 10-second local connect bound, readiness lease, and late-response/cancellation guards remain.

An Activity-owned diagnostic panel records only typed state/stage/reason values and bounded numeric protocol metadata. It is capped at 128 entries and 8,192 characters including its version header, uses elapsed time rather than wall-clock timestamps, and is cleared by the user's Clear button or Activity/process loss. It does not read Tor's raw logs or persist/export automatically. Copy requires an explicit action and a clipboard warning; other software or clipboard sync may access copied text. The Android sensitive-clipboard hint is best effort and is not a confidentiality guarantee. No account/message content, token/key, address, onion identifier, raw exception, device identifier or circuit identifier is accepted into the log schema. FLAG_SECURE remains enabled.

This revision improves evidence for the next device test. It does not claim that the observed device's onion connection is fixed. No new signed build or production readiness is implied by source changes or JVM tests.
